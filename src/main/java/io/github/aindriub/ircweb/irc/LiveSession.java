package io.github.aindriub.ircweb.irc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One IRC connection, owned by an account rather than by a browser tab.
 *
 * <p>This is what makes the application a bouncer: the socket comes and goes, and
 * the connection does not. Everything the server said while nobody was watching is
 * kept here, so reattaching shows the conversation rather than an empty window.
 *
 * <p>What is kept is of two kinds, and the difference matters. State — the status
 * line, the channel list, who is in each channel — is only ever worth the latest
 * value, so each new one replaces the last. Conversation is worth all of it up to a
 * limit, in the order it arrived. Replaying a thousand stale member lists to rebuild
 * one would be both slow and wrong.
 */
public final class LiveSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(LiveSession.class);

    /**
     * Messages kept per session. Enough to come back to in the morning, small
     * enough that a busy channel cannot grow the heap without limit while nobody
     * is attached to notice.
     */
    static final int BACKLOG = 500;

    private final String username;
    private final String serverId;
    private final IrcSession irc;

    private final Deque<OutboundEvent> backlog = new ArrayDeque<>();
    private final Map<String, OutboundEvent> names = new LinkedHashMap<>();
    private volatile OutboundEvent status;
    private volatile OutboundEvent channels;
    private volatile String nick;

    /** The browser currently watching, or null while nobody is. */
    private final AtomicReference<Consumer<OutboundEvent>> watcher = new AtomicReference<>();

    LiveSession(String username, String serverId) {
        this.username = username;
        this.serverId = serverId;
        this.irc = new IrcSession(this::record);
    }

    public String username() {
        return username;
    }

    public String serverId() {
        return serverId;
    }

    public IrcSession irc() {
        return irc;
    }

    public String nick() {
        return nick;
    }

    private void record(OutboundEvent event) {
        switch (event.type()) {
        case "status" -> {
            status = event;
            if (event.nick() != null) {
                nick = event.nick();
            }
        }
        case "channels" -> channels = event;
        case "names" -> {
            synchronized (names) {
                names.put(event.channel(), event);
            }
        }
        // Wire traffic is high volume and only interesting live. Keeping it would
        // push the actual conversation out of the backlog within seconds.
        case "raw" -> { }
        default -> {
            synchronized (backlog) {
                backlog.addLast(event);
                while (backlog.size() > BACKLOG) {
                    backlog.removeFirst();
                }
            }
        }
        }

        Consumer<OutboundEvent> watching = watcher.get();
        if (watching != null) {
            watching.accept(event);
        }
    }

    /**
     * Points the session at a browser and tells it everything it missed.
     *
     * <p>Any browser already attached is dropped: two windows on one connection
     * would each see half the traffic, which is worse than being told to use one.
     */
    public void attach(Consumer<OutboundEvent> browser) {
        watcher.set(browser);
        replayTo(browser);
    }

    /** Stops sending to a browser, without touching the IRC connection. */
    public void detach(Consumer<OutboundEvent> browser) {
        watcher.compareAndSet(browser, null);
    }

    private void replayTo(Consumer<OutboundEvent> browser) {
        // Bracketed, so the browser empties what it has first. Otherwise a socket
        // that dropped and came back appends a second copy of the backlog.
        browser.accept(OutboundEvent.replay("start"));
        browser.accept(OutboundEvent.attached(serverId, nick));

        if (status != null) {
            browser.accept(status);
        }
        if (channels != null) {
            browser.accept(channels);
        }

        List<OutboundEvent> members;
        synchronized (names) {
            members = new ArrayList<>(names.values());
        }
        members.forEach(browser);

        List<OutboundEvent> missed;
        synchronized (backlog) {
            missed = new ArrayList<>(backlog);
        }
        missed.forEach(browser);

        browser.accept(OutboundEvent.replay("end"));
    }

    void close() {
        watcher.set(null);
        try {
            irc.disconnect();
        } catch (RuntimeException e) {
            LOGGER.debug("Closing the session for {} was not clean", username, e);
        }
    }
}
