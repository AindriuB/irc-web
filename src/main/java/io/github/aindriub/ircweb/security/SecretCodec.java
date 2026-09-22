package io.github.aindriub.ircweb.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts the IRC and SASL passwords held in the database.
 *
 * <p>This is not protection against someone who has the running application - they
 * can simply connect and use the credentials. It is protection against the database
 * file alone: a copied {@code irc-web.mv.db}, a backup, a stray volume. Those travel
 * far more easily than a running process does.
 *
 * <p>The key comes from {@code IRC_WEB_SECRET_KEY} when set. Otherwise one is
 * generated on first start and written beside the database with owner-only
 * permissions, so the zero-configuration case still encrypts rather than silently
 * storing plaintext. Losing the key makes stored secrets unreadable; they are
 * re-enterable, so that is an inconvenience rather than a loss.
 */
@Component
public class SecretCodec {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretCodec.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCodec(@Value("${irc-web.secret-key:}") String configuredKey,
            @Value("${irc-web.data-dir:./data}") String dataDir) throws Exception {
        this.key = resolveKey(configuredKey, Path.of(dataDir));
    }

    private static SecretKey resolveKey(String configured, Path dataDir) throws Exception {
        if (configured != null && !configured.isBlank()) {
            byte[] decoded = Base64.getDecoder().decode(configured.trim());
            if (decoded.length != KEY_BITS / 8) {
                throw new IllegalStateException(
                        "irc-web.secret-key must be " + KEY_BITS + " bits, base64 encoded");
            }
            return new SecretKeySpec(decoded, "AES");
        }

        Files.createDirectories(dataDir);
        Path keyFile = dataDir.resolve("secret.key");
        if (Files.exists(keyFile)) {
            return new SecretKeySpec(readKey(keyFile), "AES");
        }

        byte[] fresh = new byte[KEY_BITS / 8];
        new SecureRandom().nextBytes(fresh);
        writeKey(keyFile, fresh);

        LOGGER.info("Generated a secret key at {}. Back it up with the database, or set "
                + "IRC_WEB_SECRET_KEY; without it the stored passwords cannot be read back.",
                keyFile);
        return new SecretKeySpec(fresh, "AES");
    }

    /**
     * Creates the file owner-only in one step.
     *
     * <p>Writing first and restricting afterwards leaves the key world-readable for
     * however long that takes, which is short but not zero, and is exactly the sort
     * of window that is only ever noticed after it matters. The permissions are a
     * creation attribute here, so the file never exists in a readable state.
     */
    private static void writeKey(Path keyFile, byte[] key) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(key);
        Set<PosixFilePermission> ownerOnly =
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        try {
            Files.write(
                    Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(ownerOnly)),
                    encoded.getBytes(StandardCharsets.UTF_8));
        } catch (UnsupportedOperationException e) {
            // A filesystem without POSIX permissions, such as Windows. Say so
            // rather than leaving the caller to assume the key is protected.
            LOGGER.warn("This filesystem does not support POSIX permissions, so {} is created "
                    + "with whatever the default allows. Restrict it by hand, or set "
                    + "IRC_WEB_SECRET_KEY and keep the key out of the data directory.", keyFile);
            Files.write(keyFile, encoded.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Reads and validates a key file.
     *
     * <p>Validated for the same reason the configured key is: a truncated or
     * corrupted file otherwise produces a key of the wrong size, and the failure
     * surfaces as decrypt returning null - which reads as "re-enter your password"
     * - while encrypt throws. Failing at startup names the real problem once
     * instead of presenting it as a dozen small mysteries later.
     */
    private static byte[] readKey(Path keyFile) throws Exception {
        String contents = new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).trim();
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(contents);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(keyFile + " is not valid base64. Restore it from a "
                    + "backup, or delete it and re-enter the stored passwords.", e);
        }
        if (decoded.length != KEY_BITS / 8) {
            throw new IllegalStateException(keyFile + " holds a " + (decoded.length * 8)
                    + " bit key; " + KEY_BITS + " is required. It is truncated or corrupt. "
                    + "Restore it from a backup, or delete it and re-enter the stored "
                    + "passwords.");
        }
        return decoded;
    }

    /**
     * @return ciphertext, or null for null input, so "no secret" stays distinct from
     *         "a secret that happens to be empty"
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("could not encrypt a stored secret", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Almost always a changed or lost key rather than corruption. Re-entering
            // the password fixes it, so say so rather than failing the whole request.
            LOGGER.warn("Could not decrypt a stored secret; it needs entering again", e);
            return null;
        }
    }
}
