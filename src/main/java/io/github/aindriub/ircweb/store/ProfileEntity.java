package io.github.aindriub.ircweb.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * How this client connects to one server: who to be, how to prove it, and what to
 * join. One per server, keyed by the server's id.
 *
 * <p>The two password columns hold ciphertext. They are never returned by the API -
 * callers are told whether a secret is set, not what it is - because this
 * application has no authentication of its own and anyone who can reach it could
 * otherwise read back every credential it has been given.
 */
@Entity
@Table(name = "server_profile")
public class ProfileEntity {

    @Id
    private String serverId;

    private String nick;
    private String username;
    private String realname;

    @Column(length = 2000)
    private String passwordCipher;

    private String saslUsername;

    @Column(length = 2000)
    private String saslPasswordCipher;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "profile_channels",
            joinColumns = @JoinColumn(name = "server_id"))
    @Column(name = "channel")
    private List<String> channels = new ArrayList<>();

    private Instant updatedAt;

    protected ProfileEntity() {
    }

    public ProfileEntity(String serverId) {
        this.serverId = serverId;
    }

    public String getServerId() { return serverId; }
    public String getNick() { return nick; }
    public String getUsername() { return username; }
    public String getRealname() { return realname; }
    public String getPasswordCipher() { return passwordCipher; }
    public String getSaslUsername() { return saslUsername; }
    public String getSaslPasswordCipher() { return saslPasswordCipher; }
    public List<String> getChannels() { return channels; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setNick(String nick) { this.nick = nick; }
    public void setUsername(String username) { this.username = username; }
    public void setRealname(String realname) { this.realname = realname; }
    public void setPasswordCipher(String cipher) { this.passwordCipher = cipher; }
    public void setSaslUsername(String saslUsername) { this.saslUsername = saslUsername; }
    public void setSaslPasswordCipher(String cipher) { this.saslPasswordCipher = cipher; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setChannels(List<String> channels) {
        this.channels = channels == null ? new ArrayList<>() : new ArrayList<>(channels);
    }
}
