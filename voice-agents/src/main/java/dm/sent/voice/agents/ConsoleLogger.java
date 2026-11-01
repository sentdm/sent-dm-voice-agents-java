package dm.sent.voice.agents;

/** The default logger: standard output for information, standard error for warnings and errors. */
public final class ConsoleLogger implements AgentLogger {
    @Override
    public void info(String message) {
        System.out.println(message);
    }

    @Override
    public void warn(String message) {
        System.err.println(message);
    }

    @Override
    public void error(String message) {
        System.err.println(message);
    }
}
