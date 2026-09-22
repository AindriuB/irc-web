package io.github.aindriub.ircweb.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import io.github.aindriub.ircweb.web.IrcWebSocketHandler;

/**
 * Plain WebSocket rather than STOMP over SockJS.
 *
 * <p>The browser here needs one socket carrying a handful of message types. STOMP
 * would add a broker, a client library and a subscription model to express the same
 * thing, and this project is meant to put the library under load, not to demonstrate
 * Spring messaging. Plain sockets also keep the front end dependency free, which
 * matters when the point is to run it anywhere quickly.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final IrcWebSocketHandler handler;

    public WebSocketConfig(IrcWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/irc").setAllowedOrigins("*");
    }
}
