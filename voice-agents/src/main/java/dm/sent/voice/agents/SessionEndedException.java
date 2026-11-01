package dm.sent.voice.agents;

/** The session ended before the tool call was answered. */
public final class SessionEndedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String sessionId;

    public SessionEndedException(String sessionId) {
        super("session " + sessionId + " has ended");
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }
}
