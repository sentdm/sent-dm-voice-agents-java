// The session's bookkeeping, with a connection that records what it sends: counting both ways, the
// retained
// frames, what a resume sends again, and tool calls answered by their result.
package dm.sent.voice.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dm.sent.voice.agents.frames.AgentSay;
import dm.sent.voice.agents.frames.Frame;
import dm.sent.voice.agents.frames.FrameException;
import dm.sent.voice.agents.frames.Protocol;
import dm.sent.voice.agents.frames.SessionEnd;
import dm.sent.voice.agents.frames.SessionResume;
import dm.sent.voice.agents.frames.SessionResumed;
import dm.sent.voice.agents.frames.SessionStart;
import dm.sent.voice.agents.frames.ToolCall;
import dm.sent.voice.agents.frames.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SessionTest {
    static final class Recorder implements Sender {
        final List<Frame> frames = new ArrayList<>();
        final List<byte[]> audio = new ArrayList<>();

        @Override
        public void sendText(String type, String json) {
            frames.add(Protocol.decode(json));
        }

        @Override
        public void sendBytes(byte[] frame) {
            audio.add(frame);
        }
    }

    static SessionStart start(String sessionType, ObjectNode audio) {
        ObjectNode data = Protocol.object();
        data.put("type", "session.start");
        data.put("session_id", "vs_1");
        data.put("call_id", "call_1");
        data.put("agent_id", "agent_1");
        data.put("direction", "inbound");
        data.set("from", party("number", "+14155550100"));
        data.set("to", party("agent", "agent_1"));
        data.put("session_type", sessionType);
        data.put("started_at", "2026-10-31T00:00:00Z");
        data.set("resume", resume('a'));
        if (audio != null) {
            data.set("audio", audio);
        }
        return (SessionStart) Protocol.decode(data);
    }

    static SessionStart start() {
        return start("text", null);
    }

    static ObjectNode party(String kind, String value) {
        ObjectNode party = Protocol.object();
        party.put("kind", kind);
        party.put("value", value);
        return party;
    }

    static ObjectNode resume(char token) {
        ObjectNode resume = Protocol.object();
        resume.put("token", "vsr_" + String.valueOf(token).repeat(30));
        resume.put("window_ms", 15000);
        return resume;
    }

    static ObjectNode audio(int stream) {
        ObjectNode audio = Protocol.object();
        audio.put("stream", stream);
        return audio;
    }

    static SessionResumed resumed(long received, char token) {
        ObjectNode data = Protocol.object();
        data.put("type", "session.resumed");
        data.put("session_id", "vs_1");
        data.put("received", received);
        data.set("resume", resume(token));
        return (SessionResumed) Protocol.decode(data);
    }

    static SessionEnd end() {
        return (SessionEnd)
                Protocol.decode(
                        "{\"type\":\"session.end\",\"session_id\":\"vs_1\",\"reason\":\"caller_hung_up\",\"duration_ms\":10}");
    }

    static ToolResult ok(String callRef) {
        ObjectNode data = Protocol.object();
        data.put("type", "tool.result");
        data.put("session_id", "vs_1");
        data.put("call_ref", callRef);
        data.put("ok", true);
        return (ToolResult) Protocol.decode(data);
    }

    static List<String> texts(List<Frame> frames) {
        return frames.stream().map(frame -> ((AgentSay) frame).text()).collect(Collectors.toList());
    }

    @Test
    void countsBothWaysAndKeepsTheLatestFrames() {
        Recorder recorder = new Recorder();
        Session session = new Session(start(), recorder);
        assertTrue(session.resumable());
        session.read(
                Protocol.decode(
                        "{\"type\":\"user.said\",\"session_id\":\"vs_1\",\"text\":\"hi\"}"));
        session.read(
                Protocol.decode(
                        "{\"type\":\"session.resumed\",\"session_id\":\"vs_1\",\"received\":0}"));
        assertEquals(1, session.received());
        for (int i = 0; i < Protocol.RETAINED_FRAMES + 5; i++) {
            session.say("line " + i);
        }
        assertEquals(Protocol.RETAINED_FRAMES + 5, session.sent());
        assertEquals(Protocol.RETAINED_FRAMES + 5, recorder.frames.size());
    }

    @Test
    void aResumeSendsAgainWhatSentDidNotRead() {
        Recorder recorder = new Recorder();
        Session session = new Session(start(), recorder);
        session.say("one");
        session.say("two");
        session.say("three");
        SessionResume resume = session.resumeFrame();
        assertTrue(resume.token().startsWith("vsr_"));
        assertEquals(0, resume.received());
        session.resuming = true;
        assertEquals(2, session.resumed(resumed(1, 'b')));
        assertEquals(List.of("one", "two", "three", "two", "three"), texts(recorder.frames));
        assertTrue(session.token().startsWith("vsr_b"));
        assertEquals(3, session.sent());
    }

    @Test
    void aResumePastTheRetainedFramesHangsUp() {
        Recorder recorder = new Recorder();
        Session session = new Session(start(), recorder);
        for (int i = 0; i < Protocol.RETAINED_FRAMES + 3; i++) {
            session.say(Integer.toString(i));
        }
        session.resuming = true;
        assertEquals(1, session.resumed(resumed(1, 'c')));
        ToolCall call =
                assertInstanceOf(ToolCall.class, recorder.frames.get(recorder.frames.size() - 1));
        assertEquals("hangup", call.name());
    }

    @Test
    void toolCallsAreAnsweredByTheirResult() {
        Recorder recorder = new Recorder();
        Session session = new Session(start(), recorder);
        CompletableFuture<ToolResult> task = session.transfer("+14155550199");
        ToolCall call =
                assertInstanceOf(ToolCall.class, recorder.frames.get(recorder.frames.size() - 1));
        assertEquals("transfer", call.name());
        assertEquals(
                Protocol.parse("{\"to\":{\"kind\":\"number\",\"value\":\"+14155550199\"}}"),
                call.arguments());
        session.answerTool(ok(call.callRef()));
        assertTrue(task.join().ok());
    }

    @Test
    void aSessionThatEndedRefusesMore() {
        Session session = new Session(start(), new Recorder());
        CompletableFuture<ToolResult> task = session.hangup();
        session.finish(end());
        CompletionException failed = assertThrows(CompletionException.class, task::join);
        assertInstanceOf(SessionEndedException.class, failed.getCause());
        assertThrows(SessionEndedException.class, () -> session.say("too late"));
    }

    @Test
    void aFrameThatBreaksTheProtocolIsRefusedBeforeItCounts() {
        Recorder recorder = new Recorder();
        Session session = new Session(start(), recorder);
        assertThrows(FrameException.class, () -> session.say(""));
        CompletionException failed =
                assertThrows(CompletionException.class, () -> session.sendDtmf("xyz").join());
        assertInstanceOf(FrameException.class, failed.getCause());
        assertEquals(0, session.sent());
        assertTrue(recorder.frames.isEmpty());
    }

    @Test
    void framesSentDuringAResumeWaitForTheReplay() {
        Recorder recorder = new Recorder();
        Session session = new Session(start(), recorder);
        session.say("before");
        session.resuming = true;
        session.say("during");
        CompletableFuture<ToolResult> task = session.hangup();
        assertEquals(List.of("before"), texts(recorder.frames));
        assertEquals(3, session.sent());
        int sent = session.resumed(resumed(1, 'd'));
        assertEquals(2, sent);
        assertFalse(session.resuming);
        assertEquals("during", ((AgentSay) recorder.frames.get(1)).text());
        ToolCall call = assertInstanceOf(ToolCall.class, recorder.frames.get(2));
        assertEquals("hangup", call.name());
        session.answerTool(ok(call.callRef()));
        assertTrue(task.join().ok());
    }

    @Test
    void aResumePastWhatWasSentSendsNothingAgain() {
        Recorder recorder = new Recorder();
        Session session = new Session(start(), recorder);
        session.say("one");
        session.resuming = true;
        assertEquals(0, session.resumed(resumed(5, 'e')));
        assertEquals(5, session.sent());
    }

    @Test
    void audioDuringAResumeIsDroppedAndAfterTheEndRefused() {
        Recorder recorder = new Recorder();
        Session session = new Session(start("audio", audio(7)), recorder);
        session.resuming = true;
        session.sendAudio(new byte[640]);
        assertTrue(recorder.audio.isEmpty());
        session.resuming = false;
        session.finish(end());
        assertThrows(SessionEndedException.class, () -> session.sendAudio(new byte[640]));
    }

    @Test
    void aLostSessionFailsItsToolCallsAndRefusesMore() {
        Session session = new Session(start(), new Recorder());
        CompletableFuture<ToolResult> task = session.hangup();
        session.abandon();
        CompletionException failed = assertThrows(CompletionException.class, task::join);
        assertInstanceOf(SessionEndedException.class, failed.getCause());
        assertTrue(session.ended() && session.lost());
        assertThrows(SessionEndedException.class, () -> session.say("too late"));
    }

    @Test
    void audioGoesOutOnTheSessionStream() {
        Recorder recorder = new Recorder();
        Session session = new Session(start("audio", audio(7)), recorder);
        byte[] samples = new byte[640];
        java.util.Arrays.fill(samples, (byte) 1);
        session.sendAudio(samples);
        assertEquals(0, recorder.audio.get(0)[0]);
        assertEquals(7, recorder.audio.get(0)[1]);
        assertEquals(642, recorder.audio.get(0).length);
    }
}
