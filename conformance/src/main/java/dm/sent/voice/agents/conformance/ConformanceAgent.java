// The conformance agent of sent-dm-voice-protocol's conformance/README.md, on this SDK's API. The
// protocol's
// harness runs it in CI before every release, through Main: it connects to SENT_CONFORMANCE_URL
// with any API key
// and does exactly what the README's table says.
package dm.sent.voice.agents.conformance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dm.sent.voice.agents.Handler;
import dm.sent.voice.agents.Session;
import dm.sent.voice.agents.SessionEndedException;
import dm.sent.voice.agents.frames.Protocol;
import dm.sent.voice.agents.frames.ToolResult;
import dm.sent.voice.agents.frames.UserSaid;
import java.util.List;
import java.util.concurrent.CompletionException;

public final class ConformanceAgent implements Handler {
    public static final String GREETING = "Hello from the conformance agent.";

    /** One action a user.said asks for. */
    public sealed interface Step permits Say, Tool {}

    public record Say(String text, Boolean last) implements Step {}

    public record Tool(String name, ObjectNode arguments) implements Step {}

    /**
     * What a user.said asks for: the actions the agent performs in order. A command is the text up
     * to the first space; {@code stream} takes the next word as the first piece.
     */
    public static List<Step> decide(String text) {
        int space = text.indexOf(' ');
        String command = space < 0 ? text : text.substring(0, space);
        String rest = space < 0 ? "" : text.substring(space + 1);
        switch (command) {
            case "echo":
                return List.of(new Say(rest, null));
            case "stream":
                {
                    int next = rest.indexOf(' ');
                    String word = next < 0 ? rest : rest.substring(0, next);
                    String remainder = next < 0 ? "" : rest.substring(next + 1);
                    return List.of(new Say(word, false), new Say(remainder, null));
                }
            case "transfer":
                {
                    ObjectNode to = Protocol.object();
                    to.put("kind", "user");
                    to.put("value", "conformance-desk");
                    ObjectNode arguments = Protocol.object();
                    arguments.set("to", to);
                    return List.of(new Tool("transfer", arguments));
                }
            case "hangup":
                return List.of(new Tool("hangup", null));
            case "dtmf":
                {
                    ObjectNode arguments = Protocol.object();
                    arguments.put("digits", rest);
                    return List.of(new Tool("send_dtmf", arguments));
                }
            case "hold":
                {
                    ObjectNode arguments = Protocol.object();
                    arguments.put("held", true);
                    return List.of(new Tool("hold", arguments));
                }
            default:
                return List.of();
        }
    }

    @Override
    public void onSessionStart(Session session) {
        if ("text".equals(session.sessionType())) {
            session.say(GREETING);
        }
    }

    @Override
    public void onUserSaid(Session session, UserSaid said) {
        try {
            if (said.correction()) {
                session.clear();
            }
            for (Step step : decide(said.text())) {
                if (step instanceof Say say) {
                    session.say(say.text(), say.last(), null);
                } else if (step instanceof Tool tool) {
                    // The harness answers every tool call; the result also reaches onToolResult.
                    session.tool(tool.name(), tool.arguments()).join();
                }
            }
        } catch (SessionEndedException ended) {
            // The call left while the agent was acting on it.
        } catch (CompletionException failed) {
            if (!(failed.getCause() instanceof SessionEndedException)) {
                throw failed;
            }
        }
    }

    @Override
    public void onToolResult(Session session, ToolResult result) {
        if (!result.ok() && result.error() != null) {
            try {
                session.say("Tool failed: " + result.error().code());
            } catch (SessionEndedException ended) {
                // The call left first.
            }
        }
    }

    @Override
    public void onAudio(Session session, byte[] samples) {
        session.sendAudio(samples);
    }
}
