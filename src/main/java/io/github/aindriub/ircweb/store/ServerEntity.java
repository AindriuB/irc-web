package io.github.aindriub.ircweb.store;

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
 * A server in the directory.
 *
 * <p>The YAML file is the seed for an empty database, not the source of truth:
 * entries can be edited and added through the UI, and a file that is read on every
 * start would silently undo that.
 */
@Entity
@Table(name = "irc_server")
public class ServerEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int port;

    private boolean tls;
    private boolean sasl;
    private boolean registered;

    @Column(length = 4000)
    private String notes;

    /**
     * True for entries seeded from servers.yml. Only used to decide whether to
     * re-seed; a seeded entry is as editable and as deletable as any other.
     */
    private boolean builtin;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "irc_server_exercises",
            joinColumns = @JoinColumn(name = "server_id"))
    @Column(name = "exercise")
    private List<String> exercises = new ArrayList<>();

    protected ServerEntity() {
    }

    public ServerEntity(String id, String name, String host, int port, boolean tls,
            boolean sasl, boolean registered, List<String> exercises, String notes,
            boolean builtin) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.tls = tls;
        this.sasl = sasl;
        this.registered = registered;
        this.exercises = exercises == null ? new ArrayList<>() : new ArrayList<>(exercises);
        this.notes = notes;
        this.builtin = builtin;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public boolean isTls() { return tls; }
    public boolean isSasl() { return sasl; }
    public boolean isRegistered() { return registered; }
    public String getNotes() { return notes; }
    public boolean isBuiltin() { return builtin; }
    public List<String> getExercises() { return exercises; }

    public void setName(String name) { this.name = name; }
    public void setHost(String host) { this.host = host; }
    public void setPort(int port) { this.port = port; }
    public void setTls(boolean tls) { this.tls = tls; }
    public void setSasl(boolean sasl) { this.sasl = sasl; }
    public void setRegistered(boolean registered) { this.registered = registered; }
    public void setNotes(String notes) { this.notes = notes; }
    public void setExercises(List<String> exercises) {
        this.exercises = exercises == null ? new ArrayList<>() : new ArrayList<>(exercises);
    }
}
