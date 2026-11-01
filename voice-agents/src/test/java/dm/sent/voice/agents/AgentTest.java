// The agent's bookkeeping around a connection, without a socket: which errors refuse a resume, when
// the
// backoff starts over, what a lost session's chain sees, and what a draining connection reports
// when it closes.
package dm.sent.voice.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dm.sent.voice.agents.frames.ConnectionReady;
import dm.sent.voice.agents.frames.ErrorFrame;
import dm.sent.voice.agents.frames.Protocol;
import dm.sent.voice.agents.frames.SessionStart;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class AgentTest {
    static final class Quiet implements AgentLogger {
        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {}

        @Override
        public void error(String message) {}
    }

    static final AgentLogger SILENT = new Quiet();

    /**
     * A connection nothing answers: the handshake times out, so a dial never reaches Sent from a
     * test.
     */
    static Options options() {
        return new Options("k", "agent_1")
                .withUrl("ws://10.255.255.1:9/v1/agents/connect")
                .withLogger(SILENT);
    }

    static SessionStart start(boolean resumable) {
        ObjectNode data = Protocol.object();
        data.put("type", "session.start");
        data.put("session_id", "vs_1");
        data.put("call_id", "call_1");
        data.put("agent_id", "agent_1");
        data.put("direction", "inbound");
        data.set("from", SessionTest.party("number", "+14155550100"));
        data.set("to", SessionTest.party("agent", "agent_1"));
        data.put("session_type", "text");
        data.put("started_at", "2026-10-31T00:00:00Z");
        if (resumable) {
            data.set("resume", SessionTest.resume('a'));
        }
        return (SessionStart) Protocol.decode(data);
    }

    static ErrorFrame error(String frameType, String code) {
        ObjectNode data = Protocol.object();
        data.put("type", "error");
        data.put("session_id", "vs_1");
        ObjectNode error = Protocol.object();
        error.put("code", code);
        error.put("message", "no");
        data.set("error", error);
        if (frameType != null) {
            data.put("frame_type", frameType);
        }
        return (ErrorFrame) Protocol.decode(data);
    }

    record Resuming(Agent agent, Connection connection, Session session) {}

    static Resuming resuming() {
        Agent agent = new Agent(options(), new Handler() {});
        Connection connection = new Connection(new ArrayList<>(), SILENT);
        connection.ready = true;
        Session session = new Session(start(true), connection);
        session.resuming = true;
        agent.live.put(session.id(), session);
        connection.sessions.put(session.id(), session);
        agent.connections.add(connection);
        return new Resuming(agent, connection, session);
    }

    @Test
    void onlyAnErrorAboutTheResumeRefusesIt() {
        Resuming fixture = resuming();
        fixture.agent().failed(fixture.connection(), error("agent.say", "frame_not_allowed"));
        assertTrue(
                fixture.session().resuming
                        && fixture.connection().sessions.containsKey(fixture.session().id())
                        && !fixture.session().lost());
        fixture.agent().failed(fixture.connection(), error("session.resume", "resume_failed"));
        assertTrue(
                fixture.session().lost()
                        && !fixture.connection().sessions.containsKey(fixture.session().id())
                        && !fixture.agent().live.containsKey(fixture.session().id()));
        fixture.session().chain.join();
        fixture.agent().stop();
    }

    @Test
    void aFullConnectionMovesTheResumeElsewhere() {
        Resuming fixture = resuming();
        fixture.agent().failed(fixture.connection(), error(null, "connection_full"));
        assertFalse(fixture.session().lost());
        assertFalse(fixture.connection().sessions.containsKey(fixture.session().id()));
        List<Connection> opened = new ArrayList<>();
        for (Connection connection : fixture.agent().connections) {
            if (connection != fixture.connection()) {
                opened.add(connection);
            }
        }
        assertEquals(1, opened.size());
        assertEquals(List.of(fixture.session()), opened.get(0).pending);
        fixture.agent().stop();
    }

    @Test
    void theBackoffStartsOverAfterAHeartbeatNotOnReady() {
        Agent agent = new Agent(options(), new Handler() {});
        Connection connection = new Connection(new ArrayList<>(), SILENT);
        agent.connections.add(connection);
        agent.attempt = 3;
        agent.ready(
                connection,
                (ConnectionReady)
                        Protocol.decode(
                                "{\"type\":\"connection.ready\",\"protocol_version\":\"0.2.0\",\"heartbeat_interval_ms\":15000,\"max_sessions\":10}"));
        assertEquals(3, agent.attempt);
        agent.handle(connection, Protocol.decode("{\"type\":\"ping\",\"ts\":1}"));
        assertEquals(0, agent.attempt);
        agent.stop();
    }

    static final class Noticing implements Handler {
        final List<String> seen = new ArrayList<>();

        @Override
        public void onSessionStart(Session session) {
            try {
                session.hangup().join();
            } catch (CompletionException error) {
                if (error.getCause() instanceof SessionEndedException) {
                    seen.add("tool failed");
                }
            }
        }

        @Override
        public void onSessionLost(Session session) {
            seen.add("lost");
            throw new IllegalStateException("thrown by the handler");
        }
    }

    @Test
    void aLostSessionReachesItsHandlerAfterItsToolCallFails() throws InterruptedException {
        Noticing noticing = new Noticing();
        Agent agent = new Agent(options(), noticing);
        Connection connection = new Connection(new ArrayList<>(), SILENT);
        agent.connections.add(connection);
        agent.startSession(connection, start(true));
        assertEquals(1, agent.sessions().size());
        Session session = agent.sessions().get(0);
        Thread.sleep(50);
        agent.lost(session);
        session.chain.join();
        assertEquals(List.of("tool failed", "lost"), noticing.seen);
        assertTrue(agent.sessions().isEmpty());
        agent.stop();
    }

    @Test
    void aDrainingConnectionReportsItsLeftoverSessionsLostWhenItCloses()
            throws InterruptedException {
        Noticing noticing = new Noticing();
        Agent agent = new Agent(options(), noticing);
        Connection connection = new Connection(new ArrayList<>(), SILENT);
        connection.ready = true;
        connection.draining = true;
        agent.connections.add(connection);
        agent.startSession(connection, start(false));
        assertEquals(1, agent.sessions().size());
        Session session = agent.sessions().get(0);
        Thread.sleep(50);
        agent.closed(connection);
        session.chain.join();
        assertTrue(noticing.seen.contains("lost"));
        assertTrue(session.lost());
        assertTrue(agent.sessions().isEmpty());
        assertTrue(agent.connections.isEmpty());
        agent.stop();
    }
}
