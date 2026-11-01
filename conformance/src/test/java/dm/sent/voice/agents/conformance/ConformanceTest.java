package dm.sent.voice.agents.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dm.sent.voice.agents.frames.Protocol;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ConformanceTest {
    private static List<String> shape(String text) {
        return ConformanceAgent.decide(text).stream()
                .map(
                        step -> {
                            if (step instanceof ConformanceAgent.Say say) {
                                return "say "
                                        + say.text()
                                        + (say.last() == null ? "" : " last=" + say.last());
                            }
                            ConformanceAgent.Tool tool = (ConformanceAgent.Tool) step;
                            return "tool "
                                    + tool.name()
                                    + " "
                                    + (tool.arguments() == null
                                            ? "-"
                                            : Protocol.write(tool.arguments()));
                        })
                .collect(Collectors.toList());
    }

    @Test
    void commands() {
        assertEquals(List.of("say hello there"), shape("echo hello there"));
        assertEquals(List.of("say Let last=false", "say me think"), shape("stream Let me think"));
        assertEquals(
                List.of(
                        "tool transfer {\"to\":{\"kind\":\"user\",\"value\":\"conformance-desk\"}}"),
                shape("transfer"));
        assertEquals(List.of("tool hangup -"), shape("hangup"));
        assertEquals(List.of("tool send_dtmf {\"digits\":\"12#\"}"), shape("dtmf 12#"));
        assertEquals(List.of("tool hold {\"held\":true}"), shape("hold"));
        assertTrue(shape("something else").isEmpty());
    }
}
