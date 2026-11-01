package dm.sent.voice.agents;

/** Sent refused the connection for a reason that needs a person: the status and why. */
public final class RefusedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String reason;

    public RefusedException(int status, String reason) {
        super(status + ": " + reason);
        this.status = status;
        this.reason = reason;
    }

    public int status() {
        return status;
    }

    public String reason() {
        return reason;
    }
}
