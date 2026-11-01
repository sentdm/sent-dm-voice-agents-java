package dm.sent.voice.agents;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;

/** One WebSocket to Sent, and the sessions it reads and writes. */
final class Connection implements Sender {
    volatile WebSocket socket;
    volatile boolean ready;
    boolean draining;
    final Map<String, Session> sessions = new HashMap<>();
    List<Session> pending;
    ScheduledFuture<?> watchdog;
    ScheduledFuture<?> dialing;

    /**
     * What the watchdog waits for, and how long: connection.ready on a new socket, then any frame.
     */
    String expected = "connection.ready";

    long dueMs;
    long heartbeatMs;

    /** The agent stopped, or gave this connection up, before or while its socket was open. */
    volatile boolean cancelled;

    private final AgentLogger log;
    private final Object outboxGate = new Object();

    /** The JDK's WebSocket takes one send at a time: the sends are chained, in order. */
    private CompletableFuture<?> outbox = CompletableFuture.completedFuture(null);

    Connection(List<Session> pending, AgentLogger log) {
        this.pending = pending;
        this.log = log;
    }

    boolean open() {
        WebSocket current = socket;
        return current != null && !current.isOutputClosed() && !cancelled;
    }

    @Override
    public void sendText(String type, String json) {
        if (!queue(socket -> socket.sendText(json, true))) {
            // A session frame is still counted as sent. Sent's received on the next resume says to
            // send it again.
            log.info("not sent: " + type + ", the connection is not open");
        }
    }

    @Override
    public void sendBytes(byte[] frame) {
        queue(socket -> socket.sendBinary(ByteBuffer.wrap(frame), true));
    }

    private boolean queue(Function<WebSocket, CompletableFuture<WebSocket>> send) {
        WebSocket current = socket;
        if (!open()) {
            return false;
        }
        synchronized (outboxGate) {
            outbox =
                    outbox.handle((value, error) -> null)
                            .thenCompose(
                                    value -> {
                                        try {
                                            // The socket's own failures are the reader's to report.
                                            return send.apply(current)
                                                    .handle((sent, error) -> null);
                                        } catch (RuntimeException error) {
                                            return CompletableFuture.completedFuture(null);
                                        }
                                    });
        }
        return true;
    }
}
