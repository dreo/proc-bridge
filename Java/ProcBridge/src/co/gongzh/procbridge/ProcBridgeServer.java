package co.gongzh.procbridge;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Gong Zhang
 */
public final class ProcBridgeServer {

    @FunctionalInterface
    public interface Delegate {
        /**
         * An interface that defines how server handles requests.
         *
         * @param api the requested API name
         * @param body the JSON body of request
         * @return a JSON object or {@code null} for good response
         * @throws Exception any exception for bad response
         */
        @Nullable
        JsonObject handleRequest(@NotNull String api, @NotNull JsonObject body) throws Exception;
    }

    private final int port;
    private final Delegate delegate;
    private boolean started;

    private ExecutorService executor;
    private ServerSocket serverSocket;

    public ProcBridgeServer(int port, @NotNull Object delegate) {
        this(port, new ReflectiveDelegate(delegate));
    }

    public ProcBridgeServer(int port, Delegate delegate) {
        this.started = false;
        this.port = port;
        this.delegate = delegate;

        this.executor = null;
        this.serverSocket = null;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public int getPort() {
        return port;
    }

    public synchronized void start() throws IOException {
        if (started) {
            throw new IllegalStateException("server already started");
        }

        final ServerSocket serverSocket = new ServerSocket(this.port); // possible throw exception!
        this.serverSocket = serverSocket;

        final ExecutorService executor = Executors.newCachedThreadPool();
        this.executor = executor;
        executor.execute(() -> {
            while (true) {
                try {
                    Socket socket = serverSocket.accept();
                    Connection conn = new Connection(socket, executor, delegate);
                    synchronized (ProcBridgeServer.this) {
                        if (!started) {
                            return; // finish listener
                        }
                        executor.execute(conn);
                    }
                } catch (IOException ignored) {
                    return; // finish listener
                }
            }
        });

        started = true;
    }

    public synchronized void stop() {
        if (!started) {
            throw new IllegalStateException("server does not started");
        }

        executor.shutdown();
        executor = null;

        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        serverSocket = null;

        this.started = false;
    }

    private static final class Connection implements Runnable {

        private final Socket socket;
        private final Delegate delegate;
        private final ExecutorService executor;

        Connection(Socket socket, ExecutorService executor, Delegate delegate) {
            this.socket = socket;
            this.delegate = delegate;
            this.executor = executor;
        }

        @Override
        public void run() {
            try (OutputStream os = socket.getOutputStream();
                 InputStream is = socket.getInputStream()) {

                RequestDecoder decoder = Protocol.read(is).asRequest();
                if (decoder == null) {
                    throw ProcBridgeException.malformedInputData();
                }

                final String api = decoder.api;
                final JsonObject body = decoder.body;

                Encoder encoder;
                try {
                    JsonObject reply = delegate.handleRequest(api, body);
                    encoder = new GoodResponseEncoder(reply);
                } catch (Exception ex) {
                    encoder = new BadResponseEncoder(ex.toString());
                }

                Protocol.write(os, encoder);

            } catch (ProcBridgeException | IOException e) {
                throw new RuntimeException(e);
            }
        }

    }

}
