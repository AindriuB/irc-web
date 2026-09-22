package io.github.aindriub.ircweb.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.aindriub.ircweb.irc.IrcServer;
import io.github.aindriub.ircweb.irc.ServerDirectory;

/**
 * The server directory, so the front end can offer it as a list rather than asking
 * anyone to remember hostnames.
 */
@RestController
@RequestMapping("/api")
public class ServerDirectoryController {

    private final ServerDirectory directory;

    public ServerDirectoryController(ServerDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/servers")
    public List<IrcServer> servers() {
        return directory.getServers();
    }
}
