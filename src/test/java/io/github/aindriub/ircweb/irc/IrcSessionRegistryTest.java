package io.github.aindriub.ircweb.irc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit: proves the compare-and-remove {@link IrcSessionRegistry#endIfCurrent}
 * on its own, without a live gave-up event, which is how {@link IrcSessionRegistry#open}
 * wires it to {@link IrcSession#whenGivenUp}.
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
}
