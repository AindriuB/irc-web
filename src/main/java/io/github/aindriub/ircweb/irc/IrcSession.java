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
                    .realname("irc-web exercising irc-client")
                    // Raw lines go to the browser too. For a test harness the wire
                    // traffic is half the point: it is how you tell a parsing bug
                    // from an application bug.
                    .eventListener(new RawForwarder());

            if (hasText(request.saslPassword())) {
                builder.client().sasl(
                        hasText(request.saslUsername()) ? request.saslUsername() : request.nick(),
                        request.saslPassword());
            }

            bot = builder.build();
            bot.start();
        } catch (RuntimeException e) {
            running.set(false);
            bot = null;
            LOGGER.info("Connection to {} failed: {}", server.id(), e.toString());
            sink.accept(OutboundEvent.status("disconnected", describe(e)));
            throw e;
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

    private static String describe(RuntimeException e) {
        // The cause carries the useful half: "Connection refused", "certificate
        // problem". The wrapper alone reads as a shrug.
        Throwable cause = e.getCause();
        return cause == null ? e.getMessage() : e.getMessage() + " (" + cause.getMessage() + ")";
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
