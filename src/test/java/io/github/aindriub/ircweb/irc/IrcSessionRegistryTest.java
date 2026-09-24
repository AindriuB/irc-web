package io.github.aindriub.ircweb.irc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit: proves the compare-and-remove {@link IrcSessionRegistry#endIfCurrent}
 * on its own, without a live gave-up event, which is how {@link IrcSessionRegistry#open}
 * wires it to {@link IrcSession#whenGivenUp}; and, with a fake server standing in for
 * an IRC server like {@link IrcSessionTest}, that the wiring itself works end to end.
 */
class IrcSessionRegistryTest {

    @Test
    @DisplayName("an old session's gave-up must not remove a newer session opened under the "
            + "same username")
    void oldSessionsGaveUpDoesNotRemoveANewerSession() {
        IrcSessionRegistry registry = new IrcSessionRegistry();

        LiveSession first = registry.open("bob", "net1");
        registry.end("bob");
        LiveSession second = registry.open("bob", "net2");

        // Simulates the first session's own gave-up callback firing late, after the
        // username has already moved on to a second session.
        registry.endIfCurrent("bob", first);

        assertTrue(registry.find("bob").isPresent(),
                "the newer session must still be registered");
        assertSame(second, registry.find("bob").get(),
                "the newer session must not have been replaced or removed");
    }

    @Test
    @DisplayName("a session's own gave-up removes it, when it is still the one mapped")
    void endIfCurrentRemovesTheSessionItNames() {
        IrcSessionRegistry registry = new IrcSessionRegistry();

        LiveSession session = registry.open("alice", "net1");
        registry.endIfCurrent("alice", session);

        assertFalse(registry.find("alice").isPresent(),
                "the session should have been removed");
    }

    @Test
    @DisplayName("a live gave-up, through the registry's own wiring, removes the session and "
            + "leaves the account free to open a fresh one")
    void aLiveGaveUpRemovesTheSessionThroughTheRegistrysOwnWiring() throws Exception {
        IrcSessionRegistry registry = new IrcSessionRegistry();
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            LiveSession session = registry.open("bob", "local");
            // A cap of 2, with a short backoff: the fake server below never comes
            // back, so a gave-up must follow quickly.
            session.irc().setReconnectBackoffForTest(50, 100, 2);

            Thread fakeIrcServer = new Thread(() -> {
                try (Socket first = serverSocket.accept()) {
                    registerThenDrop(first, "webtest");
                } catch (IOException e) {
                    return;
                }
                try {
                    // Never comes back: every reconnect attempt finds the port
                    // closed.
                    serverSocket.close();
                } catch (IOException ignored) {
                    // Nothing more to do.
                }
            }, "fake-irc-server-registry-gives-up");
            fakeIrcServer.setDaemon(true);
            fakeIrcServer.start();

            IrcServer server = fakeServer(serverSocket.getLocalPort());
            ConnectRequest request = new ConnectRequest("local", "webtest", null, null, null,
                    List.of());
            assertDoesNotThrow(() -> session.irc().connect(server, request));

            awaitRegistryEmpty(registry, "bob", 5_000);

            LiveSession fresh = registry.open("bob", "local");
            assertNotSame(session, fresh, "a fresh session, not the one that gave up");
            registry.end("bob");
        }
    }

    private static void awaitRegistryEmpty(IrcSessionRegistry registry, String username,
            long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        do {
            if (registry.find(username).isEmpty()) {
                return;
            }
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError(
                "the gave-up session was never removed from the registry in time");
    }

    /**
     * Reads NICK and USER, answers with 001 (welcome) then drops the connection
     * immediately, simulating an unexpected disconnection.
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

    private static IrcServer fakeServer(int port) {
        return new IrcServer("local", "Fake", "127.0.0.1", port, false, false, false,
                List.of(), "test");
    }
}
