package io.github.aindriub.ircweb.irc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CredentialRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"PASS oauth:abc", "pass oauth:abc", "Pass  oauth:abc"})
    @DisplayName("a server password that is really a PASS line gets the PASS hint")
    void passLineIsHinted(String value) {
        assertEquals(Optional.of(CredentialRules.PASS_LINE_HINT),
                CredentialRules.checkPassword(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "a\tb", "a\rb", "a\nb", "a\0b"})
    @DisplayName("a server password with whitespace or a line break is rejected")
    void serverPasswordRejectsUnusableCharacters(String value) {
        assertEquals(Optional.of("The server password cannot contain spaces or line breaks"),
                CredentialRules.checkPassword(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\rb", "a\nb", "a\0b"})
    @DisplayName("a SASL password with a line break is rejected")
    void saslPasswordRejectsControlCharacters(String value) {
        assertEquals(Optional.of("The SASL password cannot contain line breaks"),
                CredentialRules.checkSaslPassword(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"correct horse battery", "a b"})
    @DisplayName("a SASL password with a space is accepted")
    void saslPasswordAcceptsSpaces(String value) {
        assertTrue(CredentialRules.checkSaslPassword(value).isEmpty());
    }

    @Test
    @DisplayName("a SASL username with a space is rejected")
    void saslUsernameRejectsSpace() {
        assertEquals(Optional.of("The SASL username cannot contain spaces"),
                CredentialRules.checkSaslUsername("a b"));
    }

    @Test
    @DisplayName("a server password starting with ':' is rejected")
    void serverPasswordRejectsLeadingColon() {
        assertEquals(Optional.of("The server password must not start with ':'"),
                CredentialRules.checkPassword(":secret"));
    }

    @Test
    @DisplayName("a SASL username starting with ':' is rejected")
    void saslUsernameRejectsLeadingColon() {
        assertEquals(Optional.of("The SASL username must not start with ':'"),
                CredentialRules.checkSaslUsername(":name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"oauth:abc123"})
    @DisplayName("a usable value passes every check")
    void usableValuePasses(String value) {
        assertTrue(CredentialRules.checkPassword(value).isEmpty());
        assertTrue(CredentialRules.checkSaslPassword(value).isEmpty());
        assertTrue(CredentialRules.checkSaslUsername(value).isEmpty());
    }

    @Test
    @DisplayName("null and empty pass every check")
    void nullAndEmptyPass() {
        List<Function<String, Optional<String>>> checks = List.of(
                CredentialRules::checkPassword,
                CredentialRules::checkSaslPassword,
                CredentialRules::checkSaslUsername);
        for (Function<String, Optional<String>> check : checks) {
            assertTrue(check.apply(null).isEmpty());
            assertTrue(check.apply("").isEmpty());
        }
    }

    @Test
    @DisplayName("no returned message ever contains the rejected value")
    void messagesNeverEchoTheValue() {
        String secretValue = "sekrit-token-with-a-space in-it";
        assertFalse(CredentialRules.checkPassword(secretValue).orElseThrow()
                .contains(secretValue));
        assertFalse(CredentialRules.checkSaslUsername(secretValue).orElseThrow()
                .contains(secretValue));

        String secretValueWithNul = "sekrit-token-with-a-nul\0in-it";
        assertFalse(CredentialRules.checkSaslPassword(secretValueWithNul).orElseThrow()
                .contains(secretValueWithNul));

        String passLine = "PASS oauth:sekrit-token-123";
        assertFalse(CredentialRules.checkPassword(passLine).orElseThrow()
                .contains("sekrit-token-123"));
    }
}
