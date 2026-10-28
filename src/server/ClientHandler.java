package server;

import common.FrameIO;
import common.Message;
import common.Protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.Set;

/** Runs in its own thread: serves exactly one connected client. */
public class ClientHandler implements Runnable {
    private final Socket socket;
    private final UserRegistry users;
    private final GroupManager groups;
    private final String ip;
    private final Object writeLock = new Object(); // one writer at a time per socket

    private DataInputStream in;
    private DataOutputStream out;
    private volatile String username;

    public ClientHandler(Socket socket, UserRegistry users, GroupManager groups) {
        this.socket = socket;
        this.users = users;
        this.groups = groups;
        this.ip = socket.getInetAddress().getHostAddress();
    }

    public String getIp() {
        return ip;
    }

    private String label() {
        return username != null ? "'" + username + "' (" + ip + ")" : ip;
    }

    // ------------------------------------------------------------------ main loop

    @Override
    public void run() {
        try {
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            ChatServer.log("Connection from " + ip);

            if (!register()) return;

            boolean running = true;
            while (running) {
                String raw = FrameIO.readFrame(in);
                Message m;
                try {
                    m = Protocol.parse(raw);
                } catch (IllegalArgumentException e) {
                    error("Malformed message: " + e.getMessage());
                    continue;
                }
                // Never trust the sender field sent by the client
                m = new Message(m.getType(), username, m.getRecipient(), m.getContent());
                running = dispatch(m);
            }
        } catch (EOFException e) {
            ChatServer.log("Client closed the connection: " + label());
        } catch (IOException e) {
            ChatServer.log("Connection error with " + label() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            ChatServer.log("Unexpected error with " + label() + ": " + e);
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        String name = username;
        if (name != null) {
            users.remove(name, this);
            groups.removeUser(name);
            ChatServer.log("User '" + name + "' disconnected. Online: " + users.describe());
        }
        closeQuietly(in);
        closeQuietly(out);
        closeQuietly(socket);
    }

    private static void closeQuietly(Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ registration

    private boolean register() throws IOException {
        while (true) {
            Message m;
            try {
                m = Protocol.parse(FrameIO.readFrame(in));
            } catch (IllegalArgumentException e) {
                send(new Message(Protocol.REGISTER_FAIL, Protocol.SERVER, "", "Malformed request"));
                continue;
            }
            if (!Protocol.REGISTER.equals(m.getType())) {
                send(new Message(Protocol.REGISTER_FAIL, Protocol.SERVER, "", "You must register first"));
                continue;
            }
            String name = m.getSender();
            if (!Protocol.isValidName(name)) {
                send(new Message(Protocol.REGISTER_FAIL, Protocol.SERVER, "",
                        "Invalid username (1-20 letters, digits, '_', '.', '-')"));
                continue;
            }
            synchronized (writeLock) { // nobody may write to us before REGISTER_OK
                if (users.register(name, this)) {
                    username = name;
                    send(new Message(Protocol.REGISTER_OK, Protocol.SERVER, name, "Welcome " + name));
                    ChatServer.log("User '" + name + "' registered from " + ip + ". Online: " + users.describe());
                    return true;
                }
            }
            send(new Message(Protocol.REGISTER_FAIL, Protocol.SERVER, "",
                    "Username '" + name + "' is already in use"));
        }
    }

    // ------------------------------------------------------------------ dispatch

    /** @return false when the client asked to quit. */
    private boolean dispatch(Message m) throws IOException {
        switch (m.getType()) {
            case Protocol.PRIVATE:      handlePrivate(m); break;
            case Protocol.GROUP_CREATE: handleGroupCreate(m); break;
            case Protocol.GROUP_JOIN:   handleGroupJoin(m); break;
            case Protocol.GROUP_LEAVE:  handleGroupLeave(m); break;
            case Protocol.GROUP_MSG:    handleGroupMessage(m); break;
            case Protocol.LIST_USERS:
                send(new Message(Protocol.USERS, Protocol.SERVER, username, users.toProtocolString()));
                break;
            case Protocol.FILE_SEND:    handleFileSend(m); break;
            case Protocol.FILE_RESULT:  handleFileResult(m); break;
            case Protocol.QUIT:         return false;
            default:
                error("Unknown request type: " + m.getType());
        }
        return true;
    }

    // ------------------------------------------------------------------ private messages

    private void handlePrivate(Message m) {
        String to = m.getRecipient();
        if (m.getContent().isEmpty()) {
            error("Message is empty");
            return;
        }
        if (to.equals(username)) {
            error("You cannot send a private message to yourself");
            return;
        }
        ClientHandler target = users.get(to);
        if (target == null) {
            error("User '" + to + "' is offline or does not exist");
            return;
        }
        if (target.send(m)) {
            send(new Message(Protocol.PRIVATE_SENT, username, to, m.getContent()));
        } else {
            error("Could not deliver the message to '" + to + "'");
        }
    }

    // ------------------------------------------------------------------ groups

    private void handleGroupCreate(Message m) {
        String group = m.getRecipient();
        if (!Protocol.isValidName(group)) {
            error("Invalid group name (1-20 letters, digits, '_', '.', '-')");
        } else if (groups.create(group, username)) {
            info("Group '" + group + "' created. You are now a member.");
        } else {
            error("Group '" + group + "' already exists");
        }
    }

    private void handleGroupJoin(Message m) {
        String err = groups.join(m.getRecipient(), username);
        if (err == null) info("You joined group '" + m.getRecipient() + "'");
        else error(err);
    }

    private void handleGroupLeave(Message m) {
        String err = groups.leave(m.getRecipient(), username);
        if (err == null) info("You left group '" + m.getRecipient() + "'");
        else error(err);
    }

    private void handleGroupMessage(Message m) {
        String group = m.getRecipient();
        if (m.getContent().isEmpty()) {
            error("Message is empty");
            return;
        }
        Set<String> members = groups.members(group);
        if (members == null) {
            error("Group '" + group + "' does not exist");
        } else if (!members.contains(username)) {
            error("You are not a member of group '" + group + "'");
        } else {
            for (String member : members) { // includes the sender
                ClientHandler h = users.get(member);
                if (h != null) h.send(m);
            }
        }
    }

    // ------------------------------------------------------------------ file transfer

    private void handleFileSend(Message m) throws IOException {
        String to = m.getRecipient();
        String content = m.getContent();
        int idx = content.lastIndexOf(':');
        String fileName;
        long size;
        if (idx <= 0) {
            deny("Invalid file header");
            return;
        }
        try {
            fileName = content.substring(0, idx);
            size = Long.parseLong(content.substring(idx + 1));
        } catch (NumberFormatException e) {
            deny("Invalid file size");
            return;
        }
        if (size < 0) {
            deny("Invalid file size");
            return;
        }
        if (to.equals(username)) {
            deny("You cannot send a file to yourself");
            return;
        }
        ClientHandler target = users.get(to);
        if (target == null) {
            deny("User '" + to + "' is offline or does not exist");
            return;
        }

        // Tell the sender to start streaming the bytes
        send(new Message(Protocol.FILE_READY, Protocol.SERVER, username, to));
        ChatServer.log("File '" + fileName + "' (" + size + " bytes) " + username + " -> " + to);

        boolean targetOk = true;
        boolean aborted = false;
        long relayed = 0;
        byte[] buf = new byte[Protocol.MAX_CHUNK];

        // Hold the recipient's write lock so no other frame is mixed into the file data
        synchronized (target.writeLock) {
            try {
                FrameIO.writeFrame(target.out, Protocol.encode(m)); // header
            } catch (IOException e) {
                targetOk = false;
            }
            try {
                while (true) {
                    int len = in.readInt();
                    if (len == Protocol.END_OF_FILE) {
                        if (targetOk) targetOk = writeInt(target.out, Protocol.END_OF_FILE);
                        break;
                    }
                    if (len < 0) { // sender aborted
                        aborted = true;
                        if (targetOk) targetOk = writeInt(target.out, Protocol.ABORT);
                        break;
                    }
                    if (len > Protocol.MAX_CHUNK) throw new IOException("Protocol error: chunk too large");
                    in.readFully(buf, 0, len); // always drain the sender, even if the target died
                    relayed += len;
                    if (targetOk) {
                        try {
                            target.out.writeInt(len);
                            target.out.write(buf, 0, len);
                        } catch (IOException e) {
                            targetOk = false;
                        }
                    }
                }
            } catch (IOException e) { // the sender's connection broke mid-transfer
                if (targetOk) writeInt(target.out, Protocol.ABORT);
                throw e;
            }
        }

        if (aborted) {
            error("Transfer of '" + fileName + "' to '" + to + "' was cancelled");
        } else if (!targetOk) {
            error("Transfer failed: '" + to + "' disconnected during the transfer");
        } else {
            info("File '" + fileName + "' (" + relayed + " bytes) delivered to '" + to + "', waiting for verification");
        }
    }

    private static boolean writeInt(DataOutputStream o, int v) {
        try {
            o.writeInt(v);
            o.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Receiver tells the original sender whether the file was verified. */
    private void handleFileResult(Message m) {
        ClientHandler target = users.get(m.getRecipient());
        if (target != null) target.send(m);
    }

    // ------------------------------------------------------------------ sending helpers

    /** Thread-safe send to this client. @return false if the write failed. */
    public boolean send(Message m) {
        synchronized (writeLock) {
            try {
                FrameIO.writeFrame(out, Protocol.encode(m));
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    private void info(String text) {
        send(new Message(Protocol.INFO, Protocol.SERVER, username, text));
    }

    private void error(String text) {
        send(new Message(Protocol.ERROR, Protocol.SERVER, username, text));
    }

    private void deny(String text) {
        send(new Message(Protocol.FILE_DENIED, Protocol.SERVER, username, text));
    }
}
