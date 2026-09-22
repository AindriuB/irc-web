package io.github.aindriub.ircweb.irc;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The directory of servers to test against, loaded from config/servers.yml.
 *
 * <p>Kept as data rather than code so that adding a network to test against is an
 * edit to a list, and so the reasoning about each one lives next to its address.
 */
@Component
@ConfigurationProperties(prefix = "irc")
public class ServerDirectory {

    private List<IrcServer> servers = List.of();

    public List<IrcServer> getServers() {
        return servers;
    }

    public void setServers(List<IrcServer> servers) {
        this.servers = servers;
    }

    public Optional<IrcServer> byId(String id) {
        return servers.stream().filter(s -> s.id().equals(id)).findFirst();
    }
}
