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

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.aindriub.ircweb.irc.ClientCommand;
import io.github.aindriub.ircweb.irc.IrcServer;
import io.github.aindriub.ircweb.irc.IrcSession;
import io.github.aindriub.ircweb.irc.OutboundEvent;
import io.github.aindriub.ircweb.irc.ServerDirectory;

/**
 * Bridges one browser socket to one IRC connection.
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

    private final ServerDirectory directory;
    private final ObjectMapper json;

    private final Map<String, Bridge> bridges = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "irc-session");
        thread.setDaemon(true);
        return thread;
    });

    public IrcWebSocketHandler(ServerDirectory directory, ObjectMapper json) {
        this.directory = directory;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        Bridge bridge = new Bridge(socket);
        bridges.put(socket.getId(), bridge);
        bridge.send(OutboundEvent.status("idle", "pick a server"));
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
        } catch (IOException e) {
            bridge.send(OutboundEvent.error("could not parse that: " + e.getMessage()));
            return;
        }
        workers.execute(() -> bridge.handle(command));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        Bridge bridge = bridges.remove(socket.getId());
        if (bridge != null) {
            // The browser going away must take the IRC connection with it, or a
            // reload leaves an orphan sitting in channels under the same nick.
            workers.execute(bridge::close);
        }
    }

    /**
     * One browser, one IRC session, and the lock that keeps sends in order.
     */
    private final class Bridge {

        private final WebSocketSession socket;
        private final IrcSession irc;
        private final Object sendLock = new Object();

        private Bridge(WebSocketSession socket) {
            this.socket = socket;
            this.irc = new IrcSession(this::send);
        }

        private void handle(ClientCommand command) {
            try {
                switch (command.type()) {
                case "connect" -> connect(command);
                case "message" -> irc.say(command.target(), command.text());
                case "join" -> irc.join(command.channel());
                case "part" -> irc.part(command.channel());
                case "raw" -> {
                    send(OutboundEvent.raw("out", command.line()));
                    irc.raw(command.line());
                }
                case "disconnect" -> irc.disconnect();
                default -> send(OutboundEvent.error("unknown command: " + command.type()));
                }
            } catch (RuntimeException e) {
                LOGGER.debug("Command {} failed", command.type(), e);
                send(OutboundEvent.error(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }

        private void connect(ClientCommand command) {
            IrcServer server = directory.byId(command.serverId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "no such server: " + command.serverId()));
            irc.connect(server, command.toConnectRequest());
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

        private void close() {
            try {
                irc.disconnect();
            } catch (RuntimeException e) {
                LOGGER.debug("Cleanup after socket close was not clean", e);
            }
        }
    }
}
