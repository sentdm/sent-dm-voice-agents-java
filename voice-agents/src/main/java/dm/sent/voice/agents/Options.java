package dm.sent.voice.agents;

import java.util.Objects;

/** What an agent needs. */
public final class Options {
    private final String apiKey;
    private final String agentId;
    private final String url;
    private final AgentLogger logger;

    /**
     * @param apiKey the account API key; keep it on the server the agent runs on
     * @param agentId the agent, as {@code POST /v3/voice/agents} created it
     */
    public Options(String apiKey, String agentId) {
        this(apiKey, agentId, null, null);
    }

    private Options(String apiKey, String agentId, String url, AgentLogger logger) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.url = url;
        this.logger = logger;
    }

    /** Where to connect. The default is Sent. */
    public Options withUrl(String url) {
        return new Options(apiKey, agentId, url, logger);
    }

    /** Where the runtime's log lines go. The default is the console. */
    public Options withLogger(AgentLogger logger) {
        return new Options(apiKey, agentId, url, logger);
    }

    public String apiKey() {
        return apiKey;
    }

    public String agentId() {
        return agentId;
    }

    public String url() {
        return url;
    }

    public AgentLogger logger() {
        return logger;
    }
}
