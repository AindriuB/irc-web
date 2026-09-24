package io.github.aindriub.ircweb.irc;

import io.github.aindriub.irc.client.message.IRCFormatting;

/**
 * Makes a server's own wording safe to show a browser.
 *
 * <p>A NOTICE or ERROR's trailing text is otherwise untrusted: it can carry mIRC
 * formatting codes (bold, colour, ...), which are C0 control characters, arbitrary
 * whitespace, or be far longer than anything worth putting next to a form. mIRC
 * formatting, including colour specs such as {@code \u000304,01}, is stripped first
 * via irc-client's {@link IRCFormatting#strip}, which understands the shape of a
 * colour spec (the control character and its foreground/background digits) rather
 * than treating each character alone.
 */
final class ServerText {

    /** Including the trailing '…' when the text is cut. */
    private static final int MAX_LENGTH = 200;

    private ServerText() {
    }

    /**
     * @return the text with mIRC formatting stripped, every remaining C0/C1
     *         control character removed, runs of whitespace collapsed to one
     *         space, trimmed, and capped at {@value #MAX_LENGTH} characters
     *         (ending in '…' when cut); or {@code null} for null or blank input,
     *         or input that is nothing but formatting, control characters and
     *         whitespace
     */
    static String sanitise(String raw) {
        if (raw == null) {
            return null;
        }
        String formatted = IRCFormatting.strip(raw);
        StringBuilder collapsed = new StringBuilder(formatted.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            // Whitespace checked first: a tab or newline is both a C0 control
            // character and whitespace, and must still act as a word boundary
            // (collapsed to one space) rather than being silently dropped and
            // fusing the words on either side of it.
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace) {
                    collapsed.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            if (Character.isISOControl(c)) {
                // Covers the rest of C0 (U+0000-U+001F) and C1 (U+007F-U+009F),
                // which includes every mIRC formatting code (bold 0x02, colour
                // 0x03, ...).
                continue;
            }
            collapsed.append(c);
            lastWasSpace = false;
        }
        String trimmed = collapsed.toString().trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_LENGTH) {
            return trimmed.substring(0, MAX_LENGTH - 1) + "…";
        }
        return trimmed;
    }
}
