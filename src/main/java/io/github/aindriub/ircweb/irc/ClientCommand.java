package io.github.aindriub.ircweb.irc;

import java.util.List;

/**
 * Anything the browser sends. One shape, switched on {@code type}.
 */
public record ClientCommand(
        String type,
        String serverId,
        String nick,
        String password,
        String saslUsername,
        String saslPassword,
        List<String> channels,
        String target,
        String text,
        String channel,
        String line) {

    public ConnectRequest toConnectRequest() {
        return new ConnectRequest(serverId, nick, password, saslUsername, saslPassword,
                channels);
    }
}
