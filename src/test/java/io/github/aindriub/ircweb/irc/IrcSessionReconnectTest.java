package io.github.aindriub.ircweb.irc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * Plain JUnit, no Spring and no ergo, same fake-server style as {@link IrcSessionTest}:
 * proves task 03's connection-event forwarding (reconnecting, gave up) rather than
 * anything task 01/02 already covers.
 */
class IrcSessionReconnectTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ServerSocket serverSocket;

    @AfterEach
    void closeServer() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    @Test
    @DisplayName("a drop is reported as reconnecting, a refused attempt as the next reconnecting, "
            + "then a successful reconnect goes back to ready")
    void reportsReconnectingThenReadyAfterADropAndARefusedAttempt() throws Exception {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        List<OutboundEvent> events = new ArrayList<>();
        IrcSession session = new IrcSession(synchronizedSink(events));
        // Short enough not to wait out production's real backoff.
        session.setReconnectBackoffForTest(150, 10_000);
        IrcServer server = fakeServer(port);
        ConnectRequest request = new ConnectRequest("local", "webtest", null, null, null,
                List.of());

        CountDownLatch secondRegistrationAccepted = new CountDownLatch(1);
        Thread fakeIrcServer = new Thread(() -> {
            try (Socket first = serverSocket.accept()) {
                registerThenDrop(first, "webtest");
            } catch (IOException e) {
                return;
            }
            try {
                // Refuses the first reconnect attempt: nothing is listening on the
                // port while it is closed.
                serverSocket.close();
                // Reopens only once the client itself says attempt 2 is under way —
                // proof attempt 1 already failed against the closed port, rather
                // than a guess at how long that takes.
                awaitReconnectingAttempt(events, 2, 10_000);
            } catch (IOException e) {
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try (ServerSocket reopened = new ServerSocket(port)) {
                try (Socket second = reopened.accept()) {
                    acceptRegistrationSignaling(second, "webtest", secondRegistrationAccepted);
                }
            } catch (IOException e) {
                // The assertion below times out and reports the failure.
            }
        }, "fake-irc-server-reconnect-cycle");
        fakeIrcServer.setDaemon(true);
        fakeIrcServer.start();

        assertDoesNotThrow(() -> session.connect(server, request));

        assertTrue(secondRegistrationAccepted.await(10, TimeUnit.SECONDS),
                "the fake server never saw the reconnect's second registration");

        List<OutboundEvent> reconnecting = awaitStatusEvents(events, "reconnecting", 3);

        assertEquals("lost the connection to Fake", reconnecting.get(0).detail());
        assertEquals("reconnecting to Fake (attempt 1, in 1s)", reconnecting.get(1).detail());
        assertEquals("reconnecting to Fake (attempt 2, in 1s)", reconnecting.get(2).detail());

        long readyCount = awaitStatusEvents(events, "ready", 2).size();
        assertEquals(2, readyCount, "expected a ready status for the first connect and again "
                + "after the reconnect");

        for (OutboundEvent event : copyOf(events)) {
            assertFalse(JSON.writeValueAsString(event).toLowerCase(Locale.ROOT)
                    .contains("password"), "an event mentioned a password");
        }

        session.disconnect();
    }

    @Test
    @DisplayName("a channel configured at connect is rejoined, and shown again in the channels "
            + "event, after a reconnect")
    void channelsConfiguredAtConnectArePublishedAgainAfterAReconnect() throws Exception {
        serverSocket = new ServerSocket(0);
        List<OutboundEvent> events = new ArrayList<>();
        IrcSession session = new IrcSession(synchronizedSink(events));
        session.setReconnectBackoffForTest(50, 100);
        IrcServer server = fakeServer(serverSocket.getLocalPort());
        ConnectRequest request = new ConnectRequest("local", "webtest", null, null, null,
                List.of("#chan"));

        CountDownLatch secondRegistrationAccepted = new CountDownLatch(1);
        Thread fakeIrcServer = new Thread(() -> {
            try (Socket first = serverSocket.accept()) {
                registerJoinThenDrop(first, "webtest");
            } catch (IOException e) {
                return;
            }
            try (Socket second = serverSocket.accept()) {
                acceptRegistrationJoinSignaling(second, "webtest", secondRegistrationAccepted);
            } catch (IOException e) {
                // The assertion below times out and reports the failure.
            }
        }, "fake-irc-server-channels-after-reconnect");
        fakeIrcServer.setDaemon(true);
        fakeIrcServer.start();

        assertDoesNotThrow(() -> session.connect(server, request));

        assertTrue(secondRegistrationAccepted.await(10, TimeUnit.SECONDS),
                "the fake server never saw the reconnect's second registration");
        awaitStatusEvents(events, "ready", 2);

        OutboundEvent channelsAfterSecondReady = awaitChannelsEventAfterLastReady(events);
        assertTrue(channelsAfterSecondReady.channels().contains("#chan"),
                "expected #chan in the channels event after the reconnect, got: "
                        + channelsAfterSecondReady.channels());

        session.disconnect();
    }

    @Test
    @DisplayName("giving up after a finite number of attempts reports it, stops the bot off the "
            + "connection-event thread, and leaves the session free to connect again")
    void reportsGivingUpAndLeavesTheSessionReconnectable() throws Exception {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        List<OutboundEvent> events = new ArrayList<>();
        IrcSession session = new IrcSession(synchronizedSink(events));
        // A cap of 2, with a short backoff: the server below never comes back, so
        // both attempts fail and a gave-up status must follow quickly.
        session.setReconnectBackoffForTest(50, 200, 2);
        IrcServer server = fakeServer(port);
        ConnectRequest request = new ConnectRequest("local", "webtest", null, null, null,
                List.of());

        Thread fakeIrcServer = new Thread(() -> {
            try (Socket first = serverSocket.accept()) {
                registerThenDrop(first, "webtest");
            } catch (IOException e) {
                return;
            }
            try {
                // Never comes back: every reconnect attempt finds the port closed.
                serverSocket.close();
            } catch (IOException ignored) {
                // Nothing more to do.
            }
        }, "fake-irc-server-gives-up");
        fakeIrcServer.setDaemon(true);
        fakeIrcServer.start();

        assertDoesNotThrow(() -> session.connect(server, request));

        long start = System.currentTimeMillis();
        OutboundEvent gaveUp = awaitStatus(events, "disconnected",
                d -> d != null && d.startsWith("gave up reconnecting to Fake after"), 5_000);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 5_000, "gave-up took too long: " + elapsed + "ms");
        assertEquals("gave up reconnecting to Fake after 2 attempts", gaveUp.detail());

        assertFalse(session.isRunning(), "the session must not still think it is running");

        // A new connect, on the same session, proves running was really reset and
        // nothing was left retrying in the background: the bug this task fixes.
        try (ServerSocket freshServer = new ServerSocket(0)) {
            IrcServer secondServer = fakeServer(freshServer.getLocalPort());
            Thread secondFakeServer = new Thread(() -> {
                try (Socket socket = freshServer.accept()) {
                    acceptRegistration(socket, "webtest");
                } catch (IOException e) {
                    // The assertion below times out and reports the failure.
                }
            }, "fake-irc-server-after-gave-up");
            secondFakeServer.setDaemon(true);
            secondFakeServer.start();

            assertDoesNotThrow(() -> session.connect(secondServer, request));
            session.disconnect();
        }
    }

    @Test
    @DisplayName("a failed first connect emits no reconnecting status after its final "
            + "disconnected")
    void aFailedFirstConnectEmitsNoReconnectingAfterward() throws Exception {
        serverSocket = new ServerSocket(0);
        List<OutboundEvent> events = new ArrayList<>();
        IrcSession session = new IrcSession(synchronizedSink(events));
        session.setReconnectBackoffForTest(50, 100);
        IrcServer server = fakeServer(serverSocket.getLocalPort());
        ConnectRequest request = new ConnectRequest("local", "webtest", null, null, null,
                List.of());

        Thread fakeIrcServer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                rejectRegistration(socket);
            } catch (IOException e) {
                // The assertion below times out and reports the failure.
            }
        }, "fake-irc-server-plain-reject");
        fakeIrcServer.setDaemon(true);
        fakeIrcServer.start();

        try {
            session.connect(server, request);
        } catch (IllegalStateException expected) {
            // The rejection itself; already covered by IrcSessionTest.
        }

        synchronized (events) {
            assertFalse(events.isEmpty());
            OutboundEvent last = events.get(events.size() - 1);
            assertEquals("status", last.type());
            assertEquals("disconnected", last.state());
        }

        // The observable marker: a second, real connect on the same session. If
        // the rejected attempt's bot were still alive and misbehaving, its events
        // would have had this whole round trip — a fresh socket, registration and
        // a ready status — to arrive before the check below runs.
        try (ServerSocket secondServerSocket = new ServerSocket(0)) {
            IrcServer secondServer = fakeServer(secondServerSocket.getLocalPort());
            Thread secondFakeServer = new Thread(() -> {
                try (Socket socket = secondServerSocket.accept()) {
                    acceptRegistration(socket, "webtest");
                } catch (IOException e) {
                    // The assertion below times out and reports the failure.
                }
            }, "fake-irc-server-after-reject");
            secondFakeServer.setDaemon(true);
            secondFakeServer.start();

            assertDoesNotThrow(() -> session.connect(secondServer, request));
            awaitStatusEvents(events, "ready", 1);
            session.disconnect();
        }

        synchronized (events) {
            assertFalse(events.stream().anyMatch(e -> "status".equals(e.type())
                            && "reconnecting".equals(e.state())),
                    "no reconnecting status should follow a failed first connect");
        }
    }

    @Test
    @DisplayName("a deliberate disconnect emits no reconnecting or gave-up status")
    void aDeliberateDisconnectEmitsNoReconnectingOrGaveUpStatus() throws Exception {
        serverSocket = new ServerSocket(0);
        List<OutboundEvent> events = new ArrayList<>();
        IrcSession session = new IrcSession(synchronizedSink(events));
        session.setReconnectBackoffForTest(50, 100);
        IrcServer server = fakeServer(serverSocket.getLocalPort());
        ConnectRequest request = new ConnectRequest("local", "webtest", null, null, null,
                List.of());

        Thread fakeIrcServer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                acceptRegistration(socket, "webtest");
            } catch (IOException e) {
                // The assertion below times out and reports the failure.
            }
        }, "fake-irc-server-deliberate-disconnect");
        fakeIrcServer.setDaemon(true);
        fakeIrcServer.start();

        assertDoesNotThrow(() -> session.connect(server, request));
        session.disconnect();

        // The observable marker: a second connect, on the same session, must reach
        // ready. A spurious reconnecting/gave-up event from an unclean shutdown of
        // the first bot would have had this whole round trip to surface first.
        try (ServerSocket secondServerSocket = new ServerSocket(0)) {
            IrcServer secondServer = fakeServer(secondServerSocket.getLocalPort());
            Thread secondFakeServer = new Thread(() -> {
                try (Socket socket = secondServerSocket.accept()) {
                    acceptRegistration(socket, "webtest");
                } catch (IOException e) {
                    // The assertion below times out and reports the failure.
                }
            }, "fake-irc-server-after-disconnect");
            secondFakeServer.setDaemon(true);
            secondFakeServer.start();

            assertDoesNotThrow(() -> session.connect(secondServer, request));
            awaitStatusEvents(events, "ready", 2);
            session.disconnect();
        }

        synchronized (events) {
            assertFalse(events.stream().anyMatch(e -> "status".equals(e.type())
                            && ("reconnecting".equals(e.state())
                                    || (e.detail() != null && e.detail().contains("gave up")))),
                    "a deliberate disconnect must never look like a drop or a gave-up");
        }
    }

    // --- helpers -----------------------------------------------------------

    private static List<OutboundEvent> awaitStatusEvents(List<OutboundEvent> events, String state,
            int atLeast) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<OutboundEvent> matches;
        do {
            synchronized (events) {
                matches = events.stream()
                        .filter(e -> "status".equals(e.type()) && state.equals(e.state()))
                        .toList();
            }
            if (matches.size() >= atLeast) {
                return matches;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("expected at least " + atLeast + " '" + state + "' status "
                + "events, saw " + matches.size());
    }

    private static OutboundEvent awaitStatus(List<OutboundEvent> events, String state,
            Predicate<String> detailMatches, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        do {
            synchronized (events) {
                for (OutboundEvent event : events) {
                    if ("status".equals(event.type()) && state.equals(event.state())
                            && detailMatches.test(event.detail())) {
                        return event;
                    }
                }
            }
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("no matching '" + state + "' status event arrived in time");
    }

    /**
     * Waits for the {@code reconnecting} status naming the given attempt number, the
     * condition {@link #reportsReconnectingThenReadyAfterADropAndARefusedAttempt} uses
     * instead of guessing how long a failed attempt takes.
     */
    private static void awaitReconnectingAttempt(List<OutboundEvent> events, int attempt,
            long timeoutMillis) throws InterruptedException {
        String marker = "attempt " + attempt + ",";
        long deadline = System.currentTimeMillis() + timeoutMillis;
        do {
            synchronized (events) {
                boolean seen = events.stream().anyMatch(e -> "status".equals(e.type())
                        && "reconnecting".equals(e.state())
                        && e.detail() != null && e.detail().contains(marker));
                if (seen) {
                    return;
                }
            }
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("reconnecting attempt " + attempt + " never arrived in time");
    }

    /**
     * The channels event after the last {@code ready} status, the same "after" as
     * {@link IrcSessionTest#onReadyIsSafeAfterAReconnectOnTheConnectionEventThread}.
     */
    private static OutboundEvent awaitChannelsEventAfterLastReady(List<OutboundEvent> events)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        do {
            synchronized (events) {
                int lastReadyIndex = -1;
                for (int i = 0; i < events.size(); i++) {
                    OutboundEvent event = events.get(i);
                    if ("status".equals(event.type()) && "ready".equals(event.state())) {
                        lastReadyIndex = i;
                    }
                }
                for (int i = lastReadyIndex + 1; lastReadyIndex >= 0 && i < events.size(); i++) {
                    if ("channels".equals(events.get(i).type())) {
                        return events.get(i);
                    }
                }
            }
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("no channels event arrived after the last ready status");
    }

    private static List<OutboundEvent> copyOf(List<OutboundEvent> events) {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    /**
     * Reads NICK and USER, answers with 001 (welcome), then blocks until the client
     * closes the connection.
     */
    private static void acceptRegistration(Socket socket, String nick) throws IOException {
        socket.setSoTimeout(5000);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = socket.getOutputStream();
        String line;
        int seen = 0;
        while (seen < 2 && (line = in.readLine()) != null) {
            if (line.regionMatches(true, 0, "NICK", 0, 4)
                    || line.regionMatches(true, 0, "USER", 0, 4)) {
                seen++;
            }
        }
        out.write((":srv 001 " + nick + " :Welcome\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        socket.setSoTimeout(0);
        in.readLine();
    }

    /**
     * Same handshake as {@link #acceptRegistration}, but counts down {@code signal}
     * right after the 001 line is sent, so a caller can observe registration having
     * happened while the connection is still meant to stay open.
     */
    private static void acceptRegistrationSignaling(Socket socket, String nick,
            CountDownLatch signal) throws IOException {
        socket.setSoTimeout(5000);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = socket.getOutputStream();
        String line;
        int seen = 0;
        while (seen < 2 && (line = in.readLine()) != null) {
            if (line.regionMatches(true, 0, "NICK", 0, 4)
                    || line.regionMatches(true, 0, "USER", 0, 4)) {
                seen++;
            }
        }
        out.write((":srv 001 " + nick + " :Welcome\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        signal.countDown();
        socket.setSoTimeout(0);
        in.readLine();
    }

    /**
     * Same as {@link #acceptRegistrationSignaling}, but drains the JOIN the client
     * sends immediately once registered (irc-client tracks the channel client-side as
     * soon as it sends that command, so nothing here needs to reply to it) before
     * blocking on the client's eventual close, so that JOIN line is never mistaken
     * for the close itself.
     */
    private static void acceptRegistrationJoinSignaling(Socket socket, String nick,
            CountDownLatch signal) throws IOException {
        socket.setSoTimeout(5000);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = socket.getOutputStream();
        String line;
        int seen = 0;
        while (seen < 2 && (line = in.readLine()) != null) {
            if (line.regionMatches(true, 0, "NICK", 0, 4)
                    || line.regionMatches(true, 0, "USER", 0, 4)) {
                seen++;
            }
        }
        out.write((":srv 001 " + nick + " :Welcome\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        in.readLine();
        signal.countDown();
        socket.setSoTimeout(0);
        in.readLine();
    }

    /**
     * Reads NICK and USER, answers with 001 like {@link #acceptRegistration}, but
     * then drops the connection immediately instead of waiting for the client to
     * close it, simulating an unexpected disconnection.
     */
    private static void registerThenDrop(Socket socket, String nick) throws IOException {
        socket.setSoTimeout(5000);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = socket.getOutputStream();
        String line;
        int seen = 0;
        while (seen < 2 && (line = in.readLine()) != null) {
            if (line.regionMatches(true, 0, "NICK", 0, 4)
                    || line.regionMatches(true, 0, "USER", 0, 4)) {
                seen++;
            }
        }
        out.write((":srv 001 " + nick + " :Welcome\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Same as {@link #registerThenDrop}, but drains the JOIN the client sends right
     * after registering before dropping the connection: without reading it here, the
     * server side of the socket can close while that write is still in flight, and
     * the drop looks like a send failure rather than the lost connection this
     * simulates.
     */
    private static void registerJoinThenDrop(Socket socket, String nick) throws IOException {
        socket.setSoTimeout(5000);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = socket.getOutputStream();
        String line;
        int seen = 0;
        while (seen < 2 && (line = in.readLine()) != null) {
            if (line.regionMatches(true, 0, "NICK", 0, 4)
                    || line.regionMatches(true, 0, "USER", 0, 4)) {
                seen++;
            }
        }
        out.write((":srv 001 " + nick + " :Welcome\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        in.readLine();
    }

    private static void rejectRegistration(Socket socket) throws IOException {
        socket.setSoTimeout(5000);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = socket.getOutputStream();
        String line;
        int seen = 0;
        while (seen < 2 && (line = in.readLine()) != null) {
            if (line.regionMatches(true, 0, "NICK", 0, 4)
                    || line.regionMatches(true, 0, "USER", 0, 4)) {
                seen++;
            }
        }
        out.write((":srv 464 * :Password incorrect\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static IrcServer fakeServer(int port) {
        return new IrcServer("local", "Fake", "127.0.0.1", port, false, false, false,
                List.of(), "test");
    }

    private static Consumer<OutboundEvent> synchronizedSink(List<OutboundEvent> events) {
        return event -> {
            synchronized (events) {
                events.add(event);
            }
        };
    }
}
