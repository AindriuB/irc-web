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
            // Proves the reconnect loop is really gone: irc-client 1.1.0's default
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
