// One session: a call while this agent is on it.
//
// A Session is what the handlers of an Agent receive. It carries what session.start said about the
// call, sends
// the agent's frames on whichever connection the session is on, keeps the latest counted frames so
// a resume can
// send again the ones Sent did not read, and turns a tool call into the tool.result that answers
// it.
package dm.sent.voice.agents;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dm.sent.voice.agents.frames.AgentClear;
import dm.sent.voice.agents.frames.AgentSay;
import dm.sent.voice.agents.frames.Frame;
import dm.sent.voice.agents.frames.Party;
import dm.sent.voice.agents.frames.Protocol;
import dm.sent.voice.agents.frames.SessionEnd;
import dm.sent.voice.agents.frames.SessionResume;
import dm.sent.voice.agents.frames.SessionResumed;
import dm.sent.voice.agents.frames.SessionStart;
import dm.sent.voice.agents.frames.ToolCall;
import dm.sent.voice.agents.frames.ToolResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The call the agent is on, and the ways to act on it. Created by the agent; its handlers run in
 * order.
 */
public final class Session {
    private final String id;
    private final String callId;
    private final String agentId;
    private final String direction;
    private final Party from;
    private final Party to;
    private final String sessionType;
    private final String language;
    private final ObjectNode context;
    private final String startedAt;
    private final int sampleRate;
    private final Long windowMs;
    private final SessionStart start;

    private Integer stream;
    private String token;
    private long received;
    private SessionEnd end;
    private boolean lost;

    /** The connection the session sends on; the agent moves it on a resume. */
    volatile Sender connection;

    /** A session.resume was sent on the current connection and Sent has not answered it yet. */
    volatile boolean resuming;

    /** The handlers run one after another on this chain. */
    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

    final Object chainGate = new Object();

    private final Object gate = new Object();
    private List<Frame> kept = new ArrayList<>();
    private long before;
    private int calls;
    private final Map<String, CompletableFuture<ToolResult>> pending = new HashMap<>();

    Session(SessionStart start, Sender connection) {
        this.start = start;
        id = start.sessionId();
        callId = start.callId();
        agentId = start.agentId();
        direction = start.direction();
        from = start.from();
        to = start.to();
        sessionType = start.sessionType();
        language = start.language();
        context = start.context() == null ? Protocol.object() : start.context().deepCopy();
        startedAt = start.startedAt();
        sampleRate = start.audio() == null ? 16000 : (int) (long) start.audio().sampleRate();
        stream = start.audio() == null ? null : (int) start.audio().stream();
        token = start.resume() == null ? null : start.resume().token();
        windowMs = start.resume() == null ? null : start.resume().windowMs();
        this.connection = connection;
    }

    // ----- what session.start said -----

    public String id() {
        return id;
    }

    public String callId() {
        return callId;
    }

    public String agentId() {
        return agentId;
    }

    /** {@code inbound} or {@code outbound}. */
    public String direction() {
        return direction;
    }

    public Party from() {
        return from;
    }

    public Party to() {
        return to;
    }

    /** {@code text} or {@code audio}. */
    public String sessionType() {
        return sessionType;
    }

    public String language() {
        return language;
    }

    /** What the customer's backend passed when it started an outbound call. */
    public ObjectNode context() {
        return context;
    }

    public String startedAt() {
        return startedAt;
    }

    /** Hz of the session's 16-bit mono PCM, both ways, in an audio session. */
    public int sampleRate() {
        return sampleRate;
    }

    /** What session.start said about the call. */
    public SessionStart start() {
        return start;
    }

    /**
     * The number in the header of the session's binary audio frames on its current connection, or
     * null.
     */
    public Integer stream() {
        synchronized (gate) {
            return stream;
        }
    }

    /** The latest resume token, or null when the session cannot be resumed. Keep it out of logs. */
    public String token() {
        synchronized (gate) {
            return token;
        }
    }

    public Long windowMs() {
        return windowMs;
    }

    /**
     * How many of Sent's counted frames for the session the agent has read: the {@code received} of
     * a resume.
     */
    public long received() {
        synchronized (gate) {
            return received;
        }
    }

    /** session.end, once it arrived. */
    public SessionEnd end() {
        synchronized (gate) {
            return end;
        }
    }

    /**
     * The connection was lost and the session could not be resumed: Sent ends the call without a
     * session.end.
     */
    public boolean lost() {
        synchronized (gate) {
            return lost;
        }
    }

    /** Whether the call has left the agent: session.end arrived, or the session was lost. */
    public boolean ended() {
        synchronized (gate) {
            return end != null || lost;
        }
    }

    /** Whether the session survives a lost connection: it has a resume token. */
    public boolean resumable() {
        return token() != null;
    }

    /** How many counted frames the agent has sent for the session. */
    public long sent() {
        synchronized (gate) {
            return before + kept.size();
        }
    }

    // ----- what the agent does on the call -----

    /** Text sessions: speak {@code text} to the caller, as one utterance. */
    public void say(String text) {
        say(text, null, null);
    }

    /**
     * Text sessions: speak {@code text} to the caller. Send a reply in pieces with {@code last}
     * false and finish with {@code last} true; the pieces are spoken as one utterance. Null takes
     * the protocol's default: true.
     */
    public void say(String text, Boolean last, Boolean interruptible) {
        send(AgentSay.of(id, text).withLast(last).withInterruptible(interruptible));
    }

    /** Drop the speech or audio the agent sent that Sent has not played yet. */
    public void clear() {
        send(AgentClear.of(id));
    }

    /**
     * Audio sessions: play {@code samples}, 16-bit little-endian mono PCM at the session's sample
     * rate. Send 20 ms per call, and no more than 200 ms.
     */
    public void sendAudio(byte[] samples) {
        if (ended()) {
            throw new SessionEndedException(id);
        }
        Integer current = stream();
        if (current == null) {
            throw new IllegalStateException("session " + id + " is a text session");
        }
        // The stream number belongs to the old connection; on the new one it may be another
        // caller's until
        // session.resumed assigns this session's. Audio is not counted, so there is nothing to send
        // again.
        if (resuming) {
            return;
        }
        connection.sendBytes(Audio.frame(current, samples));
    }

    /** Move the call to {@code to}. On success the session then ends with {@code transferred}. */
    public CompletableFuture<ToolResult> transfer(Party to) {
        ObjectNode party = Protocol.object();
        party.put("kind", to.kind());
        if (to.value() != null) {
            party.put("value", to.value());
        }
        ObjectNode arguments = Protocol.object();
        arguments.set("to", party);
        return tool("transfer", arguments);
    }

    /**
     * Move the call to an E.164 number. On success the session then ends with {@code transferred}.
     */
    public CompletableFuture<ToolResult> transfer(String number) {
        return transfer(Party.of("number").withValue(number));
    }

    /** End the call, after the speech the agent queued before it. */
    public CompletableFuture<ToolResult> hangup() {
        return tool("hangup", null);
    }

    /** Play {@code digits} on the call: 0 to 9, *, #, A to D, and w to wait half a second. */
    public CompletableFuture<ToolResult> sendDtmf(String digits) {
        ObjectNode arguments = Protocol.object();
        arguments.put("digits", digits);
        return tool("send_dtmf", arguments);
    }

    /** Put the caller on hold, or take them off it. */
    public CompletableFuture<ToolResult> hold(boolean held) {
        ObjectNode arguments = Protocol.object();
        arguments.put("held", held);
        return tool("hold", arguments);
    }

    /**
     * Call a tool by name and wait for its tool.result. The future fails with SessionEndedException
     * if the session ends first, and with FrameException when the call breaks the protocol.
     */
    public CompletableFuture<ToolResult> tool(String name, ObjectNode arguments) {
        if (ended()) {
            return CompletableFuture.failedFuture(new SessionEndedException(id));
        }
        CompletableFuture<ToolResult> completion = new CompletableFuture<>();
        String callRef;
        synchronized (gate) {
            callRef = "t" + (++calls);
            pending.put(callRef, completion);
        }
        try {
            send(ToolCall.of(id, callRef, name).withArguments(arguments));
        } catch (RuntimeException error) {
            synchronized (gate) {
                pending.remove(callRef);
            }
            completion.completeExceptionally(error);
        }
        return completion;
    }

    // ----- what the agent's connection does with the session -----

    /**
     * Sends a frame for the session on its connection, keeping it if it counts. A frame that breaks
     * the protocol is refused before it counts. While a resume is unanswered the frame is kept and
     * not sent: the replay after session.resumed sends it, once, on the connection the session is
     * on. Keeping and sending happen under one lock, so the wire order is the kept order.
     */
    void send(Frame frame) {
        if (ended()) {
            throw new SessionEndedException(id);
        }
        String json = Protocol.encodeToString(frame);
        synchronized (gate) {
            keep(frame);
            if (resuming) {
                return;
            }
            connection.sendText(frame.type(), json);
        }
    }

    private void keep(Frame frame) {
        if (Protocol.UNCOUNTED.contains(frame.type())) {
            return;
        }
        synchronized (gate) {
            kept.add(frame);
            if (kept.size() > Protocol.RETAINED_FRAMES) {
                kept.remove(0);
                before++;
            }
        }
    }

    /** Counts one of Sent's frames for the session, as Counting frames in docs/protocol.md says. */
    void read(Frame frame) {
        if (!Protocol.UNCOUNTED.contains(frame.type())) {
            synchronized (gate) {
                received++;
            }
        }
    }

    SessionResume resumeFrame() {
        synchronized (gate) {
            if (token == null) {
                throw new IllegalStateException("session " + id + " has no resume token");
            }
            return SessionResume.of(id, token, received);
        }
    }

    /**
     * Takes the answer to a session.resume and sends again, on the session's connection, the frames
     * Sent did not read, or a hangup when Sent needs one the session no longer keeps. Returns how
     * many it sent. The replay and the end of the resume happen under the session's lock, so
     * nothing a handler sends can slip between.
     */
    int resumed(SessionResumed frame) {
        synchronized (gate) {
            token = frame.resume() == null ? null : frame.resume().token();
            if (frame.audio() != null) {
                stream = (int) frame.audio().stream();
            }
            // Without a next token the session ended while it was detached; its replay ends with
            // session.end.
            if (frame.resume() == null) {
                resuming = false;
                return 0;
            }
            List<Frame> again;
            if (frame.received() < before) {
                again = List.of(ToolCall.of(id, "t" + (++calls), "hangup"));
            } else {
                // A received past what was sent is Sent's mistake; there is nothing to send again
                // for it.
                int read = (int) Math.min(frame.received() - before, kept.size());
                again = new ArrayList<>(kept.subList(read, kept.size()));
            }
            before = frame.received();
            kept = new ArrayList<>();
            for (Frame frameAgain : again) {
                keep(frameAgain);
                connection.sendText(frameAgain.type(), Protocol.encodeToString(frameAgain));
            }
            resuming = false;
            return again.size();
        }
    }

    void answerTool(ToolResult result) {
        CompletableFuture<ToolResult> waiting;
        synchronized (gate) {
            waiting = pending.remove(result.callRef());
        }
        if (waiting != null) {
            waiting.complete(result);
        }
    }

    void finish(SessionEnd end) {
        synchronized (gate) {
            this.end = end;
        }
        failPending();
    }

    /**
     * The session is lost: no tool.result or session.end will come, so every waiting tool call
     * fails, and anything sent from now on is refused.
     */
    void abandon() {
        synchronized (gate) {
            lost = true;
            resuming = false;
        }
        failPending();
    }

    private void failPending() {
        List<CompletableFuture<ToolResult>> waiting;
        synchronized (gate) {
            waiting = new ArrayList<>(pending.values());
            pending.clear();
        }
        for (CompletableFuture<ToolResult> completion : waiting) {
            completion.completeExceptionally(new SessionEndedException(id));
        }
    }
}
