package io.github.aindriub.ircweb.irc;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.aindriub.ircweb.security.SecretCodec;
import io.github.aindriub.ircweb.store.ProfileEntity;
import io.github.aindriub.ircweb.store.ProfileRepository;
import io.github.aindriub.ircweb.store.ServerRepository;
import io.github.aindriub.ircweb.store.ServerEntity;

/**
 * The server directory and the profiles attached to it.
 *
 * <p>servers.yml seeds an empty database and is then left alone. Re-reading it on
 * every start would be simpler and would silently undo every edit made through the
 * UI, which is the kind of simple that costs an afternoon.
 */
@Service
public class DirectoryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectoryService.class);

    private final ServerRepository servers;
    private final ProfileRepository profiles;
    private final ServerDirectory seed;
    private final SecretCodec codec;

    public DirectoryService(ServerRepository servers, ProfileRepository profiles,
            ServerDirectory seed, SecretCodec codec) {
        this.servers = servers;
        this.profiles = profiles;
        this.seed = seed;
        this.codec = codec;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedIfEmpty() {
        if (servers.count() > 0) {
            return;
        }
        for (IrcServer server : seed.getServers()) {
            servers.save(new ServerEntity(server.id(), server.name(), server.host(),
                    server.port(), server.tls(), server.sasl(), server.registered(),
                    server.features(), server.notes(), true));
        }
        LOGGER.info("Seeded the directory with {} servers from servers.yml",
                seed.getServers().size());
    }

    public List<IrcServer> list() {
        return servers.findAllByOrderByNameAsc().stream().map(DirectoryService::toRecord).toList();
    }

    public Optional<IrcServer> byId(String id) {
        return servers.findById(id).map(DirectoryService::toRecord);
    }

    @Transactional
    public IrcServer create(ServerUpsert request) {
        String id = request.id() == null || request.id().isBlank()
                ? slug(request.name()) : request.id();
        if (servers.existsById(id)) {
            throw new IllegalArgumentException("a server with id '" + id + "' already exists");
        }
        ServerEntity entity = new ServerEntity(id, request.name(), request.host(),
                request.port(), request.tls(), request.sasl(), request.registered(),
                request.features(), request.notes(), false);
        return toRecord(servers.save(entity));
    }

    @Transactional
    public IrcServer update(String id, ServerUpsert request) {
        ServerEntity entity = servers.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("no such server: " + id));
        entity.setName(request.name());
        entity.setHost(request.host());
        entity.setPort(request.port());
        entity.setTls(request.tls());
        entity.setSasl(request.sasl());
        entity.setRegistered(request.registered());
        entity.setFeatures(request.features());
        entity.setNotes(request.notes());
        return toRecord(servers.save(entity));
    }

    @Transactional
    public void delete(String id) {
        if (!servers.existsById(id)) {
            throw new IllegalArgumentException("no such server: " + id);
        }
        // The profile holds credentials for a server that is about to stop existing.
        // Leaving them behind would be an orphaned secret nobody remembers giving.
        profiles.deleteByServerId(id);
        servers.deleteById(id);
    }

    public ServerProfile profile(String serverId) {
        return profiles.findByServerId(serverId)
                .map(DirectoryService::toRecord)
                .orElseGet(() -> ServerProfile.empty(serverId));
    }

    @Transactional
    public ServerProfile saveProfile(String serverId, ProfileUpdate update) {
        if (!servers.existsById(serverId)) {
            throw new IllegalArgumentException("no such server: " + serverId);
        }
        ProfileEntity entity = profiles.findByServerId(serverId)
                .orElseGet(() -> new ProfileEntity(serverId));

        entity.setNick(trimToNull(update.nick()));
        entity.setUsername(trimToNull(update.username()));
        entity.setRealname(trimToNull(update.realname()));
        entity.setSaslUsername(trimToNull(update.saslUsername()));
        entity.setChannels(update.channels());
        entity.setPasswordCipher(
                applySecret(update.password(), entity.getPasswordCipher()));
        entity.setSaslPasswordCipher(
                applySecret(update.saslPassword(), entity.getSaslPasswordCipher()));
        entity.setUpdatedAt(Instant.now());

        return toRecord(profiles.save(entity));
    }

    /**
     * Null leaves the stored secret alone, empty clears it, anything else replaces
     * it. The form never shows what is stored, so "unchanged" has to be expressible
     * or editing a nick would wipe a password.
     */
    private String applySecret(String submitted, String existing) {
        if (submitted == null) {
            return existing;
        }
        return submitted.isEmpty() ? null : codec.encrypt(submitted);
    }

    /**
     * The decrypted credentials, for making a connection. Deliberately not on the
     * record the API returns.
     */
    public Credentials credentials(String serverId) {
        return profiles.findByServerId(serverId)
                .map(p -> new Credentials(codec.decrypt(p.getPasswordCipher()),
                        p.getSaslUsername(), codec.decrypt(p.getSaslPasswordCipher())))
                .orElse(new Credentials(null, null, null));
    }

    public record Credentials(String password, String saslUsername, String saslPassword) {
    }

    private static IrcServer toRecord(ServerEntity e) {
        return new IrcServer(e.getId(), e.getName(), e.getHost(), e.getPort(), e.isTls(),
                e.isSasl(), e.isRegistered(), List.copyOf(e.getFeatures()), e.getNotes());
    }

    private static ServerProfile toRecord(ProfileEntity e) {
        return new ServerProfile(e.getServerId(), e.getNick(), e.getUsername(), e.getRealname(),
                e.getSaslUsername(), List.copyOf(e.getChannels()),
                e.getPasswordCipher() != null, e.getSaslPasswordCipher() != null,
                e.getUpdatedAt());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String slug(String name) {
        String slug = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("could not derive an id from the name");
        }
        return slug;
    }
}
