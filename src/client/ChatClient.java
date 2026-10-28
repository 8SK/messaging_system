package client;

import common.FrameIO;
import common.Message;
import common.Protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Console client: connects, registers, then reads commands from the keyboard. */
public class ChatClient {
    private final String host;
    private final int port;
    private Socket socket;
    DataInputStream in;
    DataOutputStream out;
    final Object writeLock = new Object();
    final BlockingQueue<Message> fileReplies = new LinkedBlockingQueue<>();

    private volatile String username;
    private volatile boolean closing = false;
    private HistoryManager history;

    public ChatClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        String host = args.length > 0 ? args[0] : "localhost";
        int port = 5000;
        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Usage: java client.ChatClient [host] [port]");
                return;
            }
        }
        new ChatClient(host, port).start();
    }

    // ------------------------------------------------------------------ startup

    private void start() {
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        } catch (IOException e) {
            System.err.println("Cannot connect to " + host + ":" + port + " - " + e.getMessage());
            return;
        }

        try {
            if (!register(console)) {
                closeSocket();
                return;
            }
        } catch (IOException e) {
            System.err.println("Connection lost during registration: " + e.getMessage());
            closeSocket();
            return;
        }

        history = new HistoryManager(username);
        ServerListener listener = new ServerListener(this, in, history);
        listener.setDaemon(true);
        listener.start();
        System.out.println("Connected as '" + username + "'. Type /help for commands.");
        commandLoop(console);
        shutdown();
    }

    private boolean register(BufferedReader console) throws IOException {
        while (true) {
            System.out.print("Enter username: ");
            System.out.flush();
            String line = console.readLine();
            if (line == null) return false;
            String name = line.trim();
            if (!Protocol.isValidName(name)) {
                System.out.println("Invalid username (1-20 letters, digits, '_', '.', '-').");
                continue;
            }
            FrameIO.writeFrame(out, Protocol.encode(new Message(Protocol.REGISTER, name, "", "")));
            Message reply = Protocol.parse(FrameIO.readFrame(in));
            if (Protocol.REGISTER_OK.equals(reply.getType())) {
                username = name;
                return true;
            }
            System.out.println("Registration failed: " + reply.getContent());
        }
    }

    // ------------------------------------------------------------------ commands

    private void commandLoop(BufferedReader console) {
        while (true) {
            String line;
            try {
                line = console.readLine();
            } catch (IOException e) {
                break;
            }
            if (line == null) break; // Ctrl+D / end of input
            line = line.trim();
            if (line.isEmpty()) continue;
            if (!line.startsWith("/")) {
                System.out.println("Commands start with '/'. Type /help.");
                continue;
            }

            String[] p = line.split("\\s+", 3);
            String cmd = p[0].toLowerCase();
            try {
                switch (cmd) {
                    case "/msg":
                        if (p.length < 3 || !Protocol.isValidName(p[1])) {
                            System.out.println("Usage: /msg <user> <text>");
                        } else {
                            send(new Message(Protocol.PRIVATE, username, p[1], p[2]));
                        }
                        break;
                    case "/create":
                        sendGroupCmd(Protocol.GROUP_CREATE, p, "/create <group>");
                        break;
                    case "/join":
                        sendGroupCmd(Protocol.GROUP_JOIN, p, "/join <group>");
                        break;
                    case "/leave":
                        sendGroupCmd(Protocol.GROUP_LEAVE, p, "/leave <group>");
                        break;
                    case "/gmsg":
                        if (p.length < 3 || !Protocol.isValidName(p[1])) {
                            System.out.println("Usage: /gmsg <group> <text>");
                        } else {
                            send(new Message(Protocol.GROUP_MSG, username, p[1], p[2]));
                        }
                        break;
                    case "/users":
                        send(new Message(Protocol.LIST_USERS, username, "", ""));
                        break;
                    case "/sendfile":
                        if (p.length < 3 || !Protocol.isValidName(p[1])) {
                            System.out.println("Usage: /sendfile <user> <path>");
                        } else {
                            FileSender.sendFile(this, p[1], p[2]);
                        }
                        break;
                    case "/history":
                        history.printAll();
                        break;
                    case "/search":
                        String phrase = line.substring(cmd.length()).trim();
                        if (phrase.isEmpty()) System.out.println("Usage: /search <word or phrase>");
                        else history.search(phrase);
                        break;
                    case "/help":
                        printHelp();
                        break;
                    case "/quit":
                        return;
                    default:
                        System.out.println("Unknown command. Type /help.");
                }
            } catch (IOException e) {
                System.out.println("Connection lost: " + e.getMessage());
                return;
            }
        }
    }

    private void sendGroupCmd(String type, String[] p, String usage) throws IOException {
        if (p.length < 2 || !Protocol.isValidName(p[1])) {
            System.out.println("Usage: " + usage);
        } else {
            send(new Message(type, username, p[1], ""));
        }
    }

    private void printHelp() {
        System.out.println("Commands:");
        System.out.println("  /msg <user> <text>       private message");
        System.out.println("  /create <group>          create a group (you become a member)");
        System.out.println("  /join <group>            join a group");
        System.out.println("  /leave <group>           leave a group");
        System.out.println("  /gmsg <group> <text>     message a group");
        System.out.println("  /users                   list online users with IP addresses");
        System.out.println("  /sendfile <user> <path>  send a file to an online user");
        System.out.println("  /history                 show saved history");
        System.out.println("  /search <phrase>         search saved history");
        System.out.println("  /quit                    disconnect");
    }

    // ------------------------------------------------------------------ sending / closing

    /** Thread-safe: frames from different threads never interleave. */
    public void send(Message m) throws IOException {
        synchronized (writeLock) {
            FrameIO.writeFrame(out, Protocol.encode(m));
        }
    }

    public boolean trySend(Message m) {
        try {
            send(m);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Called by ServerListener when the connection to the server breaks. */
    public void onConnectionLost() {
        if (closing) return;
        System.out.println("Disconnected from the server.");
        closeSocket();
        System.exit(1);
    }

    private void shutdown() {
        closing = true;
        trySend(new Message(Protocol.QUIT, username, "", ""));
        closeSocket();
        System.out.println("Goodbye.");
    }

    private void closeSocket() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }
}
