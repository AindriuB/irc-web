package io.github.aindriub.ircweb.store;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Someone allowed to use this application.
 *
 * <p>The column holds a bcrypt hash. Nothing anywhere stores or can recover the
 * password itself, which is the point of using one.
 */
@Entity
@Table(name = "app_user")
public class AppUserEntity {

    @Id
    private String username;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    private Instant createdAt;

    /**
     * Set when the password was generated for us rather than chosen. Drives the
     * nag in the UI, because a printed-once password that is never changed is the
     * usual way these end up effectively unauthenticated.
     */
    private boolean generatedPassword;

    protected AppUserEntity() {
    }

    public AppUserEntity(String username, String passwordHash, boolean generatedPassword) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.generatedPassword = generatedPassword;
        this.createdAt = Instant.now();
    }

    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isGeneratedPassword() { return generatedPassword; }

    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setGeneratedPassword(boolean generated) { this.generatedPassword = generated; }
}
