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
            return new LiveSession(username, serverId);
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

    @PreDestroy
    void closeAll() {
        Collection<LiveSession> live = sessions.values();
        LOGGER.info("Shutting down with {} IRC session(s) open", live.size());
        live.forEach(LiveSession::close);
        sessions.clear();
    }
}
