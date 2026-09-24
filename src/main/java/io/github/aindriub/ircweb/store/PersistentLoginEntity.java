package io.github.aindriub.ircweb.store;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A remember-me series, as {@code JdbcTokenRepositoryImpl} reads and writes it.
 *
 * <p>Nothing in this application ever loads or saves one of these through JPA:
 * {@code JdbcTokenRepositoryImpl} talks to the table directly with its own SQL,
 * column names and all. This class exists only so Hibernate's {@code ddl-auto:
 * update} creates the table on startup - the schema this application already
 * uses everywhere else - instead of the token repository creating it with
 * {@code setCreateTableOnStartup(true)}, which fails the second time the
 * application starts because the table is already there.
 */
@Entity
@Table(name = "persistent_logins")
public class PersistentLoginEntity {

    @Column(nullable = false, length = 64)
    private String username;

    @Id
    @Column(length = 64)
    private String series;

    @Column(nullable = false, length = 64)
    private String token;

    @Column(name = "last_used", nullable = false)
    private Instant lastUsed;

    protected PersistentLoginEntity() {
    }

    public String getUsername() { return username; }
    public String getSeries() { return series; }
    public String getToken() { return token; }
    public Instant getLastUsed() { return lastUsed; }
}
