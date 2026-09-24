package io.github.aindriub.ircweb.irc;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * The connections currently held on behalf of each account.
 *
 * <p>One per account rather than one per browser, which is the whole point: the
 * connection belongs to the person, and a browser attaches to it.
 *
 * <p>Deliberately one network at a time. Keying by account alone makes "am I
 * already connected?" a question with one answer, and a second connection under the
 * same nick is more often a mistake than a plan. Several at once needs a window
 * that can show them, which this does not have yet.
 */
@Component
public class IrcSessionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(IrcSessionRegistry.class);

    private final Map<String, LiveSession> sessions = new ConcurrentHashMap<>();

    public Optional<LiveSession> find(String username) {
        return Optional.ofNullable(sessions.get(username));
    }

    /**
     * Starts a session for an account that has none.
     *
     * @throws IllegalStateException if one is already running, naming the network,
     *                               since "already connected" without saying to what
     *                               leaves nothing to act on
     */
    public LiveSession open(String username, String serverId) {
        return sessions.compute(username, (key, existing) -> {
            if (existing != null) {
                throw new IllegalStateException(
                        "already connected to " + existing.serverId() + "; disconnect first");
            }
            LOGGER.info("Opening an IRC session for '{}' on {}", username, serverId);
            LiveSession session = new LiveSession(username, serverId);
            // Wired so a session that gives up reconnecting can take itself out of
            // the map, but only if it is still the one mapped here: an event from an
            // older, already-replaced session (its bot stopping in its own time,
            // off the connection-event thread) must never end whatever is running
            // now under the same username.
            session.irc().whenGivenUp(() -> endIfCurrent(username, session));
            return session;
        });
    }

    /** Ends a session and leaves the network. The only way off, now a closed tab is not. */
    public void end(String username) {
        LiveSession session = sessions.remove(username);
        if (session != null) {
            LOGGER.info("Closing the IRC session for '{}' on {}", username, session.serverId());
            session.close();
        }
    }

    /**
     * Ends {@code session} only if it is still the one mapped for {@code username} —
     * a compare-and-remove, backed by {@link ConcurrentHashMap#remove(Object, Object)}.
     * Package-private: {@link #open} wires this to each session's own gave-up
     * callback, and a test drives it directly to prove the comparison without a
     * live gave-up.
     */
    void endIfCurrent(String username, LiveSession session) {
        if (sessions.remove(username, session)) {
            LOGGER.info("Closing the IRC session for '{}' on {} after giving up on reconnecting",
                    username, session.serverId());
            session.close();
        }
    }

    @PreDestroy
    void closeAll() {
        Collection<LiveSession> live = sessions.values();
        LOGGER.info("Shutting down with {} IRC session(s) open", live.size());
        live.forEach(LiveSession::close);
        sessions.clear();
    }
}
