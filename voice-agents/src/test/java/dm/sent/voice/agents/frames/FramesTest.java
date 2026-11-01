// The generated frames against the protocol's own fixtures, copied here by the lane at render time.
package dm.sent.voice.agents.frames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FramesTest {
    private static final Path ROOT =
            Path.of(System.getProperty("sent.fixtures", "../tests/fixtures"));

    static List<String> fixtures(String kind) throws IOException {
        try (Stream<Path> files = Files.walk(ROOT.resolve(kind))) {
            return files.filter(file -> file.toString().endsWith(".json"))
                    .map(file -> ROOT.relativize(file).toString())
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    static List<String> valid() throws IOException {
        return fixtures("valid");
    }

    static List<String> invalid() throws IOException {
        return fixtures("invalid");
    }

    private static ObjectNode load(String file) throws IOException {
        return (ObjectNode) Protocol.parse(Files.readString(ROOT.resolve(file)));
    }

    @ParameterizedTest
    @MethodSource("valid")
    void validFramesRoundTrip(String file) throws IOException {
        ObjectNode data = load(file);
        Frame frame = Protocol.decode(data);
        assertFalse(frame instanceof UnknownFrame, file + " decoded as unknown");
        assertEquals(data.get("type").textValue(), frame.type());
        ObjectNode again = Protocol.encode(frame);
        for (Map.Entry<String, JsonNode> field : data.properties()) {
            assertEquals(
                    field.getValue(),
                    again.get(field.getKey()),
                    field.getKey() + " changed on the way back");
        }
        assertEquals(again, Protocol.encode(Protocol.decode(again)));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void invalidFramesAreRefused(String file) throws IOException {
        ObjectNode data = load(file);
        assertThrows(FrameException.class, () -> Protocol.decode(data));
    }

    @Test
    void fixturesArePresent() throws IOException {
        assertFalse(valid().isEmpty());
        assertFalse(invalid().isEmpty());
    }

    @Test
    void unknownFramesAndFieldsPass() {
        ObjectNode note = Protocol.object();
        note.put("type", "session.note");
        note.put("session_id", "vs_1");
        note.put("x", 1);
        UnknownFrame frame = assertInstanceOf(UnknownFrame.class, Protocol.decode(note));
        assertEquals("session.note", frame.type());
        assertEquals("vs_1", frame.sessionId());
        assertEquals(
                "{\"type\":\"session.note\",\"session_id\":\"vs_1\",\"x\":1}",
                Protocol.write(Protocol.encode(frame)));

        UserSaid said =
                assertInstanceOf(
                        UserSaid.class,
                        Protocol.decode(
                                "{\"type\":\"user.said\",\"session_id\":\"vs_1\",\"text\":\"hi\",\"mood\":\"good\"}"));
        assertFalse(said.correction());
        SessionEnd end =
                assertInstanceOf(
                        SessionEnd.class,
                        Protocol.decode(
                                "{\"type\":\"session.end\",\"session_id\":\"vs_1\",\"reason\":\"added_later\",\"duration_ms\":1}"));
        assertEquals("added_later", end.reason());
    }

    @Test
    void theProtocolFactsMatchTheMarks() {
        assertEquals("sent.voice.v1", Protocol.SUBPROTOCOL);
        assertEquals(32, Protocol.RETAINED_FRAMES);
        assertEquals("agent.say", AgentSay.TYPE);
        assertTrue(Protocol.UNCOUNTED.contains("session.start"));
        assertFalse(Protocol.UNCOUNTED.contains("agent.say"));
        assertEquals(Set.of("text"), Protocol.SESSION_TYPES.get("agent.say"));
        assertTrue(Protocol.FROM_AGENT.contains("pong"));
        assertTrue(Protocol.FROM_SENT.contains("ping"));
        assertTrue(Protocol.SESSION_END_REASONS.contains("caller_hung_up"));
    }

    @Test
    void encodeRefusesABadFrame() {
        assertThrows(FrameException.class, () -> Protocol.encode(AgentSay.of("nope", "hi")));
        assertThrows(
                FrameException.class, () -> Protocol.encode(ToolCall.of("vs_1", "t1", "transfer")));
        assertThrows(FrameException.class, () -> Protocol.decode("not json"));
    }

    @Test
    void encodeWritesTypeFirstLeavesNullsOutAndEscapesNothing() {
        String json = Protocol.encodeToString(AgentSay.of("vs_1", "hi, +1 <you>"));
        assertTrue(json.startsWith("{\"type\":\"agent.say\","), json);
        assertTrue(json.contains("\"hi, +1 <you>\""), json);
        String call = Protocol.encodeToString(ToolCall.of("vs_1", "t1", "hangup"));
        assertFalse(call.contains("arguments"), call);
    }
}
