package io.github.aindriub.ircweb.irc;

import java.time.Instant;
import java.util.List;

/**
 * How this client connects to one server, as the browser sees it.
 *
 * <p>There are no password fields. The API reports whether a secret is set, never
 * what it is: this application holds credentials for other people's networks and a
 * readable-back password is one XSS or one forgotten firewall rule from being
 * someone else's.
 *
 * @param hasPassword     whether a server password is stored
 * @param hasSaslPassword whether a SASL password is stored
 */
public record ServerProfile(
        String serverId,
        String nick,
        String username,
        String realname,
        String saslUsername,
        List<String> channels,
        boolean hasPassword,
        boolean hasSaslPassword,
        Instant updatedAt) {

    public static ServerProfile empty(String serverId) {
        return new ServerProfile(serverId, null, null, null, null, List.of(), false, false, null);
    }
}
