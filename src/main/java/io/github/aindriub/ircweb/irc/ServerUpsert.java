package io.github.aindriub.ircweb.irc;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * A directory entry as submitted by the browser.
 */
public record ServerUpsert(
        @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,39}",
                message = "id must be lowercase letters, digits and hyphens")
        String id,

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "host is required")
        String host,

        @Min(value = 1, message = "port must be between 1 and 65535")
        @Max(value = 65535, message = "port must be between 1 and 65535")
        int port,

        boolean tls,
        boolean sasl,
        boolean registered,
        List<String> exercises,
        String notes) {
}
