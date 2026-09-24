package io.github.aindriub.ircweb.irc;

import java.util.Optional;

/**
 * The rules IRC itself imposes on the values a client sends as credentials,
 * mirroring irc-client 1.1.0's {@code IRCText.requireParam} and
 * {@code IRCText.requireText}. The server password and the SASL username are
 * sent as middle parameters, so they may not contain whitespace, a leading
 * ':' or a carriage return, line feed or NUL, since each is either a message
 * boundary or would turn the value into a trailing parameter that swallows
 * the rest of the line. The SASL password is base64-encoded into a trailing
 * parameter, so spaces are fine there; only CR, LF and NUL are rejected.
 * irc-client enforces this itself, but only once a connection is actually
 * being made, which is too late for a form to tell someone why nothing
 * happened. This mirrors that same rule so it can be checked the moment a
 * value is saved, and again at connect time (task 02), without the two ever
 * drifting apart.
 *
 * <p>Every message here is fixed text that never includes the value that was
 * rejected, so a submitted password can never end up in a response, a log, or
 * an exception's stack trace.
 */
public final class CredentialRules {

    public static final String PASS_LINE_HINT =
            "Enter just the token (oauth:…), not the whole PASS line";

    private static final String SERVER_PASSWORD_MESSAGE =
            "The server password cannot contain spaces or line breaks";
    private static final String SASL_PASSWORD_MESSAGE =
            "The SASL password cannot contain spaces or line breaks";
    private static final String SASL_USERNAME_MESSAGE =
            "The SASL username cannot contain spaces";
    private static final String SERVER_PASSWORD_LEADING_COLON_MESSAGE =
            "The server password must not start with ':'";
    private static final String SASL_USERNAME_LEADING_COLON_MESSAGE =
            "The SASL username must not start with ':'";

    private CredentialRules() {
    }

    /** Empty when usable, else a message that never contains the value. */
    public static Optional<String> checkPassword(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        if (value.regionMatches(true, 0, "PASS ", 0, 5)) {
            return Optional.of(PASS_LINE_HINT);
        }
        if (value.startsWith(":")) {
            return Optional.of(SERVER_PASSWORD_LEADING_COLON_MESSAGE);
        }
        return hasUnusableCharacter(value) ? Optional.of(SERVER_PASSWORD_MESSAGE) : Optional.empty();
    }

    /**
     * The SASL password is sent as a trailing IRC parameter (base64-encoded), so
     * unlike the server password and the SASL username, spaces are legal here:
     * only CR, LF and NUL are rejected.
     */
    public static Optional<String> checkSaslPassword(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        return hasControlCharacter(value) ? Optional.of(SASL_PASSWORD_MESSAGE) : Optional.empty();
    }

    public static Optional<String> checkSaslUsername(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        if (value.startsWith(":")) {
            return Optional.of(SASL_USERNAME_LEADING_COLON_MESSAGE);
        }
        return hasUnusableCharacter(value) ? Optional.of(SASL_USERNAME_MESSAGE) : Optional.empty();
    }

    private static boolean hasUnusableCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '\r' || c == '\n' || c == '\0') {
                return true;
            }
        }
        return false;
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {
                return true;
            }
        }
        return false;
    }
}
