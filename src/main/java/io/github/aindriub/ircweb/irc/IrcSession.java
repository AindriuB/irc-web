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
import io.github.aindriub.irc.client.message.IRCMessage;
import io.github.aindriub.irc.client.state.ChannelState;
import io.github.aindriub.irc.client.state.ChannelUser;

/**
 * One browser's IRC connection.
 *
 * <p>Everything the library emits arrives on a Netty event loop thread and is handed
 * straight to {@code sink}. The sink is responsible for getting it to the browser
 * safely; nothing here blocks, because blocking an event loop thread stops the
 * connection it belongs to.
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

    public IrcSession(Consumer<OutboundEvent> sink) {
        this.sink = sink;
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

            builder.client()
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

            if (hasText(request.saslPassword())) {
                builder.client().sasl(
                        hasText(request.saslUsername()) ? request.saslUsername() : request.nick(),
                        request.saslPassword());
            }

            bot = builder.build();
            bot.start();
        } catch (Exception e) {
            // Netty can hand a plain checked IOException straight back through
            // start(), unwrapped, so this has to catch Exception, not just
            // RuntimeException, or a bare connection refusal would skip the stop()
            // below and leave the bot's own reconnect loop running forever.
            running.set(false);
            stopWhateverWasBuilt();
            String reason = mapFailureReason(e);
            // Only the exception's own class and the safe, mapped reason are
            // logged. Never e.getMessage() or e itself: irc-client 1.1.0 puts the
            // rejected value in a cause's message, and logging the throwable would
            // print that cause chain.
            LOGGER.info("Connection to {} failed: {}", server.id(), e.getClass().getSimpleName());
            sink.accept(OutboundEvent.status("disconnected", reason));
            throw new IllegalStateException(reason);
        }
    }

    /** Stops the bot this connect attempt built, if it got that far. Never leaves it running. */
    private void stopWhateverWasBuilt() {
        IRCBot current = bot;
        bot = null;
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException e) {
                LOGGER.debug("Stop after a failed connect was not clean", e);
            }
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

    /** Empty when every effective credential is usable, else a message safe to show. */
    private static String firstCredentialProblem(ConnectRequest request) {
        return CredentialRules.checkPassword(request.password())
                .or(() -> CredentialRules.checkSaslUsername(request.saslUsername()))
                .or(() -> CredentialRules.checkSaslPassword(request.saslPassword()))
                .orElse(null);
    }

    /**
     * A fixed, credential-free reason for each failure irc-client 1.1.0 can report on
     * a first connect. Matched only on the outermost exception's own message, never on
     * a cause: {@code RegistrationHandler.exceptionCaught} wraps validation failures
     * such as a rejected password in a cause whose message repeats the value, and that
     * must never reach the browser or a log.
     */
    private static String mapFailureReason(Exception e) {
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
                return "A saved credential is not valid for IRC. Edit the profile and "
                        + "save it again.";
            }
            if (message.startsWith("Server did not complete registration within")) {
                return "The server did not finish registration in time. Try again.";
            }
            if ("Connection closed before registration completed".equals(message)) {
                return "The connection closed before registration finished. Try again.";
            }
        }
        if (e instanceof java.io.IOException || e.getCause() instanceof java.io.IOException) {
            return "Could not reach the server. Check the host and port, then try again.";
        }
        return "Could not connect. Check the server settings and try again.";
    }

    /**
     * Turns bot events into things the browser can render.
     */
    private final class Forwarder extends BotListener {

        @Override
        public void onReady(IRCBot bot) {
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
     * Every raw inbound line, for the traffic pane.
     */
    private final class RawForwarder implements EventHandler<String> {
        @Override
        public void publishEvent(Event<String> event) {
            sink.accept(OutboundEvent.raw("in", event.getPayload()));
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
