package dev.nickyllo.betterbuild.core.platform;

/** Where the Architect's words go. The core never formats a Minecraft chat component. */
public interface ChatSink {

    /** Something the Architect says out loud, attributed to him. */
    void say(String message);

    /** A problem the player needs to act on. */
    void warn(String message);
}
