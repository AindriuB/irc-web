package io.github.aindriub.ircweb.irc;

import java.util.List;

/**
 * One entry from the server directory.
 *
 * @param id         stable key used by the UI and by tests
 * @param name       what a human calls it
 * @param host       hostname to connect to
 * @param port       port to connect to
 * @param tls        whether the port expects TLS
 * @param sasl       whether the network supports SASL PLAIN
 * @param registered whether a registered account is needed to be useful
 * @param exercises  which parts of the library this target is good for proving
 * @param notes      anything that would otherwise be rediscovered the hard way
 */
public record IrcServer(
        String id,
        String name,
        String host,
        int port,
        boolean tls,
        boolean sasl,
        boolean registered,
        List<String> exercises,
        String notes) {

    /**
     * True for the servers this project starts itself. They are the only ones that
     * can be restarted mid-test, and the only ones safe to point a reconnect loop at.
     */
    public boolean isLocal() {
        return "127.0.0.1".equals(host) || "localhost".equals(host);
    }
}
