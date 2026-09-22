package io.github.aindriub.ircweb.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;

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
            return new SecretKeySpec(Base64.getDecoder().decode(Files.readString(keyFile).trim()),
                    "AES");
        }

        byte[] fresh = new byte[KEY_BITS / 8];
        new SecureRandom().nextBytes(fresh);
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(fresh));
        try {
            Files.setPosixFilePermissions(keyFile,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            LOGGER.warn("Could not restrict permissions on {}", keyFile);
        }
        LOGGER.info("Generated a secret key at {}. Back it up with the database, or set "
                + "IRC_WEB_SECRET_KEY; without it the stored passwords cannot be read back.",
                keyFile);
        return new SecretKeySpec(fresh, "AES");
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
