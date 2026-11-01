// The agent: one process that serves calls on the Sent Voice Agents protocol.
//
// Give Agent a Handler; run() connects to Sent with the account API key and serves sessions until
// stop(). The
// runtime answers heartbeats, opens a new connection when one drains or is lost, and resumes the
// sessions that
// can move, so the handlers only see calls. Each session's handlers run one after another in the
// order Sent's
// frames arrived; different sessions run at the same time.
package dm.sent.voice.agents;

import dm.sent.voice.agents.frames.ConnectionDraining;
import dm.sent.voice.agents.frames.ConnectionReady;
import dm.sent.voice.agents.frames.ErrorFrame;
import dm.sent.voice.agents.frames.Frame;
import dm.sent.voice.agents.frames.FrameException;
import dm.sent.voice.agents.frames.Ping;
import dm.sent.voice.agents.frames.Pong;
import dm.sent.voice.agents.frames.Protocol;
import dm.sent.voice.agents.frames.SessionEnd;
import dm.sent.voice.agents.frames.SessionFrame;
import dm.sent.voice.agents.frames.SessionResume;
import dm.sent.voice.agents.frames.SessionResumed;
import dm.sent.voice.agents.frames.SessionStart;
import dm.sent.voice.agents.frames.ToolResult;
import dm.sent.voice.agents.frames.UnknownFrame;
import dm.sent.voice.agents.frames.UserSaid;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Serves calls. The handler's methods run on the session's own chain, in order. */
public final class Agent {
    /** Where the agent connects when the options name no URL. */
    public static final String DEFAULT_URL = "wss://" + Protocol.HOST + Protocol.CONNECT_PATH;

    /** The longest wait before another attempt to connect. */
    private static final int MAX_BACKOFF_MS = 30_000;

    /**
     * How long a handshake may take before the attempt counts as failed, and how long an open
     * socket may wait for connection.ready.
     */
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;

    /**
     * How many heartbeat intervals without a frame make a connection dead, as Heartbeats in
     * docs/protocol.md says.
     */
    private static final int SILENT_INTERVALS = 3;

    /**
     * Why Sent refuses a handshake with each status, from Connecting in docs/protocol.md. Each
     * needs a person.
     */
    private static final Map<Integer, String> REFUSALS =
            Map.of(
                    400, "agent_id or the subprotocol is missing",
                    401, "the API key is not valid",
                    403, "voice is not enabled for the account, or the key does not own the agent",
                    404, "the agent does not exist");

    private final String apiKey;
    private final String agentId;
    private final String url;
    private final Handler handler;
    final AgentLogger log;

    private final Object gate = new Object();
    final Set<Connection> connections = new HashSet<>();
    final Map<String, Session> live = new HashMap<>();
    int attempt;
    private boolean stopped;
    private CompletableFuture<Void> done;

    private final ScheduledExecutorService timers =
            Executors.newSingleThreadScheduledExecutor(daemon("sent-voice-agents-timer"));
    private final ExecutorService handlers =
            Executors.newCachedThreadPool(daemon("sent-voice-agents-session"));
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofMillis(HANDSHAKE_TIMEOUT_MS)).build();

    public Agent(Options options, Handler handler) {
        apiKey = options.apiKey();
        agentId = options.agentId();
        url = options.url() == null ? DEFAULT_URL : options.url();
        log = options.logger() == null ? new ConsoleLogger() : options.logger();
        this.handler = handler;
    }

    public String agentId() {
        return agentId;
    }

    public String url() {
        return url;
    }

    // ----- running -----

    /**
     * Serves calls until {@link #stop()}. The future completes after stop(), and fails with
     * RefusedException when Sent refuses the connection for a reason that needs a person.
     */
    public CompletableFuture<Void> run() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (gate) {
            done = future;
        }
        openConnection(new ArrayList<>(), 0);
        return future;
    }

    /** Runs the agent until SIGINT or SIGTERM. For a program whose main is the agent. */
    public void serve() {
        Thread hook = new Thread(this::stop, "sent-voice-agents-stop");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            run().join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof RefusedException refused) {
                throw refused;
            }
            throw error;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException alreadyStopping) {
                // The JVM is shutting down: the hook is what called stop().
            }
        }
    }

    /**
     * Stops serving: every connection closes with 1000, so the calls on them follow the agent's
     * fallback at once.
     */
    public void stop() {
        List<Connection> current;
        CompletableFuture<Void> future;
        synchronized (gate) {
            stopped = true;
            current = new ArrayList<>(connections);
            future = done;
            done = null;
        }
        for (Connection connection : current) {
            connection.cancelled = true;
            if (connection.watchdog != null) {
                connection.watchdog.cancel(false);
            }
            if (connection.dialing != null) {
                connection.dialing.cancel(false);
            }
            WebSocket socket = connection.socket;
            if (socket != null && !socket.isOutputClosed()) {
                closeQuietly(connection, socket);
            }
        }
        if (future != null) {
            future.complete(null);
        }
    }

    private void closeQuietly(Connection connection, WebSocket socket) {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "")
                .handle(
                        (closed, error) -> {
                            if (error != null) {
                                socket.abort();
                                closed(connection);
                            }
                            return null;
                        });
        // The other side answers the close, which closes the socket; if it does not, the socket is
        // dropped.
        timers.schedule(
                () -> {
                    socket.abort();
                    closed(connection);
                },
                5_000,
                TimeUnit.MILLISECONDS);
    }

    /** Every live session. */
    public List<Session> sessions() {
        synchronized (gate) {
            return List.copyOf(live.values());
        }
    }

    // ----- connections -----

    private void openConnection(List<Session> pending, int delayMs) {
        Connection connection = new Connection(pending, log);
        connection.broken = () -> broken(connection);
        synchronized (gate) {
            connections.add(connection);
        }
        if (delayMs > 0) {
            connection.dialing =
                    timers.schedule(() -> dial(connection), delayMs, TimeUnit.MILLISECONDS);
        } else {
            dial(connection);
        }
    }

    private void dial(Connection connection) {
        if (connection.cancelled) {
            closed(connection);
            return;
        }
        URI uri =
                URI.create(
                        url
                                + (url.contains("?") ? "&" : "?")
                                + "agent_id="
                                + URLEncoder.encode(agentId, StandardCharsets.UTF_8));
        http.newWebSocketBuilder()
                .subprotocols(Protocol.SUBPROTOCOL)
                .header("Authorization", "Bearer " + apiKey)
                .connectTimeout(Duration.ofMillis(HANDSHAKE_TIMEOUT_MS))
                .buildAsync(uri, new Reader(connection, uri.getHost()))
                .whenComplete(
                        (socket, error) -> {
                            if (error != null) {
                                refused(
                                        connection,
                                        error instanceof CompletionException
                                                        && error.getCause() != null
                                                ? error.getCause()
                                                : error);
                            }
                        });
    }

    private void refused(Connection connection, Throwable error) {
        int status =
                error instanceof WebSocketHandshakeException handshake
                                && handshake.getResponse() != null
                        ? handshake.getResponse().statusCode()
                        : 0;
        String reason = REFUSALS.get(status);
        if (reason != null) {
            log.error("refused with " + status + ": " + reason);
            CompletableFuture<Void> future;
            synchronized (gate) {
                stopped = true;
                future = done;
                done = null;
            }
            if (future != null) {
                future.completeExceptionally(new RefusedException(status, reason));
            }
        } else {
            log.warn(
                    status > 0
                            ? "refused with " + status + ": trying again"
                            : "connect failed: " + error.getMessage());
        }
        closed(connection);
    }

    /** A send failed: the socket is dead, whatever the JDK has reported about it. */
    private void broken(Connection connection) {
        if (connection.cancelled) {
            return;
        }
        log.info("lost: a send failed");
        WebSocket socket = connection.socket;
        if (socket != null) {
            socket.abort();
        }
        deliver(connection, () -> closed(connection));
    }

    /**
     * Runs the next step of a connection's inbox: messages and the close, in the order they
     * arrived.
     */
    private void deliver(Connection connection, Runnable step) {
        synchronized (connection.inboxGate) {
            connection.inbox = connection.inbox.thenRunAsync(() -> guarded(step), handlers);
        }
    }

    /**
     * Reads one socket. Each callback asks for the next message and hands the one it got to the
     * connection's inbox at once, so the JDK is never left without demand while the agent works:
     * see Connection.inbox.
     */
    private final class Reader implements WebSocket.Listener {
        private final Connection connection;
        private final String host;
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Reader(Connection connection, String host) {
            this.connection = connection;
            this.host = host;
        }

        @Override
        public void onOpen(WebSocket socket) {
            connection.socket = socket;
            if (connection.cancelled) {
                socket.abort();
                closed(connection);
                return;
            }
            log.info("connected to " + host);
            // Until connection.ready arrives the socket has the handshake's deadline; ready() hands
            // it to heard().
            arm(connection, HANDSHAKE_TIMEOUT_MS, "connection.ready");
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            String message = null;
            if (last) {
                message = text.toString();
                text.setLength(0);
            }
            socket.request(1);
            if (message != null) {
                heard(connection);
                String whole = message;
                deliver(connection, () -> received(whole, null));
            }
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            bytes.write(chunk, 0, chunk.length);
            byte[] message = null;
            if (last) {
                message = bytes.toByteArray();
                bytes.reset();
            }
            socket.request(1);
            if (message != null) {
                heard(connection);
                byte[] whole = message;
                deliver(connection, () -> received(null, whole));
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            String what = ("closed with " + status + " " + reason).trim();
            deliver(
                    connection,
                    () -> {
                        log.info(what);
                        closed(connection);
                    });
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            String what =
                    connection.cancelled ? "closed by the agent" : "lost: " + error.getMessage();
            deliver(
                    connection,
                    () -> {
                        log.info(what);
                        closed(connection);
                    });
        }

        private void received(String message, byte[] binary) {
            if (binary != null) {
                handleAudio(connection, binary);
                return;
            }
            Frame frame;
            try {
                frame = Protocol.decode(message);
            } catch (FrameException error) {
                log.warn("ignored a frame: " + error.getMessage());
                return;
            }
            try {
                handle(connection, frame);
            } catch (RuntimeException error) {
                // A frame the runtime could not handle is skipped; the connection goes on.
                log.error("handling a " + frame.type() + " frame failed: " + error);
            }
        }
    }

    /** Resets the watchdog: no frame for three heartbeat intervals means the connection is dead. */
    private void heard(Connection connection) {
        if (!connection.ready) {
            return;
        }
        arm(connection, SILENT_INTERVALS * connection.heartbeatMs, "frame");
    }

    /** Arms the connection's one watchdog: nothing within {@code dueMs} closes the socket. */
    private void arm(Connection connection, long dueMs, String expected) {
        synchronized (connection) {
            if (connection.watchdog != null) {
                connection.watchdog.cancel(false);
            }
            connection.expected = expected;
            connection.dueMs = dueMs;
            if (connection.cancelled) {
                return;
            }
            connection.watchdog =
                    timers.schedule(
                            () -> {
                                log.warn(
                                        "no "
                                                + connection.expected
                                                + " for "
                                                + connection.dueMs
                                                + " ms, closing");
                                WebSocket socket = connection.socket;
                                if (socket != null) {
                                    socket.abort();
                                }
                                closed(connection);
                            },
                            dueMs,
                            TimeUnit.MILLISECONDS);
        }
    }

    void handle(Connection connection, Frame frame) {
        if (frame instanceof ConnectionReady ready) {
            ready(connection, ready);
        } else if (frame instanceof Ping ping) {
            connection.sendText(Pong.TYPE, Protocol.encodeToString(Pong.of(ping.ts())));
            // A connection that lived through a heartbeat interval is a good one: the next loss
            // starts the
            // backoff over. A connection that dies before its first ping keeps it growing.
            if (connection.ready) {
                synchronized (gate) {
                    attempt = 0;
                }
            }
        } else if (frame instanceof ConnectionDraining) {
            drain(connection);
        } else if (frame instanceof SessionStart start) {
            startSession(connection, start);
        } else if (frame instanceof SessionResumed resumed) {
            resumed(connection, resumed);
        } else if (frame instanceof ErrorFrame error) {
            failed(connection, error);
            safely(() -> handler.onError(error));
        } else if (frame instanceof SessionFrame scoped) {
            sessionFrame(connection, frame, scoped.sessionId());
        } else if (frame instanceof UnknownFrame unknown && unknown.sessionId() != null) {
            sessionFrame(connection, frame, unknown.sessionId());
        } else {
            log.info("ignored a " + frame.type() + " frame");
        }
    }

    void ready(Connection connection, ConnectionReady frame) {
        List<Session> pending;
        synchronized (gate) {
            connection.ready = true;
            connection.heartbeatMs = frame.heartbeatIntervalMs();
            pending = new ArrayList<>();
            for (Session session : connection.pending) {
                if (live.get(session.id()) == session) {
                    pending.add(session);
                }
            }
            connection.pending = new ArrayList<>();
        }
        heard(connection);
        log.info(
                "ready: protocol "
                        + frame.protocolVersion()
                        + ", up to "
                        + frame.maxSessions()
                        + " sessions, resuming "
                        + pending.size());
        for (Session session : pending) {
            resume(connection, session);
        }
        safely(() -> handler.onConnected(frame));
    }

    private void resume(Connection connection, Session session) {
        synchronized (gate) {
            for (Connection other : connections) {
                other.sessions.remove(session.id());
            }
            connection.sessions.put(session.id(), session);
            session.connection = connection;
            session.resuming = true;
        }
        SessionResume resume = session.resumeFrame();
        connection.sendText(resume.type(), Protocol.encodeToString(resume));
    }

    private void drain(Connection connection) {
        List<Session> moving = new ArrayList<>();
        synchronized (gate) {
            connection.draining = true;
            for (Session session : connection.sessions.values()) {
                if (session.token() != null && !session.resuming) {
                    moving.add(session);
                }
            }
        }
        log.info("draining, moving " + moving.size() + " session(s)");
        openConnection(moving, 0);
    }

    void startSession(Connection connection, SessionStart frame) {
        Session session = new Session(frame, connection);
        synchronized (gate) {
            live.put(session.id(), session);
            connection.sessions.put(session.id(), session);
        }
        log.info(
                "session "
                        + session.id()
                        + " started: "
                        + frame.sessionType()
                        + ", "
                        + frame.direction());
        enqueue(session, () -> handler.onSessionStart(session));
    }

    private void sessionFrame(Connection connection, Frame frame, String sessionId) {
        Session session;
        synchronized (gate) {
            // A session that moved to another connection is read there: Sent replays what this one
            // did not deliver.
            session = connection.sessions.get(sessionId);
            if (session == null || session.resuming) {
                return;
            }
        }
        session.read(frame);
        if (frame instanceof UserSaid said) {
            enqueue(session, () -> handler.onUserSaid(session, said));
        } else if (frame instanceof ToolResult result) {
            session.answerTool(result);
            enqueue(session, () -> handler.onToolResult(session, result));
        } else if (frame instanceof SessionEnd end) {
            session.finish(end);
            forget(session);
            enqueue(session, () -> handler.onSessionEnd(session, end));
        } else if (frame instanceof UnknownFrame unknown) {
            enqueue(session, () -> handler.onFrame(session, unknown));
        }
    }

    private void handleAudio(Connection connection, byte[] message) {
        if (message.length < Audio.HEADER_BYTES) {
            return;
        }
        int stream = Audio.streamOf(message);
        Session session = null;
        synchronized (gate) {
            for (Session candidate : connection.sessions.values()) {
                Integer current = candidate.stream();
                if (current != null && current == stream && !candidate.resuming) {
                    session = candidate;
                    break;
                }
            }
        }
        if (session == null) {
            return;
        }
        Session found = session;
        byte[] samples = Audio.samplesOf(message);
        enqueue(found, () -> handler.onAudio(found, samples));
    }

    private void resumed(Connection connection, SessionResumed frame) {
        Session session;
        synchronized (gate) {
            session = connection.sessions.get(frame.sessionId());
            if (session == null || !session.resuming) {
                return;
            }
        }
        int again = session.resumed(frame);
        log.info("session " + session.id() + " resumed, sent " + again + " frame(s) again");
    }

    /**
     * An error is uncounted, as Counting frames in docs/protocol.md says. Only one that refuses a
     * session.resume changes the session's fate; any other is the handler's to read.
     */
    void failed(Connection connection, ErrorFrame frame) {
        log.warn("error " + frame.error().code() + ": " + frame.error().message());
        boolean aboutTheResume =
                "session.resume".equals(frame.frameType())
                        || "resume_failed".equals(frame.error().code())
                        || "connection_full".equals(frame.error().code());
        if (!aboutTheResume) {
            return;
        }
        Session session;
        synchronized (gate) {
            session = frame.sessionId() == null ? null : connection.sessions.get(frame.sessionId());
            if (session == null || !session.resuming) {
                return;
            }
            session.resuming = false;
            connection.sessions.remove(session.id());
        }
        // This connection already carries max_sessions. The token is still good on another one.
        if ("connection_full".equals(frame.error().code())) {
            List<Session> moving = new ArrayList<>();
            moving.add(session);
            openConnection(moving, 0);
            return;
        }
        lost(session);
    }

    private void forget(Session session) {
        synchronized (gate) {
            live.remove(session.id());
            for (Connection connection : connections) {
                connection.sessions.remove(session.id());
            }
        }
    }

    void lost(Session session) {
        forget(session);
        session.abandon();
        enqueue(session, () -> handler.onSessionLost(session));
    }

    void closed(Connection connection) {
        List<Session> pending;
        int delay;
        boolean drained;
        List<Session> lostSessions = new ArrayList<>();
        synchronized (gate) {
            if (!connections.remove(connection)) {
                return;
            }
            if (connection.watchdog != null) {
                connection.watchdog.cancel(false);
            }
            if (stopped) {
                return;
            }
            List<Session> lost = new ArrayList<>(connection.sessions.values());
            List<Session> movable = new ArrayList<>();
            for (Session session : lost) {
                if (session.token() != null && !session.resuming && !queued(session)) {
                    movable.add(session);
                }
            }
            for (Session session : lost) {
                if (movable.contains(session) || queued(session)) {
                    continue;
                }
                log.warn(
                        "session "
                                + session.id()
                                + " lost: "
                                + (session.token() != null
                                        ? "its resume was not answered"
                                        : "it has no resume token"));
                lostSessions.add(session);
            }
            pending = new ArrayList<>();
            for (Session session : connection.pending) {
                if (live.get(session.id()) == session) {
                    pending.add(session);
                }
            }
            pending.addAll(movable);
            drained = connection.draining && pending.isEmpty();
            delay = drained ? 0 : backoff();
        }
        // Reported whether or not a reconnect follows: a drained connection's leftovers are lost
        // too.
        for (Session session : lostSessions) {
            lost(session);
        }
        if (drained) {
            return;
        }
        log.info("reconnecting in " + delay + " ms to resume " + pending.size() + " session(s)");
        openConnection(pending, delay);
    }

    /** Whether another connection already waits to resume the session. Under the gate. */
    private boolean queued(Session session) {
        for (Connection other : connections) {
            if (other.pending.contains(session)) {
                return true;
            }
        }
        return false;
    }

    private int backoff() {
        int delay =
                attempt == 0
                        ? 0
                        : (int) Math.min(MAX_BACKOFF_MS, 1000L << Math.min(attempt - 1, 20));
        attempt++;
        return delay;
    }

    // ----- the session's chain -----

    private void enqueue(Session session, Runnable handler) {
        synchronized (session.chainGate) {
            session.chain = session.chain.thenRunAsync(() -> guarded(handler), handlers);
        }
    }

    /** Runs a connection-level handler off the reader. */
    private void safely(Runnable handler) {
        handlers.execute(() -> guarded(handler));
    }

    /**
     * Runs a handler; an exception in it is logged and does not take the session or the agent down.
     */
    private void guarded(Runnable handler) {
        try {
            handler.run();
        } catch (RuntimeException error) {
            log.error("a handler failed: " + error);
        }
    }

    private static ThreadFactory daemon(String name) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
