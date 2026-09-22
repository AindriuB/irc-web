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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.WebSocketHttpHeaders;

import org.springframework.security.crypto.password.PasswordEncoder;

import io.github.aindriub.ircweb.irc.IrcSessionRegistry;
import io.github.aindriub.ircweb.store.AppUserEntity;
import io.github.aindriub.ircweb.store.AppUserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    /**
     * A second account, because a session now belongs to an account rather than to
     * a socket: two browsers signed in as the same person share one connection, so
     * a test that needs two connections needs two people.
     */
    private static final String OTHER_USER = "tester2";
    private static final String OTHER_PASSWORD = "other-password";

    @LocalServerPort
    private int port;

    @Autowired
    private IrcSessionRegistry registry;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder encoder;

    private WebSocketSession socket;

    @BeforeAll
    static void requireLocalServer() {
        assumeTrue(reachable(),
                "local IRC server not running: docker compose -f docker/compose.yaml up -d");
    }

    @BeforeEach
    void createTheSecondAccount() {
        if (!users.existsById(OTHER_USER)) {
            users.save(new AppUserEntity(OTHER_USER, encoder.encode(OTHER_PASSWORD)));
        }
    }

    @AfterEach
    void closeSocketAndSession() throws IOException {
        if (socket != null && socket.isOpen()) {
            socket.close(CloseStatus.NORMAL);
        }
        // Closing the socket no longer ends the IRC session, which is the point of
        // the feature and a leak between tests if the test does not say so.
        registry.end("tester");
        registry.end(OTHER_USER);
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
            other = connectAs(OTHER_USER, OTHER_PASSWORD, speakerEvents);
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

    @Test
    @DisplayName("the IRC connection outlives the browser socket")
    void theSessionSurvivesTheSocket() throws Exception {
        Collector first = open();
        send(connectCommand("webtest6", "#irc-web-bnc"));
        first.await(e -> "status".equals(text(e, "type")) && "ready".equals(text(e, "state")));

        // The browser goes away, as a closed tab or a shut laptop would.
        socket.close(CloseStatus.NORMAL);
        assertTrue(registry.find("tester").isPresent(),
                "closing the socket must not take the IRC connection with it");

        // Coming back attaches to what was already running rather than starting
        // something new: same nick, same network, no second registration.
        Collector second = new Collector();
        socket = connect(second);

        JsonNode attached = second.await(e -> "attached".equals(text(e, "type")));
        assertTrue("local".equals(text(attached, "state")),
                "expected to be told which network, got " + text(attached, "state"));
        assertTrue("webtest6".equals(text(attached, "nick")),
                "expected the nick it is actually using, got " + text(attached, "nick"));

        second.await(e -> "replay".equals(text(e, "type")) && "end".equals(text(e, "state")));
    }

    @Test
    @DisplayName("what was said while nobody was watching is replayed")
    void replaysWhatWasMissed() throws Exception {
        Collector first = open();
        send(connectCommand("webtest7", "#irc-web-backlog"));
        first.await(e -> "status".equals(text(e, "type")) && "ready".equals(text(e, "state")));
        first.await(e -> "names".equals(text(e, "type"))
                && "#irc-web-backlog".equalsIgnoreCase(text(e, "channel")));

        socket.close(CloseStatus.NORMAL);

        WebSocketSession other = null;
        try {
            Collector otherEvents = new Collector();
            other = connectAs(OTHER_USER, OTHER_PASSWORD, otherEvents);
            other.sendMessage(new TextMessage(connectCommand("webtest8", "#irc-web-backlog")));
            otherEvents.await(e -> "status".equals(text(e, "type"))
                    && "ready".equals(text(e, "state")));

            other.sendMessage(new TextMessage(JSON.writeValueAsString(java.util.Map.of(
                    "type", "message",
                    "target", "#irc-web-backlog",
                    "text", "said while you were away"))));

            // One connection is ordered, so a reply to something sent afterwards
            // proves the server already dealt with the message. Without this the
            // test would race the reattach and pass for the wrong reason.
            other.sendMessage(new TextMessage(JSON.writeValueAsString(
                    java.util.Map.of("type", "raw", "line", "TIME"))));
            otherEvents.await(e -> "server".equals(text(e, "type"))
                    && "391".equals(text(e, "state")));
        } finally {
            if (other != null && other.isOpen()) {
                other.close(CloseStatus.NORMAL);
            }
        }

        Collector back = new Collector();
        socket = connect(back);

        JsonNode missed = back.await(e -> "message".equals(text(e, "type"))
                && "said while you were away".equals(text(e, "text")));
        assertTrue("webtest8".equals(text(missed, "sender")),
                "expected webtest8, got " + text(missed, "sender"));
    }

    @Test
    @DisplayName("a second connection for the same account is refused, and says why")
    void refusesASecondConnection() throws Exception {
        Collector events = open();
        send(connectCommand("webtest9", null));
        events.await(e -> "status".equals(text(e, "type")) && "ready".equals(text(e, "state")));

        send(connectCommand("webtest9b", null));

        JsonNode error = events.await(e -> "error".equals(text(e, "type")));
        assertTrue(text(error, "detail").contains("already connected"),
                "expected to be told what is already running, got " + text(error, "detail"));
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
        return connectAs("tester", "test-password", collector);
    }

    private WebSocketSession connectAs(String user, String password, Collector collector)
            throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Basic " + java.util.Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes()));
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
