package io.github.aindriub.ircweb.irc;

import java.util.List;

/**
 * Anything sent to the browser.
 *
 * <p>One shape with nullable fields rather than a type per event: the browser
 * switches on {@code type} either way, and a single record keeps the JSON contract
 * visible in one place.
 */
public record OutboundEvent(
        String type,
        String state,
        String detail,
        String target,
        String sender,
        String text,
        Boolean self,
        String kind,
        String channel,
        String nick,
        String reason,
        String topic,
        String direction,
        String line,
        List<String> channels,
        List<Member> members) {

    /**
     * One person in a channel.
     *
     * @param prefix the status character a client shows, such as {@code @}
     */
    public record Member(String nick, String prefix, boolean operator) {
    }

    public static OutboundEvent status(String state, String detail) {
        return status(state, detail, null);
    }

    /**
     * @param nick the nick actually in use, which the browser needs in order to spot
     *             a mention of itself. It is not always the one that was asked for.
     */
    public static OutboundEvent status(String state, String detail, String nick) {
        return new OutboundEvent("status", state, detail, null, null, null, null, null, null,
                nick, null, null, null, null, null, null);
    }

    public static OutboundEvent message(String target, String sender, String text,
            boolean self) {
        return new OutboundEvent("message", null, null, target, sender, text, self, null, null,
                null, null, null, null, null, null, null);
    }

    public static OutboundEvent notice(String target, String sender, String text) {
        return new OutboundEvent("notice", null, null, target, sender, text, false, null, null,
                null, null, null, null, null, null, null);
    }

    public static OutboundEvent presence(String kind, String channel, String nick,
            String reason) {
        return new OutboundEvent("presence", null, null, null, null, null, null, kind, channel,
                nick, reason, null, null, null, null, null);
    }

    public static OutboundEvent channels(List<String> channels) {
        return new OutboundEvent("channels", null, null, null, null, null, null, null, null,
                null, null, null, null, null, channels, null);
    }

    public static OutboundEvent names(String channel, String topic, List<Member> members) {
        return new OutboundEvent("names", null, null, null, null, null, null, null, channel,
                null, null, topic, null, null, null, members);
    }

    public static OutboundEvent raw(String direction, String line) {
        return new OutboundEvent("raw", null, null, null, null, null, null, null, null, null,
                null, null, direction, line, null, null);
    }

    /**
     * A numeric reply worth showing a human: a WHOIS line, an error, a topic.
     */
    public static OutboundEvent server(String numeric, String text) {
        return new OutboundEvent("server", numeric, text, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    /**
     * Brackets a replay so the browser can empty what it already has.
     *
     * <p>Without it, reattaching after a dropped socket would append a second copy
     * of every message still in the backlog.
     *
     * @param state {@code start} or {@code end}
     */
    public static OutboundEvent replay(String state) {
        return new OutboundEvent("replay", state, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    /**
     * Which network a browser has just been attached to, so the connect form can
     * show the session that was already running rather than an empty one.
     *
     * @param serverId carried in {@code state}, which is where the browser already
     *                 looks for what a status line is about
     */
    public static OutboundEvent attached(String serverId, String nick) {
        return new OutboundEvent("attached", serverId, null, null, null, null, null, null, null,
                nick, null, null, null, null, null, null);
    }

    public static OutboundEvent error(String detail) {
        return new OutboundEvent("error", null, detail, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
