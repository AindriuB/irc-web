package io.github.aindriub.ircweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * An IRC client that runs on a server and is used from a browser.
 *
 * <p>One process holds the connections; browsers attach to them over a websocket
 * and detach again. That is what lets a tab be closed without dropping off the
 * network, and it is why this is a server rather than a page.
 */
@SpringBootApplication
public class IrcWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(IrcWebApplication.class, args);
    }
}
