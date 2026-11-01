package dm.sent.voice.agents;

/** Where the runtime's log lines go. Tokens and keys never appear in them. */
public interface AgentLogger {
    void info(String message);

    void warn(String message);

    void error(String message);
}
