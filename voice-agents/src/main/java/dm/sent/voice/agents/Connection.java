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

    /** What the agent does when a send fails: the socket is dead whatever the JDK has reported. */
    Runnable broken = () -> {};

    /**
     * The messages read, handled one after another off the JDK's listener thread. The listener
     * returns at once, because the JDK's WebSocket reports a dropped connection only while it has
     * demand: a drop that lands while the listener is still running is lost for good
     * (TransportImpl.acknowledgeReception throws InternalError on EOF with no demand, and the
     * socket layer swallows it, so neither onClose nor onError ever runs).
     */
    CompletableFuture<Void> inbox = CompletableFuture.completedFuture(null);

    final Object inboxGate = new Object();

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
                                            // A send that fails is the one sure sign of a dead
                                            // socket.
                                            return send.apply(current)
                                                    .handle(
                                                            (sent, error) -> {
                                                                if (error != null && !cancelled) {
                                                                    broken.run();
                                                                }
                                                                return null;
                                                            });
                                        } catch (RuntimeException error) {
                                            if (!cancelled) {
                                                broken.run();
                                            }
                                            return CompletableFuture.completedFuture(null);
                                        }
                                    });
        }
        return true;
    }
}
