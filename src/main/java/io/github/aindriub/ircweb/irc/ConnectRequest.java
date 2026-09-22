package io.github.aindriub.ircweb.irc;

import java.util.List;

/**
 * What the browser sends to open a connection.
 *
 * @param serverId     which entry of the server directory to use
 * @param nick         nick to register with
 * @param password     server password, or a Twitch oauth token; null for none
 * @param saslUsername SASL account, defaulting to the nick
 * @param saslPassword SASL account password; null disables SASL
 * @param channels     channels to join once registered
 */
public record ConnectRequest(
        String serverId,
        String nick,
        String password,
        String saslUsername,
        String saslPassword,
        List<String> channels) {
}
