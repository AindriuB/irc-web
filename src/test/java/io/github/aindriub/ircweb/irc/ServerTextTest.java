package io.github.aindriub.ircweb.irc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerTextTest {

    @Test
    void nullInputIsNull() {
        assertNull(ServerText.sanitise(null));
    }

    @Test
    void blankInputIsNull() {
        assertNull(ServerText.sanitise("   "));
    }

    @Test
    void controlCharactersOnlyIsNull() {
        assertNull(ServerText.sanitise("\u0002\u0003\u001F"));
    }

    @Test
    void removesMircFormattingCodesFromRealisticText() {
        // \u0002 bold, \u000F reset: both C0 control characters, the same rule
        // that strips every mIRC formatting code.
        String withFormatting = "\u0002Login unsuccessful\u000F";
        assertEquals("Login unsuccessful", ServerText.sanitise(withFormatting));
    }

    @Test
    void removesC1ControlCharacters() {
        assertEquals("ab", ServerText.sanitise("a\u0085b"));
    }

    @Test
    void collapsesRunsOfWhitespaceToOneSpace() {
        assertEquals("a b c", ServerText.sanitise("a    b\t\tc"));
    }

    @Test
    void trimsLeadingAndTrailingWhitespace() {
        assertEquals("hello", ServerText.sanitise("   hello   "));
    }

    @Test
    void passesShortTextThrough() {
        assertEquals("Login unsuccessful", ServerText.sanitise("Login unsuccessful"));
    }

    @Test
    void capsAt200CharactersEndingInEllipsis() {
        String longText = "x".repeat(1000);

        String sanitised = ServerText.sanitise(longText);

        assertEquals(200, sanitised.length());
        assertTrue(sanitised.endsWith("…"));
        assertEquals("x".repeat(199) + "…", sanitised);
    }

    @Test
    void exactly200CharactersIsNotCut() {
        String exact = "x".repeat(200);

        String sanitised = ServerText.sanitise(exact);

        assertEquals(exact, sanitised);
        assertEquals(200, sanitised.length());
    }
}
