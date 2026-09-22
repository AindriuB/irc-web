package io.github.aindriub.ircweb;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.WebSocketHttpHeaders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Drives the whole stack against the local IRC server: browser socket, Spring
 * handler, irc-client, ergo and back.
 *
 * <p>Needs {@code docker compose -f docker/compose.yaml up -d}. Skips rather than
 * fails when that is not running, so the suite stays useful on a machine that has
 * not started it - but note what that means: a skipped test has proved nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // A known password, because the test has to log in like a browser
            // does: the websocket handshake is an authenticated request.
            "irc-web.admin-username=tester",
            "irc-web.admin-password=test-password",
            // Its own database and key, so a run never inherits or disturbs the
            // data directory of a copy someone is using.
            "irc-web.data-dir=${java.io.tmpdir}/irc-web-it",
            "spring.datasource.url=jdbc:h2:mem:irc-web-it;DB_CLOSE_DELAY=-1"
        })
class LocalIrcServerIT {

    private static final String IRC_HOST = "127.0.0.1";
    private static final int IRC_PORT = 6667;
    private static final long TIMEOUT_SECONDS = 20;

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    private WebSocketSession socket;

    @BeforeAll
    static void requireLocalServer() {
        assumeTrue(reachable(),
                "local IRC server not running: docker compose -f docker/compose.yaml up -d");
    }

    @AfterEach
    void closeSocket() throws IOException {
        if (socket != null && socket.isOpen()) {
            socket.close(CloseStatus.NORMAL);
        }
    }

    @Test
    @DisplayName("connects, registers and reports ready")
    void registers() throws Exception {
        Collector events = open();

        send(connectCommand("webtest1", "#irc-web-test"));

        JsonNode ready = events.await(e -> "status".equals(text(e, "type"))
                && "ready".equals(text(e, "state")));
        assertTrue(text(ready, "detail").contains("webtest1"),
                "expected the nick in: " + text(ready, "detail"));
    }

    @Test
    @DisplayName("joins a channel and reports its members")
    void joinsAndListsMembers() throws Exception {
        Collector events = open();
        send(connectCommand("webtest2", "#irc-web-test"));
        events.await(e -> "status".equals(text(e, "type"))
                && "ready".equals(text(e, "state")));

        JsonNode names = events.await(e -> "names".equals(text(e, "type"))
                && "#irc-web-test".equalsIgnoreCase(text(e, "channel")));

        assertTrue(names.get("members").size() >= 1,
                "the channel should contain at least ourselves");
    }

    @Test
    @DisplayName("a message sent by one client reaches another")
    void messagesTravelBetweenClients() throws Exception {
        Collector listener = open();
        send(connectCommand("webtest3", "#irc-web-room"));
        listener.await(e -> "status".equals(text(e, "type"))
                && "ready".equals(text(e, "state")));

        // A second browser, so the message makes a real round trip through ergo
        // rather than being echoed locally.
        WebSocketSession other = null;
        try {
            Collector speakerEvents = new Collector();
            other = connect(speakerEvents);
            other.sendMessage(new TextMessage(connectCommand("webtest4", "#irc-web-room")));
            speakerEvents.await(e -> "status".equals(text(e, "type"))
                    && "ready".equals(text(e, "state")));

            other.sendMessage(new TextMessage(JSON.writeValueAsString(java.util.Map.of(
                    "type", "message",
                    "target", "#irc-web-room",
                    "text", "hello from the other side"))));

            JsonNode received = listener.await(e -> "message".equals(text(e, "type"))
                    && "hello from the other side".equals(text(e, "text")));
            assertTrue("webtest4".equals(text(received, "sender")),
                    "expected webtest4, got " + text(received, "sender"));
        } finally {
            if (other != null && other.isOpen()) {
                other.close(CloseStatus.NORMAL);
            }
        }
    }

    @Test
    @DisplayName("raw wire traffic reaches the browser")
    void forwardsRawLines() throws Exception {
        Collector events = open();
        send(connectCommand("webtest5", null));

        // 001 is the first thing any server says once it accepts registration, so
        // seeing it proves the raw feed is wired to the real connection.
        JsonNode raw = events.await(e -> "raw".equals(text(e, "type"))
                && text(e, "line") != null && text(e, "line").contains(" 001 "));
        assertTrue("in".equals(text(raw, "direction")));
    }

    // ------------------------------------------------------------------ helpers

    private Collector open() throws Exception {
        Collector collector = new Collector();
        socket = connect(collector);
        return collector;
    }

    /**
     * The handshake is an ordinary authenticated request, so it carries basic
     * credentials. A browser uses its session cookie; either satisfies the filter
     * chain, and basic is far less to set up from a test.
     */
    private WebSocketSession connect(Collector collector) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Basic " + java.util.Base64.getEncoder()
                .encodeToString("tester:test-password".getBytes()));
        return new StandardWebSocketClient()
                .execute(collector, headers, java.net.URI.create(
                        "ws://localhost:" + port + "/ws/irc"))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void send(String payload) throws IOException {
        socket.sendMessage(new TextMessage(payload));
    }

    private static String connectCommand(String nick, String channel) throws IOException {
        var command = new java.util.HashMap<String, Object>();
        command.put("type", "connect");
        command.put("serverId", "local");
        command.put("nick", nick);
        if (channel != null) {
            command.put("channels", List.of(channel));
        }
        return JSON.writeValueAsString(command);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean reachable() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(IRC_HOST, IRC_PORT), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Collects everything the server sends, and lets a test wait for the one it
     * cares about. Events arrive in an order the test does not control, so matching
     * on a predicate beats asserting on position.
     */
    private static final class Collector extends TextWebSocketHandler {

        private final List<JsonNode> events = new ArrayList<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message)
                throws IOException {
            JsonNode node = JSON.readTree(message.getPayload());
            synchronized (events) {
                events.add(node);
                events.notifyAll();
            }
        }

        JsonNode await(java.util.function.Predicate<JsonNode> matcher)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
            synchronized (events) {
                int seen = 0;
                while (true) {
                    for (; seen < events.size(); seen++) {
                        if (matcher.test(events.get(seen))) {
                            return events.get(seen);
                        }
                    }
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        throw new AssertionError("timed out. Saw:\n  "
                                + String.join("\n  ", events.stream()
                                        .map(JsonNode::toString).toList()));
                    }
                    events.wait(remaining);
                }
            }
        }
    }
}
