package io.github.aindriub.ircweb.irc;

import java.util.List;

/**
 * A profile as submitted by the browser.
 *
 * <p>The two password fields are three-valued on purpose, which a plain string
 * cannot express: null means leave what is stored alone, empty means clear it, and
 * anything else means replace it. Without that, a form that never shows the current
 * password would wipe it every time anything else was edited.
 */
public record ProfileUpdate(
        String nick,
        String username,
        String realname,
        String password,
        String saslUsername,
        String saslPassword,
        List<String> channels) {
}
