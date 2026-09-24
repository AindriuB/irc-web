package io.github.aindriub.ircweb.irc;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.aindriub.irc.client.bot.BotListener;
import io.github.aindriub.irc.client.bot.IRCBot;
import io.github.aindriub.irc.client.bot.IRCBotBuilder;
import io.github.aindriub.irc.client.bot.MessageContext;
import io.github.aindriub.irc.client.event.Event;
import io.github.aindriub.irc.client.event.EventHandler;
import io.github.aindriub.irc.client.handler.ServerRefusedException;
import io.github.aindriub.irc.client.message.IRCMessage;
import io.github.aindriub.irc.client.message.IRCMessageParser;
import io.github.aindriub.irc.client.message.IRCParseException;
import io.github.aindriub.irc.client.state.ChannelState;
import io.github.aindriub.irc.client.state.ChannelUser;

/**
 * One browser's IRC connection.
 *
 * <p>Most of what the library emits arrives on a Netty event loop thread and is
 * handed straight to {@code sink}. The exception is {@link Forwarder#onReady}: after
 * a reconnect (irc-client 1.2.0+) it runs on irc-client's own dedicated
 * connection-event thread instead, not a Netty loop. Either way the sink is
 * responsible for getting the event to the browser safely; nothing here blocks,
 * because blocking an event loop thread stops the connection it belongs to, and
 * blocking the connection-event thread would delay every later connection-state
 * event and reconnect attempt.
 */
public class IrcSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(IrcSession.class);

    /**
     * Numerics that are either already shown somewhere better or are pure volume:
     * the MOTD, the NAMES reply the member list is built from, and ISUPPORT. Showing
     * these buries the replies a person actually asked for, such as a WHOIS.
     */
    private static final java.util.Set<String> QUIET = java.util.Set.of(
            "001", "002", "003", "004", "005",
            "251", "252", "253", "254", "255", "265", "266",
            "353", "366",
            "372", "375", "376", "422");

    private final Consumer<OutboundEvent> sink;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile IRCBot bot;

    /**
     * -1 in production, which leaves irc-client's own reconnect backoff (1000 ms
     * initial, 60000 ms max, unlimited attempts) untouched. Set only through
     * {@link #setReconnectBackoffForTest}.
     */
    private volatile long testReconnectInitialDelayMillis = -1;
    private volatile long testReconnectMaxDelayMillis = -1;

    /**
     * Set only while a connect is in progress, and read only by the connecting
     * thread after {@code IRCBot.start()} returns or throws. Written from a Netty
     * thread ({@link RawForwarder}) and from {@link Forwarder#onReady}, which after
     * a reconnect runs on irc-client's own connection-event thread rather than a
     * Netty loop; volatile is enough because each of {@link #capturedNotice} and
     * {@link #capturedError} only ever has one writer at a time.
     */
    private volatile boolean capturingServerText;
    private volatile String capturedNotice;
    private volatile String capturedError;

    public IrcSession(Consumer<OutboundEvent> sink) {
        this.sink = sink;
    }

    /**
     * Package-private seam so a test can make irc-client retry within about 100 ms
     * instead of waiting out the library's default backoff. Production code never
     * calls this, so it keeps the library's own defaults. {@code maxAttempts} is
     * deliberately not a parameter here: leaving reconnection unlimited, exactly
     * like production, is what task 03's finite-attempts behaviour is tested
     * against.
     */
    void setReconnectBackoffForTest(long initialDelayMillis, long maxDelayMillis) {
        this.testReconnectInitialDelayMillis = initialDelayMillis;
        this.testReconnectMaxDelayMillis = maxDelayMillis;
    }

    /**
     * Connects and registers. Blocks until the server accepts registration, which is
     * what {@code IRCBot.start()} promises, so callers run it off the request thread.
     */
    public void connect(IrcServer server, ConnectRequest request) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("this session is already connected");
        }

        // Checked before anything is built: a password or SASL value that irc-client
        // would refuse anyway is refused here, with a message that never echoes it,
        // rather than reaching the library and coming back wrapped in a stack trace.
        String credentialProblem = firstCredentialProblem(request);
        if (credentialProblem != null) {
            running.set(false);
            sink.accept(OutboundEvent.status("disconnected", credentialProblem));
            throw new IllegalArgumentException(credentialProblem);
        }

        // Capture starts only now that the credential check has passed, and stops
        // once this attempt settles (onReady, or the catch below): a NOTICE or
        // ERROR seen at any other time, such as after registration, must never end
        // up in a later failure's reason.
        capturedNotice = null;
        capturedError = null;
        capturingServerText = true;

        IRCBot built = null;
        try {
            sink.accept(OutboundEvent.status("connecting",
                    server.name() + " as " + request.nick()));

            IRCBotBuilder builder = IRCBot.builder()
                    .host(server.host())
                    .port(server.port())
                    .nick(request.nick())
                    .listener(new Forwarder());

            if (request.channels() != null && !request.channels().isEmpty()) {
                builder.channels(request.channels().toArray(new String[0]));
            }
            if (hasText(request.password())) {
                builder.password(request.password());
            }

            var clientBuilder = builder.client()
                    .secure(server.tls())
                    // A local server presents a certificate it generated itself, so
                    // validating it would fail by design. Never relaxed for a public
                    // network, where an unvalidated certificate is the whole problem.
                    .trustAllCertificates(server.tls() && server.isLocal())
                    .realname("irc-web")
                    // Raw lines go to the browser too, as their own buffer. It is
                    // how you tell "the server refused this" from "this client did
                    // not send it", without reaching for the server's logs.
                    .eventListener(new RawForwarder());

            long testInitial = testReconnectInitialDelayMillis;
            long testMax = testReconnectMaxDelayMillis;
            if (testInitial >= 0 && testMax >= 0) {
                // Test-only seam: keeps maxAttempts at 0 (unlimited), same as
                // production, and only shortens the delay between attempts.
                clientBuilder.reconnectBackoff(testInitial, testMax, 0);
            }

            if (hasText(request.saslPassword())) {
                builder.client().sasl(
                        hasText(request.saslUsername()) ? request.saslUsername() : request.nick(),
                        request.saslPassword());
            }

            built = builder.build();
            bot = built;
            startBot(built);
        } catch (Throwable t) {
            // Netty can hand a plain checked IOException straight back through
            // start(), unwrapped, and a broken TLS classpath or an OutOfMemoryError
            // surfaces as an Error, not an Exception. Every one of them has to stop
            // the bot this attempt built and clear running, or it is orphaned with
            // its own reconnect loop running forever and unreachable by anything
            // that could stop it.
            running.set(false);
            stopBuilt(built);
            capturingServerText = false;
            String reason = mapFailureReason(t);
            // Only the throwable's own class and the safe, mapped reason are
            // logged. Never t.getMessage() or t itself: irc-client 1.1.0 put the
            // rejected value in a cause's message, and although 1.2.1's IRCText
            // validators and eager builder checks (task 01/02 of the 1.2.1 security
            // patch) no longer do that, logging the throwable is kept off the table
            // as defence in depth rather than trusted to stay that way forever.
            LOGGER.info("Connection to {} failed: {}", server.id(), t.getClass().getSimpleName());
            sink.accept(OutboundEvent.status("disconnected",
                    withServerText(server, request, reason)));
            if (t instanceof Error error) {
                // Not this method's to translate: cleanup above has already run,
                // and the caller needs to see what actually happened.
                throw error;
            }
            throw new IllegalStateException(reason);
        }
    }

    /**
     * Package-private seam so a test can make a start attempt fail with something
     * irc-client cannot be driven to produce over a real socket, such as an Error.
     * Production code always takes this, unchanged.
     */
    void startBot(IRCBot bot) {
        bot.start();
    }

    /**
     * Stops the bot this attempt built, using the reference captured before the
     * failure rather than the {@code bot} field: a concurrent {@link #disconnect()}
     * may already have replaced or cleared that field, and must not be able to make
     * this cleanup stop nothing.
     */
    private void stopBuilt(IRCBot built) {
        if (built == null) {
            return;
        }
        if (bot == built) {
            bot = null;
        }
        try {
            built.stop();
        } catch (RuntimeException e) {
            LOGGER.debug("Stop after a failed connect was not clean", e);
        }
    }

    public void say(String target, String text) {
        require().say(target, text);
        // The server does not echo our own messages back, so show it locally or the
        // sender never sees what they said.
        sink.accept(OutboundEvent.message(target, require().getNick(), text, true));
    }

    public void join(String channel) {
        require().join(channel);
    }

    public void part(String channel) {
        require().part(channel);
    }

    /**
     * Sends a line exactly as typed. The escape hatch that makes this useful for
     * testing anything the bot API does not cover.
     */
    public void raw(String line) {
        require().getClient().send(line);
    }

    public void disconnect() {
        IRCBot current = bot;
        bot = null;
        running.set(false);
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException e) {
                LOGGER.debug("Disconnect was not clean", e);
            }
        }
        sink.accept(OutboundEvent.status("disconnected", null));
    }

    public boolean isRunning() {
        IRCBot current = bot;
        return current != null && current.isRunning();
    }

    private IRCBot require() {
        IRCBot current = bot;
        if (current == null) {
            throw new IllegalStateException("not connected");
        }
        return current;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static final String NICK_AS_SASL_USERNAME_REASON =
            "The nick cannot contain spaces or start with ':' when it is also the SASL "
                    + "username";

    /** Empty when every effective credential is usable, else a message safe to show. */
    private static String firstCredentialProblem(ConnectRequest request) {
        return CredentialRules.checkPassword(request.password())
                .or(() -> CredentialRules.checkSaslUsername(request.saslUsername()))
                .or(() -> checkNickAsSaslUsername(request))
                .or(() -> CredentialRules.checkSaslPassword(request.saslPassword()))
                .orElse(null);
    }

    /**
     * When there is a SASL password but no SASL username, {@link #connect} uses the
     * nick as the SASL username, so the nick has to pass the same rule or irc-client's
     * {@code sasl()} throws an {@link IllegalArgumentException} and the credential
     * message points at the wrong field.
     */
    private static java.util.Optional<String> checkNickAsSaslUsername(ConnectRequest request) {
        if (!hasText(request.saslPassword()) || hasText(request.saslUsername())) {
            return java.util.Optional.empty();
        }
        return CredentialRules.checkSaslUsername(request.nick()).isPresent()
                ? java.util.Optional.of(NICK_AS_SASL_USERNAME_REASON)
                : java.util.Optional.empty();
    }

    /**
     * Prefixes {@code reason} with what the server itself said, when a NOTICE or
     * ERROR was captured during this attempt, e.g. {@code "Twitch said: Login
     * unsuccessful. The connection closed before registration finished. Try
     * again."}. An ERROR seen during the attempt wins over a NOTICE, since it is
     * the server's own last word before closing the connection. Returns {@code
     * reason} unchanged when nothing usable was captured, which is exactly what
     * this method returned before task 02: no NOTICE or ERROR seen, or all of it
     * turned out to be sanitised away (which includes being nothing but a
     * credential this attempt sent, once {@link #scrubCredentials} has redacted
     * it).
     */
    private String withServerText(IrcServer server, ConnectRequest request, String reason) {
        String raw = capturedError != null ? capturedError : capturedNotice;
        String sanitised = ServerText.sanitise(scrubCredentials(raw, request));
        if (sanitised == null) {
            return reason;
        }
        return server.name() + " said: " + sanitised + ". " + reason;
    }

    private static final String REDACTED = "[redacted]";

    /**
     * A server can echo back exactly what it was sent, and a bad password or SASL
     * password is exactly the sort of thing that shows up in an ERROR or NOTICE
     * explaining why registration failed ("bad password oauth:sekrit-XYZ" is the
     * shape Twitch used in production). Every credential this attempt configured is
     * redacted here, before {@link ServerText#sanitise} ever sees the text, so none
     * of it can reach the browser, a log, or an exception message. Null or empty
     * values are skipped, since {@link String#replace} would otherwise do nothing
     * useful with them anyway.
     */
    private static String scrubCredentials(String raw, ConnectRequest request) {
        if (raw == null) {
            return null;
        }
        String scrubbed = redact(raw, request.password());
        scrubbed = redact(scrubbed, request.saslPassword());
        String password = request.password();
        if (hasText(password) && password.regionMatches(true, 0, "oauth:", 0, 6)) {
            // The token half of "oauth:<token>" is worth redacting on its own too:
            // a server that only echoes the token, without the "oauth:" prefix the
            // full-password redaction above matches, must not leak it either.
            scrubbed = redact(scrubbed, password.substring("oauth:".length()));
        }
        return scrubbed;
    }

    private static String redact(String text, String value) {
        if (value == null || value.isEmpty()) {
            return text;
        }
        return text.replace(value, REDACTED);
    }

    private static final String CREDENTIAL_NOT_VALID_REASON =
            "A saved credential is not valid for IRC. Edit the profile and save it again.";
    private static final String UNREACHABLE_REASON =
            "Could not reach the server. Check the host and port, then try again.";
    private static final String SERVER_REFUSED_REASON =
            "The server refused the connection. Check the server settings and try again.";

    /**
     * A fixed, credential-free reason for each failure irc-client can report on a
     * first connect. Matched only on the outermost throwable's own type or message,
     * never on a cause's message: {@code RegistrationHandler.exceptionCaught} wraps
     * validation failures such as a rejected password in a cause whose message
     * repeats the value, and that must never reach the browser or a log. Where a
     * cause is consulted at all (below), only its type is inspected, never its
     * message.
     */
    private static String mapFailureReason(Throwable e) {
        if (e instanceof ServerRefusedException) {
            // irc-client 1.2.1's RegistrationHandler throws this, unwrapped, when
            // the server sends ERROR during registration or answers a TLS attempt
            // in plain text. Its message and getReply() carry the server's own
            // words, which task 02 may choose to surface; here only the fact of a
            // refusal is reported, never that text.
            return SERVER_REFUSED_REASON;
        }
        if (e instanceof IllegalArgumentException) {
            // irc-client 1.2.1's builders (ClientConfigurationBuilder.password/sasl,
            // IRCBotBuilder.password) validate eagerly and throw this directly,
            // before a socket is ever opened. The message never contains the
            // rejected value (only its length or the index of the problem), but is
            // still never logged or sent, on the same defence-in-depth footing as
            // every other reason here.
            return CREDENTIAL_NOT_VALID_REASON;
        }
        String message = e.getMessage();
        if (message != null) {
            if (message.startsWith("Registration rejected by the server")) {
                return "The server rejected registration. Check the password and nick, "
                        + "then try again.";
            }
            if (message.startsWith("SASL authentication failed")
                    || message.startsWith("SASL was configured but the server refused")) {
                return "SASL authentication failed. Check the SASL username and password.";
            }
            if ("Registration failed".equals(message)) {
                return mapRegistrationFailedReason(e.getCause());
            }
            if (message.startsWith("Server did not complete registration within")) {
                return "The server did not finish registration in time. Try again.";
            }
            if ("Connection closed before registration completed".equals(message)) {
                return "The connection closed before registration finished. Try again.";
            }
        }
        if (e instanceof java.io.IOException || chainHas(e.getCause(), java.io.IOException.class)) {
            return UNREACHABLE_REASON;
        }
        return "Could not connect. Check the server settings and try again.";
    }

    /**
     * {@code RegistrationHandler.exceptionCaught} wraps everything from a channel
     * error to the library's own credential validation in one message, "Registration
     * failed", so the cause's message can never be used to tell those apart without
     * risking a credential. Its type can: an {@link IllegalArgumentException}
     * anywhere in the chain is irc-client rejecting a value we sent (a bad password,
     * a bad SASL username); an {@link java.io.IOException} is a socket or TLS problem
     * reaching the server. Anything else falls back to the credential message, which
     * was always this method's answer before the two were told apart.
     */
    private static String mapRegistrationFailedReason(Throwable cause) {
        if (chainHas(cause, IllegalArgumentException.class)) {
            return CREDENTIAL_NOT_VALID_REASON;
        }
        if (chainHas(cause, javax.net.ssl.SSLHandshakeException.class)) {
            return "Could not establish a secure connection to the server.";
        }
        if (chainHas(cause, java.io.IOException.class)) {
            return UNREACHABLE_REASON;
        }
        return CREDENTIAL_NOT_VALID_REASON;
    }

    /** Whether {@code type} appears anywhere in {@code start}'s cause chain. */
    private static boolean chainHas(Throwable start, Class<? extends Throwable> type) {
        Throwable current = start;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    /**
     * Turns bot events into things the browser can render.
     */
    private final class Forwarder extends BotListener {

        @Override
        public void onReady(IRCBot bot) {
            // Registration completed, so nothing captured from here on belongs to a
            // failure reason: a NOTICE the server sends afterwards is unrelated.
            capturingServerText = false;
            sink.accept(OutboundEvent.status("ready", "registered as " + bot.getNick(),
                    bot.getNick()));
            publishChannels();
        }

        @Override
        public void onMessage(MessageContext context) {
            sink.accept(OutboundEvent.message(context.getTarget(), context.getSender(),
                    context.getText(), false));
        }

        @Override
        public void onNotice(MessageContext context) {
            sink.accept(OutboundEvent.notice(context.getTarget(), context.getSender(),
                    context.getText()));
        }

        @Override
        public void onJoin(IRCBot bot, String channel, String nick) {
            sink.accept(OutboundEvent.presence("join", channel, nick, null));
            publishChannels();
            publishNames(channel);
        }

        @Override
        public void onPart(IRCBot bot, String channel, String nick) {
            sink.accept(OutboundEvent.presence("part", channel, nick, null));
            publishChannels();
            publishNames(channel);
        }

        @Override
        public void onQuit(IRCBot bot, String nick, String reason) {
            sink.accept(OutboundEvent.presence("quit", null, nick, reason));
        }

        @Override
        public void onOther(IRCBot bot, IRCMessage message) {
            // 366 ends a NAMES reply, which is when the tracker's view of a channel
            // is complete and worth showing.
            if ("366".equals(message.getCommand()) && message.getParam(1) != null) {
                publishNames(message.getParam(1));
            }
            if (message.isNumeric() && !QUIET.contains(message.getCommand())) {
                sink.accept(OutboundEvent.server(message.getCommand(), readable(message)));
            }
        }
    }

    /**
     * Every raw inbound line, for the traffic pane. Never outbound: irc-client's
     * {@code eventListener} is fed only by {@code AbstractInboundEventHandler},
     * which is placed in the pipeline's read side, so our own lines such as PASS
     * are never seen here.
     */
    private final class RawForwarder implements EventHandler<String> {
        @Override
        public void publishEvent(Event<String> event) {
            String line = event.getPayload();
            sink.accept(OutboundEvent.raw("in", line));
            captureServerText(line);
        }

        /**
         * Remembers the trailing text of a NOTICE or ERROR seen while a connect is
         * in progress (see {@link #capturingServerText}), so a failed registration
         * can quote it. Nothing here is logged or sent anywhere itself; it is only
         * read back by {@link #withServerText}.
         */
        private void captureServerText(String line) {
            if (!capturingServerText) {
                return;
            }
            IRCMessage message;
            try {
                message = IRCMessageParser.parse(line);
            } catch (IRCParseException e) {
                // Deliberately silent: an unparseable inbound line is simply not a
                // reason we can quote, not a fault of ours to report anywhere. The
                // raw pane above already shows the line itself, and irc-client's own
                // handlers separately decide whether it is otherwise fatal.
                return;
            }
            String trailing = message.getTrailing();
            if (trailing == null) {
                return;
            }
            String command = message.getCommand();
            if ("NOTICE".equals(command)) {
                capturedNotice = trailing;
            } else if ("ERROR".equals(command)) {
                capturedError = trailing;
            }
        }
    }

    private void publishChannels() {
        IRCBot current = bot;
        if (current != null) {
            sink.accept(OutboundEvent.channels(current.getChannels()));
        }
    }

    private void publishNames(String channel) {
        IRCBot current = bot;
        if (current == null || channel == null) {
            return;
        }
        ChannelState state = current.getChannelState(channel);
        if (state == null) {
            return;
        }
        List<OutboundEvent.Member> members = state.getUsers().stream()
                .map(IrcSession::toMember)
                .toList();
        sink.accept(OutboundEvent.names(state.getName(), state.getTopic(), members));
    }

    /**
     * A numeric's parameters minus the first, which is always our own nick and adds
     * nothing to a line someone is reading about themselves.
     */
    private static String readable(IRCMessage message) {
        List<String> params = message.getParams();
        if (params.isEmpty()) {
            return message.getCommand();
        }
        return String.join(" ", params.subList(1, params.size()));
    }

    private static OutboundEvent.Member toMember(ChannelUser user) {
        var highest = user.getHighestStatus();
        return new OutboundEvent.Member(
                user.getNick(),
                highest == null ? null : String.valueOf(highest.getPrefix()),
                user.isOperator());
    }
}
