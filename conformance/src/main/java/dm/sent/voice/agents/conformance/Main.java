// The conformance agent of sent-dm-voice-protocol, as a program: the protocol's harness runs it
// with
// SENT_CONFORMANCE_URL set, in CI before every release, and by hand against a harness on localhost.
package dm.sent.voice.agents.conformance;

import dm.sent.voice.agents.Agent;
import dm.sent.voice.agents.Options;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        String url = System.getenv("SENT_CONFORMANCE_URL");
        Options options =
                new Options("conformance", "agent_conformance")
                        .withUrl(
                                url == null || url.isEmpty()
                                        ? "ws://localhost:8765/v1/agents/connect"
                                        : url);
        new Agent(options, new ConformanceAgent()).serve();
    }
}
