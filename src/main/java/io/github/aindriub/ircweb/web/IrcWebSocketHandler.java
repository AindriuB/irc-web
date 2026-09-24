package io.github.aindriub.ircweb.web;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.github.aindriub.ircweb.irc.ClientCommand;
import io.github.aindriub.ircweb.irc.ConnectRequest;
import io.github.aindriub.ircweb.irc.DirectoryService;
import io.github.aindriub.ircweb.irc.IrcServer;
import io.github.aindriub.ircweb.irc.IrcSessionRegistry;
import io.github.aindriub.ircweb.irc.LiveSession;
import io.github.aindriub.ircweb.irc.OutboundEvent;
import io.github.aindriub.ircweb.irc.ServerProfile;

/**
 * Attaches a browser socket to the IRC session its account already owns.
 *
 * <p>The socket no longer owns the connection. Opening one attaches to whatever is
 * running for that account and replays what was missed; closing one only detaches.
 * Leaving a network is something asked for, not something that happens because a
 * laptop lid was shut.
 *
 * <p>Two threading rules hold this together. A {@link WebSocketSession} is not safe
 * for concurrent use, and IRC events arrive on whichever Netty thread happens to be
 * reading, so every send is serialised on a per-session lock. And nothing that talks
 * to IRC runs on the thread delivering the browser message: connecting blocks until
 * registration completes, and a send can block on flood protection, neither of which
 * belongs on a container thread.
 */
@Component
public class IrcWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(IrcWebSocketHandler.class);

    private final DirectoryService directory;
    private final IrcSessionRegistry registry;
    private final ObjectMapper json;

    private final Map<String, Bridge> bridges = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "irc-session");
        thread.setDaemon(true);
        return thread;
    });

    public IrcWebSocketHandler(DirectoryService directory, IrcSessionRegistry registry,
            ObjectMapper json) {
        this.directory = directory;
        this.registry = registry;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        Bridge bridge = new Bridge(socket);
        bridges.put(socket.getId(), bridge);

        // Attaching replays the backlog, which is IO onto a container thread, so it
        // goes to a worker like everything else that might take a moment.
        workers.execute(bridge::attachOrIdle);
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        Bridge bridge = bridges.get(socket.getId());
        if (bridge == null) {
            return;
        }
        ClientCommand command;
        try {
            command = json.readValue(message.getPayload(), ClientCommand.class);
        } catch (JacksonException e) {
            // Jackson 3 made these unchecked. Still caught: a browser sending
            // something unparseable should be told so, not silently ignored.
            bridge.send(OutboundEvent.error("could not parse that: " + e.getMessage()));
            return;
        }
        workers.execute(() -> bridge.handle(command));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        Bridge bridge = bridges.remove(socket.getId());
        if (bridge != null) {
            // Detach only. The connection is the account's, not the tab's.
            bridge.detach();
        }
    }

    /**
     * One browser, one IRC session, and the lock that keeps sends in order.
     */
    private final class Bridge {

        private final WebSocketSession socket;
        private final String username;
        private final Object sendLock = new Object();

        /** Held as a field so detaching can name the same watcher that attached. */
        private final java.util.function.Consumer<OutboundEvent> watcher = this::send;

        private volatile LiveSession live;

        private Bridge(WebSocketSession socket) {
            this.socket = socket;
            this.username = socket.getPrincipal() == null
                    ? null : socket.getPrincipal().getName();
        }

        /** Picks up the account's session if it has one, or says there is nothing to show. */
        private void attachOrIdle() {
            if (username == null) {
                // The socket is behind the same authentication as everything else,
                // so this should not happen. If it ever does, refuse rather than
                // guess whose session to hand over.
                send(OutboundEvent.error("not signed in"));
                return;
            }
            registry.find(username).ifPresentOrElse(
                session -> {
                    live = session;
                    session.attach(watcher);
                },
                () -> send(OutboundEvent.status("idle", "pick a server")));
        }

        private void detach() {
            LiveSession session = live;
            if (session != null) {
                session.detach(watcher);
            }
        }

        private void handle(ClientCommand command) {
            try {
                switch (command.type()) {
                case "connect" -> connect(command);
                case "message" -> session().say(command.target(), command.text());
                case "join" -> session().join(command.channel());
                case "part" -> session().part(command.channel());
                case "raw" -> {
                    send(OutboundEvent.raw("out", command.line()));
                    session().raw(command.line());
                }
                case "disconnect" -> {
                    registry.end(username);
                    live = null;
                    send(OutboundEvent.status("disconnected", "left the network"));
                }
                default -> send(OutboundEvent.error("unknown command: " + command.type()));
                }
            } catch (RuntimeException e) {
                LOGGER.debug("Command {} failed", command.type(), e);
                send(OutboundEvent.error(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }

        private io.github.aindriub.ircweb.irc.IrcSession session() {
            LiveSession session = live;
            if (session == null) {
                throw new IllegalStateException("not connected");
            }
            return session.irc();
        }

        private void connect(ClientCommand command) {
            IrcServer server = directory.byId(command.serverId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "no such server: " + command.serverId()));

            LiveSession session = registry.open(username, command.serverId());
            live = session;
            session.attach(watcher);
            try {
                session.irc().connect(server, withStoredDetails(command));
            } catch (RuntimeException | Error e) {
                // A session that never registered is not worth keeping. Left in the
                // registry it would refuse every later attempt as "already
                // connected" to a connection that does not exist. An Error from the
                // library (an OutOfMemoryError, a broken TLS classpath) leaves the
                // same problem and must not be allowed to skip this cleanup.
                registry.end(username);
                live = null;
                throw e;
            }
        }

        /**
         * The browser sends what was typed; anything it left blank comes from the
         * stored profile. Passwords in particular are never sent to the browser, so
         * they can only come from here.
         */
        private ConnectRequest withStoredDetails(ClientCommand command) {
            ConnectRequest typed = command.toConnectRequest();
            ServerProfile profile = directory.profile(command.serverId());
            DirectoryService.Credentials stored = directory.credentials(command.serverId());

            return new ConnectRequest(
                    typed.serverId(),
                    firstSet(typed.nick(), profile.nick()),
                    firstSet(typed.password(), stored.password()),
                    firstSet(typed.saslUsername(), stored.saslUsername()),
                    firstSet(typed.saslPassword(), stored.saslPassword()),
                    typed.channels() == null || typed.channels().isEmpty()
                            ? profile.channels() : typed.channels());
        }

        private String firstSet(String typed, String stored) {
            return typed != null && !typed.isBlank() ? typed : stored;
        }

        private void send(OutboundEvent event) {
            if (!socket.isOpen()) {
                return;
            }
            try {
                String payload = json.writeValueAsString(event);
                // Serialised: Netty threads and container threads both end up here,
                // and concurrent sends on one session corrupt the frame stream.
                synchronized (sendLock) {
                    if (socket.isOpen()) {
                        socket.sendMessage(new TextMessage(payload));
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("Could not deliver {} to {}", event.type(), socket.getId(), e);
            }
        }

    }
}
