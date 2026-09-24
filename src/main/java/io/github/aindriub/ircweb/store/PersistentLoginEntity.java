package io.github.aindriub.ircweb.store;

import java.time.LocalDateTime;

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
 *
 * <p>{@code lastUsed} is a {@link LocalDateTime}, not an {@link java.time.Instant}:
 * Hibernate maps an {@code Instant} to {@code timestamp with time zone}, but
 * {@code JdbcTokenRepositoryImpl}'s own SQL declares the column a plain
 * {@code timestamp}. A {@code LocalDateTime} matches that, so the table Hibernate
 * creates is the one the repository's queries expect.
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
    private LocalDateTime lastUsed;

    protected PersistentLoginEntity() {
    }

    public String getUsername() { return username; }
    public String getSeries() { return series; }
    public String getToken() { return token; }
    public LocalDateTime getLastUsed() { return lastUsed; }
}
