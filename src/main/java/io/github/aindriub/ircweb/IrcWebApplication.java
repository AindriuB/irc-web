package io.github.aindriub.ircweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A web front end for irc-client.
 *
 * <p>It exists to exercise the library against real servers in the way a real
 * application would: many concurrent connections, events arriving on Netty threads
 * and being fanned out to browsers, and connections that come and go.
 */
@SpringBootApplication
public class IrcWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(IrcWebApplication.class, args);
    }
}
