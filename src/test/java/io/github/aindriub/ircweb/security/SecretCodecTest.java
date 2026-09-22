package io.github.aindriub.ircweb.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretCodecTest {

    @Test
    @DisplayName("a secret survives a round trip")
    void roundTrips(@TempDir Path dir) throws Exception {
        SecretCodec codec = new SecretCodec("", dir.toString());

        assertEquals("hunter2", codec.decrypt(codec.encrypt("hunter2")));
        assertNull(codec.encrypt(null), "null is not a secret and should stay null");
        assertNull(codec.decrypt(null));
    }

    @Test
    @DisplayName("the same plaintext encrypts differently every time")
    void usesAFreshIv(@TempDir Path dir) throws Exception {
        SecretCodec codec = new SecretCodec("", dir.toString());

        // A fixed IV would make equal passwords visibly equal in the database.
        assertNotEquals(codec.encrypt("same"), codec.encrypt("same"));
    }

    @Test
    @DisplayName("the generated key file is never world readable, even briefly")
    void keyFileIsOwnerOnlyFromTheStart(@TempDir Path dir) throws Exception {
        new SecretCodec("", dir.toString());

        Path keyFile = dir.resolve("secret.key");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(keyFile);

        // Created with the permissions rather than chmod'd afterwards, so there is
        // no window in which the key sits readable on disk.
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                permissions, "the key file should be owner-only");
    }

    @Test
    @DisplayName("a key file is reused, so restarting does not lose what is stored")
    void reusesAnExistingKey(@TempDir Path dir) throws Exception {
        String ciphertext = new SecretCodec("", dir.toString()).encrypt("remembered");

        SecretCodec afterRestart = new SecretCodec("", dir.toString());

        assertEquals("remembered", afterRestart.decrypt(ciphertext));
    }

    @Test
    @DisplayName("a truncated key file fails at startup rather than silently later")
    void rejectsATruncatedKeyFile(@TempDir Path dir) throws Exception {
        new SecretCodec("", dir.toString());
        Path keyFile = dir.resolve("secret.key");
        byte[] half = new byte[16];
        new SecureRandom().nextBytes(half);
        Files.write(keyFile, Base64.getEncoder().encodeToString(half)
                .getBytes(StandardCharsets.UTF_8));

        // Left unchecked this surfaces as decrypt returning null, which reads as
        // "re-enter your password", while encrypt throws. One clear failure at
        // startup beats a dozen small mysteries afterwards.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new SecretCodec("", dir.toString()));
        assertTrue(failure.getMessage().contains("128 bit"), failure.getMessage());
    }

    @Test
    @DisplayName("a key file that is not base64 fails with a message that says so")
    void rejectsGarbage(@TempDir Path dir) throws Exception {
        new SecretCodec("", dir.toString());
        Files.write(dir.resolve("secret.key"), "not a key".getBytes(StandardCharsets.UTF_8));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new SecretCodec("", dir.toString()));
        assertTrue(failure.getMessage().contains("base64"), failure.getMessage());
    }

    @Test
    @DisplayName("a configured key of the wrong size is refused")
    void rejectsAShortConfiguredKey(@TempDir Path dir) {
        byte[] tooShort = new byte[16];
        String encoded = Base64.getEncoder().encodeToString(tooShort);

        assertThrows(IllegalStateException.class,
                () -> new SecretCodec(encoded, dir.toString()));
    }

    @Test
    @DisplayName("a configured key is used, and no file is written")
    void prefersTheConfiguredKey(@TempDir Path dir) throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);

        SecretCodec codec = new SecretCodec(encoded, dir.toString());

        assertEquals("kept", codec.decrypt(codec.encrypt("kept")));
        assertTrue(Files.notExists(dir.resolve("secret.key")),
                "a configured key should not also be written to disk");
    }

    @Test
    @DisplayName("ciphertext from a different key reads as needing re-entry, not a crash")
    void wrongKeyReturnsNull(@TempDir Path first, @TempDir Path second) throws Exception {
        String ciphertext = new SecretCodec("", first.toString()).encrypt("secret");

        // Losing the key is recoverable - the passwords can be entered again - so
        // this must not take down every request that touches a profile.
        assertNull(new SecretCodec("", second.toString()).decrypt(ciphertext));
    }
}
