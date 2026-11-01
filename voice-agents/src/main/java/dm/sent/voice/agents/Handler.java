package dm.sent.voice.agents;

import dm.sent.voice.agents.frames.ConnectionReady;
import dm.sent.voice.agents.frames.ErrorFrame;
import dm.sent.voice.agents.frames.SessionEnd;
import dm.sent.voice.agents.frames.ToolResult;
import dm.sent.voice.agents.frames.UnknownFrame;
import dm.sent.voice.agents.frames.UserSaid;

/**
 * What an agent does on its calls. Implement the methods you need; each has a default that does
 * nothing. A session's handlers run one after another, in the order Sent's frames arrived, on the
 * session's own chain; different sessions run at the same time.
 */
public interface Handler {
    /** A connection to Sent is ready for sessions. */
    default void onConnected(ConnectionReady ready) {}

    /**
     * A call reached the agent. In a text session, {@code say} to speak; an audio session waits for
     * audio.
     */
    default void onSessionStart(Session session) {}

    /**
     * Text sessions: the caller said something. With {@code correction} true it replaces the
     * previous one.
     */
    default void onUserSaid(Session session, UserSaid said) {}

    /**
     * Audio sessions: the caller's audio, 16-bit little-endian mono PCM at {@code
     * session.sampleRate()}.
     */
    default void onAudio(Session session, byte[] samples) {}

    /** Sent answered a tool call. The call's waiting future gets the same result. */
    default void onToolResult(Session session, ToolResult result) {}

    /** A session frame this SDK version does not know. It was counted; nothing else is needed. */
    default void onFrame(Session session, UnknownFrame frame) {}

    /** The call left the agent. {@code end.reason()} says why. */
    default void onSessionEnd(Session session, SessionEnd end) {}

    /**
     * The session's connection was lost and it could not be resumed: Sent ends the call with
     * agent_unreachable. No session.end follows.
     */
    default void onSessionLost(Session session) {}

    /** Sent refused a frame; the connection stays open. */
    default void onError(ErrorFrame error) {}
}
