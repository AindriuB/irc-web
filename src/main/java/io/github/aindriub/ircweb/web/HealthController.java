package io.github.aindriub.ircweb.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.aindriub.ircweb.irc.DirectoryService;

/**
 * A liveness probe that does not require signing in.
 *
 * <p>It reports the number of servers in the directory rather than a bare "UP",
 * because a probe should distinguish an application that started from one that
 * started and loaded its configuration. Those are different failures and only the
 * second is worth restarting for.
 *
 * <p>The count is the only thing exposed. It reveals nothing a stranger could use,
 * where the directory itself carries hostnames and notes.
 */
@RestController
public class HealthController {

    private final DirectoryService directory;

    public HealthController(DirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "servers", directory.list().size());
    }
}
