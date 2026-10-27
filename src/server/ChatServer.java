package server;

import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Entry point of the server: accepts connections and gives each one its own thread. */
public class ChatServer {
    public static final int DEFAULT_PORT = 5000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static synchronized void log(String msg) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + msg);
    }

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (port < 1 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                System.err.println("Usage: java server.ChatServer [port 1-65535]");
                return;
            }
        }

        UserRegistry users = new UserRegistry();
        GroupManager groups = new GroupManager();
        ExecutorService pool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            log("Server started on port " + port + ". Waiting for clients...");
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setKeepAlive(true);
                    pool.execute(new ClientHandler(socket, users, groups));
                } catch (IOException e) {
                    log("Failed to accept a connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Cannot start server on port " + port + ": " + e.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }
}
