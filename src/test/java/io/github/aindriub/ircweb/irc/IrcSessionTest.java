package io.github.aindriub.ircweb.irc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * Plain JUnit, no Spring and no ergo: a loopback {@link ServerSocket} stands in for
 * an IRC server, which is enough to prove two things that the production incident
 * turned on: a connect that irc-client would reject never even opens a socket, and a
 * connect that irc-client's own server rejects does not leave a reconnect loop
 * running behind it.
 */
class IrcSessionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ServerSocket serverSocket;

    @AfterEach
    void closeServer() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    @Test
    @DisplayName("a PASS line typed as the password is refused before any socket is opened")
    void refusesAPassLineBeforeConnecting() throws Exception {
        serverSocket = new ServerSocket(0);
        List<OutboundEvent> events = new ArrayList<>();
        IrcSession session = new IrcSession(synchronizedSink(events));

        IrcServer server = fakeServer(serverSocket.getLocalPort());
        ConnectRequest request = new ConnectRequest("local", "webtest",
                "PASS oauth:sekrit-token-123", null, null, List.of());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> session.connect(server, request));
        assertEquals(CredentialRules.PASS_LINE_HINT, thrown.getMessage());

        synchronized (events) {
            assertFalse(events.isEmpty(), "expected at least one event");
            OutboundEvent first = events.get(0);
            assertEquals("status", first.type());
            assertEquals("disconnected", first.state());
            assertEquals(CredentialRules.PASS_LINE_HINT, first.detail());
            assertFalse(events.stream().anyMatch(
                    e -> "connecting".equals(e.state())),
                    "no connecting event should have been emitted");
        }

        // The check happens before IRCBot.builder() is ever reached, so the fake
        // server never sees a connection attempt at all.
        serverSocket.setSoTimeout(2000);
        assertThrows(SocketTimeoutException.class, serverSocket::accept,
                "the rejected credential must never reach a real socket");

        for (OutboundEvent event : events) {
            String payload = JSON.writeValueAsString(event);
            assertFalse(payload.contains("sekrit-token-123"),
                    "an event leaked the token: " + payload);
        }
    }

    @Test
    @DisplayName("a nick with a space is refused before connecting when it is also used as the "
            + "SASL username")
    void refusesANickWithASpaceWhenUsedAsTheSaslUsername() throws Exception {
        serverSocket = new ServerSocket(0);
        List<OutboundEvent> events = new ArrayList<>();
        IrcSession session = new IrcSession(synchronizedSink(events));

        IrcServer server = fakeServer(serverSocket.getLocalPort());
        // No saslUsername: connect() falls back to the nick, which is invalid as a
        // SASL username (it contains a space).
        ConnectRequest request = new ConnectRequest("local", "a b", null, null, "saslpass",
                List.of());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> session.connect(server, request));
        assertEquals("The nick cannot contain spaces or start with ':' when it is also the "
                + "SASL username", thrown.getMessage());

        // The check happens before IRCBot.builder() is ever reached, so the fake
        // server never sees a connection attempt at all.
        serverSocket.setSoTimeout(2000);
        assertThrows(SocketTimeoutException.class, serverSocket::accept,
                "the rejected nick must never reach a real socket");
    }

    @Test
    @DisplayName("a connect the server rejects stops the bot instead of leaving it reconnecting")
    void stopsTheBotOnARejectedRegistration() throws Exception {
        serverSocket = new ServerSocket(0);
        AtomicBoolean sawSecondConnection = new AtomicBoolean(false);
        CountDownLatch secondAttemptWindowClosed = new CountDownLatch(1);

        Thread fakeIrcServer = new Thread(() -> {
            try (Socket first = serverSocket.accept()) {
                rejectRegistration(first);
            } catch (IOException e) {
                // The test thread will time out on the missing status event and
                // report the failure; nothing more useful to do here.
                return;
            }
            // Proves the reconnect loop is really gone: irc-client's default
            // backoff would have retried within about a second if stop() had not
            // been called. Four seconds is generous headroom above that.
            try {
                serverSocket.setSoTimeout(4000);
                try (Socket second = serverSocket.accept()) {
                    sawSecondConnection.set(true);
                }
            } catch (SocketTimeoutException expected) {
                // No second connection arrived: the bot was really stopped.
            } catch (IOException e) {
                // Socket closed from under us by test teardown; not a reconnect.
            } finally {
                secondAttemptWindowClosed.countDown();
            }
        }, "fake-irc-server");
        fakeIrcServer.setDaemon(true);
        fakeIrcServer.start();

        List<OutboundEvent> events = new ArrayList<>();
        IrcSession session = new IrcSession(synchronizedSink(events));
        IrcServer server = fakeServer(serverSocket.getLocalPort());
        ConnectRequest request = new ConnectRequest("local", "webtest", null, null, null,
                List.of());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> session.connect(server, request));
        assertTrue(thrown.getMessage().startsWith("The server rejected registration"),
                "expected the mapped reason, got: " + thrown.getMessage());

        synchronized (events) {
            OutboundEvent last = events.get(events.size() - 1);
            assertEquals("status", last.type());
            assertEquals("disconnected", last.state());
            assertTrue(last.detail().startsWith("The server rejected registration"),
                    "expected the mapped reason, got: " + last.detail());
        }

        assertTrue(secondAttemptWindowClosed.await(10, TimeUnit.SECONDS),
                "the fake server never finished watching for a second connection");
        assertFalse(sawSecondConnection.get(),
                "a second connection means the reconnect loop was not stopped");
    }

    @Test
    @DisplayName("an Error during start still stops the bot the attempt built")
    void anErrorDuringStartStillStopsTheBot() throws Exception {
        serverSocket = new ServerSocket(0);
        List<OutboundEvent> events = new ArrayList<>();
        ErrorOnceSession session = new ErrorOnceSession(synchronizedSink(events));
        IrcServer server = fakeServer(serverSocket.getLocalPort());
        ConnectRequest request = new ConnectRequest("local", "webtest", null, null, null,
                List.of());

        // The seam throws before start() ever calls client.connect(), so this proves
        // the cleanup itself, not the fake server: an Error thrown mid-start must be
        // rethrown unchanged after connect() has already stopped the bot and reset
        // running, exactly like a RuntimeException does.
        OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class,
                () -> session.connect(server, request));
        assertEquals("simulated for test", thrown.getMessage());

        synchronized (events) {
            OutboundEvent last = events.get(events.size() - 1);
            assertEquals("status", last.type());
            assertEquals("disconnected", last.state());
        }

        // Nothing ever reached the fake server: the seam fired before any socket was
        // opened.
        serverSocket.setSoTimeout(1000);
        assertThrows(SocketTimeoutException.class, serverSocket::accept);

        // The proof that stop() really ran and running was really reset: a second,
        // un-seamed connect on the same session succeeds rather than being refused
        // as "already connected".
        Thread fakeIrcServer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                acceptRegistration(socket, "webtest");
            } catch (IOException e) {
                // The assertion below times out and reports the failure.
            }
        }, "fake-irc-server-after-error");
        fakeIrcServer.setDaemon(true);
        fakeIrcServer.start();

        assertDoesNotThrow(() -> session.connect(server, request));
        session.disconnect();
    }

    @Test
    @DisplayName("a failed connect does not block reconnecting to a server that now accepts")
    void canReconnectAfterAFailedConnect() throws Exception {
        serverSocket = new ServerSocket(0);
        List<OutboundEvent> events = new ArrayList<>();
        IrcSession session = new IrcSession(synchronizedSink(events));
        IrcServer server = fakeServer(serverSocket.getLocalPort());
        ConnectRequest request = new ConnectRequest("local", "webtest", null, null, null,
                List.of());

        Thread fakeIrcServer = new Thread(() -> {
            try (Socket first = serverSocket.accept()) {
                rejectRegistration(first);
            } catch (IOException e) {
                return;
            }
            try (Socket second = serverSocket.accept()) {
                acceptRegistration(second, "webtest");
            } catch (IOException e) {
                // The assertion below times out and reports the failure.
            }
        }, "fake-irc-server-reconnect");
        fakeIrcServer.setDaemon(true);
        fakeIrcServer.start();

        assertThrows(IllegalStateException.class, () -> session.connect(server, request));

        // Refusing every later attempt as "already connected" to a connection that
        // does not exist was exactly the bug: running must have been reset.
        assertDoesNotThrow(() -> session.connect(server, request));

        synchronized (events) {
            assertTrue(events.stream().anyMatch(e -> "status".equals(e.type())
                            && "ready".equals(e.state())),
                    "expected a ready status once the second attempt registered");
        }

        session.disconnect();
    }

    @Test
    @DisplayName("an ERROR during registration maps to a fixed, credential-free server-refusal "
            + "reason, prefixed with what the server itself said")
    void anErrorDuringRegistrationMapsToAServerRefusalReason() throws Exception {
        serverSocket = new ServerSocket(0);
        List<OutboundEvent> events = new ArrayList<>();
        IrcSession session = new IrcSession(synchronizedSink(events));
        IrcServer server = fakeServer(serverSocket.getLocalPort());
        ConnectRequest request = new ConnectRequest("local", "webtest", null, null, null,
                List.of());

        Thread fakeIrcServer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                errorDuringRegistration(socket);
            } catch (IOException e) {
                // The assertion below times out and reports the failure.
            }
        }, "fake-irc-server-error");
        fakeIrcServer.setDaemon(true);
        fakeIrcServer.start();

        assertThrows(IllegalStateException.class, () -> session.connect(server, request));

        synchronized (events) {
            OutboundEvent last = events.get(events.size() - 1);
            assertEquals("status", last.type());
            assertEquals("disconnected", last.state());
            // Task 02: the ERROR's own trailing text, captured while the attempt was
            // in progress, now leads the detail, with the fixed reason kept after it.
            assertTrue(last.detail().startsWith(server.name() + " said: Closing Link"),
                    "expected the server's own wording first, got: " + last.detail());
            assertTrue(last.detail().contains(
                    "The server refused the connection. Check the server settings and try again."),
                    "expected the fixed reason too, got: " + last.detail());
        }
    }

    @Test
    @DisplayName("a NOTICE seen before the connection closes is quoted in the failure reason")
    void aNoticeBeforeAnUnexpectedCloseIsQuotedInTheFailureReason() throws Exception {
        serverSocket = new ServerSocket(0);
        List<OutboundEvent> events = new ArrayList<>();
        IrcSession session = new IrcSession(synchronizedSink(events));
        IrcServer server = fakeServer(serverSocket.getLocalPort());
        ConnectRequest request = new ConnectRequest("local", "webtest", "oauth:sekrit-XYZ", null,
                null, List.of());

        Thread fakeIrcServer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                noticeThenClose(socket, "Login unsuccessful");
            } catch (IOException e) {
                // The assertion below times out and reports the failure.
            }
        }, "fake-irc-server-notice");
        fakeIrcServer.setDaemon(true);
        fakeIrcServer.start();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> session.connect(server, request));
        // The fixed reason irc-client's own "Connection closed before registration
        // completed" maps to, unaffected by this task.
        String fixedReason =
                "The connection closed before registration finished. Try again.";
        assertEquals(fixedReason, thrown.getMessage());

        synchronized (events) {
            OutboundEvent last = events.get(events.size() - 1);
            assertEquals("status", last.type());
            assertEquals("disconnected", last.state());
            assertTrue(last.detail().startsWith(server.name() + " said: Login unsuccessful"),
                    "expected the server's own wording first, got: " + last.detail());
            assertTrue(last.detail().contains(fixedReason),
                    "expected the fixed reason too, got: " + last.detail());

            for (OutboundEvent event : events) {
                String payload = JSON.writeValueAsString(event);
                assertFalse(payload.contains("sekrit-XYZ"),
                        "an event leaked the token: " + payload);
            }
        }
    }

    @Test
    @DisplayName("an ERROR's trailing text, control characters and length included, reaches the "
            + "detail sanitised")
    void anErrorWithControlCharactersAndALongTailIsSanitisedInTheFailureReason() throws Exception {
        serverSocket = new ServerSocket(0);
        List<OutboundEvent> events = new ArrayList<>();
        IrcSession session = new IrcSession(synchronizedSink(events));
        IrcServer server = fakeServer(serverSocket.getLocalPort());
        ConnectRequest request = new ConnectRequest("local", "webtest", null, null, null,
                List.of());

        String tail = "x".repeat(1000);
        String dirty = "\u0003\u0002Closing Link: " + tail;
        Thread fakeIrcServer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                noticeThenClose(socket, dirty);
            } catch (IOException e) {
                // The assertion below times out and reports the failure.
            }
        }, "fake-irc-server-dirty-notice");
        fakeIrcServer.setDaemon(true);
        fakeIrcServer.start();

        assertThrows(IllegalStateException.class, () -> session.connect(server, request));

        synchronized (events) {
            OutboundEvent last = events.get(events.size() - 1);
            assertEquals("status", last.type());
            assertEquals("disconnected", last.state());
            assertFalse(last.detail().contains("\u0003"));
            assertFalse(last.detail().contains("\u0002"));
            String quoted = last.detail().substring(
                    (server.name() + " said: ").length());
            int quoteEnd = quoted.indexOf(". The connection closed");
            String quotedPart = quoteEnd < 0 ? quoted : quoted.substring(0, quoteEnd);
            assertTrue(quotedPart.length() <= 200,
                    "quoted part too long (" + quotedPart.length() + "): " + quotedPart);
        }
    }

    @Test
    @DisplayName("a connect with no NOTICE or ERROR seen gives exactly the reason it always gave")
    void noCapturedServerTextLeavesTheReasonUnchanged() throws Exception {
        serverSocket = new ServerSocket(0);
        List<OutboundEvent> events = new ArrayList<>();
        IrcSession session = new IrcSession(synchronizedSink(events));
        IrcServer server = fakeServer(serverSocket.getLocalPort());
        ConnectRequest request = new ConnectRequest("local", "webtest", null, null, null,
                List.of());

        Thread fakeIrcServer = new Thread(() -> {
            try (Socket first = serverSocket.accept()) {
                rejectRegistration(first);
            } catch (IOException e) {
                // The assertion below times out and reports the failure.
            }
        }, "fake-irc-server-plain-reject");
        fakeIrcServer.setDaemon(true);
        fakeIrcServer.start();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> session.connect(server, request));
        assertTrue(thrown.getMessage().startsWith("The server rejected registration"));

        synchronized (events) {
            OutboundEvent last = events.get(events.size() - 1);
            assertEquals("status", last.type());
            assertEquals("disconnected", last.state());
            assertEquals(thrown.getMessage(), last.detail());
            assertFalse(last.detail().contains(" said: "),
                    "no server text was seen, so nothing should be quoted: " + last.detail());
        }
    }

    @Test
    @DisplayName("a notice after registration completes still arrives as an ordinary notice event")
    void aNoticeAfterRegistrationIsAnOrdinaryNoticeEvent() throws Exception {
        serverSocket = new ServerSocket(0);
        List<OutboundEvent> events = new ArrayList<>();
        IrcSession session = new IrcSession(synchronizedSink(events));
        IrcServer server = fakeServer(serverSocket.getLocalPort());
        ConnectRequest request = new ConnectRequest("local", "webtest", null, null, null,
                List.of());

        CountDownLatch noticeSent = new CountDownLatch(1);
        Thread fakeIrcServer = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                acceptRegistrationThenNotice(socket, "webtest", "Welcome to the network",
                        noticeSent);
            } catch (IOException e) {
                // The assertion below times out and reports the failure.
            }
        }, "fake-irc-server-post-ready-notice");
        fakeIrcServer.setDaemon(true);
        fakeIrcServer.start();

        assertDoesNotThrow(() -> session.connect(server, request));
        assertTrue(noticeSent.await(10, TimeUnit.SECONDS),
                "the fake server never got to send its post-registration notice");

        long deadline = System.currentTimeMillis() + 10_000;
        boolean sawNotice;
        do {
            synchronized (events) {
                sawNotice = events.stream().anyMatch(e -> "notice".equals(e.type())
                        && "Welcome to the network".equals(e.text()));
            }
            if (!sawNotice) {
                Thread.sleep(50);
            }
        } while (!sawNotice && System.currentTimeMillis() < deadline);
        assertTrue(sawNotice, "expected the post-registration NOTICE as an ordinary notice event");

        session.disconnect();
    }

    @Test
    @DisplayName("onReady after a reconnect runs safely off a Netty loop, on irc-client's "
            + "connection-event thread")
    void onReadyIsSafeAfterAReconnectOnTheConnectionEventThread() throws Exception {
        serverSocket = new ServerSocket(0);
        List<OutboundEvent> events = new ArrayList<>();
        // Parallel to events, index for index: which thread delivered each one.
        List<String> threadNames = new ArrayList<>();
        IrcSession session = new IrcSession(event -> {
            synchronized (events) {
                events.add(event);
                threadNames.add(Thread.currentThread().getName());
            }
        });
        // Short enough that the test does not wait out irc-client's real default
        // backoff (1000 ms initial), long enough not to race the fake server below.
        session.setReconnectBackoffForTest(50, 100);
        IrcServer server = fakeServer(serverSocket.getLocalPort());
        ConnectRequest request = new ConnectRequest("local", "webtest", null, null, null,
                List.of());

        CountDownLatch secondRegistrationAccepted = new CountDownLatch(1);
        Thread fakeIrcServer = new Thread(() -> {
            try (Socket first = serverSocket.accept()) {
                registerThenDrop(first, "webtest");
            } catch (IOException e) {
                return;
            }
            try (Socket second = serverSocket.accept()) {
                acceptRegistrationSignaling(second, "webtest", secondRegistrationAccepted);
            } catch (IOException e) {
                // The assertion below times out and reports the failure.
            }
        }, "fake-irc-server-reconnect-onready");
        fakeIrcServer.setDaemon(true);
        fakeIrcServer.start();

        assertDoesNotThrow(() -> session.connect(server, request));

        assertTrue(secondRegistrationAccepted.await(10, TimeUnit.SECONDS),
                "the fake server never saw the reconnect's second registration");

        // onReady runs asynchronously off the connection-event thread after a
        // reconnect, so the second "ready" status is awaited rather than asserted
        // on immediately.
        long deadline = System.currentTimeMillis() + 10_000;
        boolean sawSecondReady;
        boolean sawChannelsAfterReady;
        do {
            synchronized (events) {
                long readyCount = events.stream()
                        .filter(e -> "status".equals(e.type()) && "ready".equals(e.state()))
                        .count();
                sawSecondReady = readyCount >= 2;
                // The final value of lastReadyIndex, found first, is what "after"
                // means below; a channels event spotted before the second ready
                // (from the first connect) must not satisfy this.
                int lastReadyIndex = -1;
                for (int i = 0; i < events.size(); i++) {
                    OutboundEvent event = events.get(i);
                    if ("status".equals(event.type()) && "ready".equals(event.state())) {
                        lastReadyIndex = i;
                    }
                }
                boolean channelsAfter = false;
                for (int i = lastReadyIndex + 1; lastReadyIndex >= 0 && i < events.size(); i++) {
                    if ("channels".equals(events.get(i).type())) {
                        channelsAfter = true;
                        break;
                    }
                }
                sawChannelsAfterReady = sawSecondReady && channelsAfter;
            }
            if (!sawChannelsAfterReady) {
                Thread.sleep(50);
            }
        } while (!sawChannelsAfterReady && System.currentTimeMillis() < deadline);

        assertTrue(sawSecondReady, "expected a second ready status after the reconnect");
        assertTrue(sawChannelsAfterReady,
                "expected a channels event after the second ready status");

        synchronized (events) {
            int secondReadyIndex = -1;
            for (int i = 0; i < events.size(); i++) {
                OutboundEvent event = events.get(i);
                if ("status".equals(event.type()) && "ready".equals(event.state())) {
                    secondReadyIndex = i;
                }
            }
            OutboundEvent secondReady = events.get(secondReadyIndex);
            assertEquals("webtest", secondReady.nick());
            // The whole point of this test: after a reconnect, onReady runs on
            // irc-client's own connection-event thread rather than a Netty loop.
            assertTrue(threadNames.get(secondReadyIndex).startsWith("irc-connection-events"),
                    "expected the connection-event thread, got: "
                            + threadNames.get(secondReadyIndex));
        }

        session.disconnect();
    }

    /**
     * Reads NICK and USER, answers with 001 (welcome) and then blocks until the
     * client closes the connection, so an already-successful registration is not
     * mistaken by irc-client for a dropped connection worth reconnecting from.
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
     * right after the 001 line is sent, before blocking on the client's eventual
     * close. Plain {@link #acceptRegistration} cannot be observed from outside until
     * it returns, which is too late for a caller that needs to know registration
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
     * Reads NICK and USER, answers with 001 (welcome) like {@link #acceptRegistration},
     * but then drops the connection immediately instead of waiting for the client to
     * close it, simulating an unexpected disconnection that irc-client's own
     * automatic reconnect should notice and recover from.
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
     * Reads NICK and USER, then answers with a bare ERROR, the shape irc-client
     * 1.2.1's {@code RegistrationHandler} maps to a {@link
     * io.github.aindriub.irc.client.handler.ServerRefusedException}.
     */
    private static void errorDuringRegistration(Socket socket) throws IOException {
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
        out.write("ERROR :Closing Link\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Reads NICK and USER, sends a NOTICE with {@code text} as its trailing
     * parameter, then closes the connection without ever completing registration,
     * the shape production saw from Twitch on a bad token.
     */
    private static void noticeThenClose(Socket socket, String text) throws IOException {
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
        out.write((":tmi.twitch.tv NOTICE * :" + text + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Completes registration like {@link #acceptRegistration}, sends a NOTICE right
     * afterwards and signals {@code notified}, then blocks until the client closes,
     * so a test can prove a NOTICE seen after {@code onReady} is treated as an
     * ordinary notice rather than being captured for a failure reason.
     */
    private static void acceptRegistrationThenNotice(Socket socket, String nick, String text,
            CountDownLatch notified) throws IOException {
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
        out.write((":srv NOTICE " + nick + " :" + text + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        notified.countDown();
        socket.setSoTimeout(0);
        in.readLine();
    }

    private static void rejectRegistration(Socket socket) throws IOException {
        socket.setSoTimeout(5000);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = socket.getOutputStream();
        // No password is sent for this request, so the handshake is just NICK then
        // USER; reading both before replying keeps this from racing the client.
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

    private static java.util.function.Consumer<OutboundEvent> synchronizedSink(
            List<OutboundEvent> events) {
        return event -> {
            synchronized (events) {
                events.add(event);
            }
        };
    }

    /**
     * A session whose first start attempt throws an {@link Error} instead of really
     * starting, using {@link IrcSession}'s package-private seam. Every attempt after
     * the first behaves exactly like the production code, which is what lets the
     * same instance prove a real second connect works afterwards.
     */
    private static final class ErrorOnceSession extends IrcSession {

        private final AtomicBoolean failNext = new AtomicBoolean(true);

        ErrorOnceSession(java.util.function.Consumer<OutboundEvent> sink) {
            super(sink);
        }

        @Override
        void startBot(io.github.aindriub.irc.client.bot.IRCBot bot) {
            if (failNext.compareAndSet(true, false)) {
                throw new OutOfMemoryError("simulated for test");
            }
            super.startBot(bot);
        }
    }
}
