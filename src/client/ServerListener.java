package client;

import common.FrameIO;
import common.Message;
import common.Protocol;

import java.io.DataInputStream;
import java.io.IOException;

/** Background thread: reads everything the server sends and prints/saves it. */
public class ServerListener extends Thread {
    private final ChatClient client;
    private final DataInputStream in;
    private final HistoryManager history;

    public ServerListener(ChatClient client, DataInputStream in, HistoryManager history) {
        super("server-listener");
        this.client = client;
        this.in = in;
        this.history = history;
    }

    @Override
    public void run() {
        String me = client.getUsername();
        try {
            while (true) {
                Message m;
                try {
                    m = Protocol.parse(FrameIO.readFrame(in));
                } catch (IllegalArgumentException e) {
                    System.out.println("Warning: ignored a malformed message from the server.");
                    continue;
                }
                switch (m.getType()) {
                    case Protocol.PRIVATE:
                        System.out.println("[Private] " + m.getSender() + ": " + m.getContent());
                        history.save(Protocol.PRIVATE, m.getSender(), me, m.getContent());
                        break;
                    case Protocol.PRIVATE_SENT: // server confirmed delivery
                        System.out.println("[Private -> " + m.getRecipient() + "] " + m.getContent());
                        history.save(Protocol.PRIVATE, me, m.getRecipient(), m.getContent());
                        break;
                    case Protocol.GROUP_MSG: // also delivered to the sender
                        System.out.println("[Group " + m.getRecipient() + "] " + m.getSender() + ": " + m.getContent());
                        history.save(Protocol.GROUP_MSG, m.getSender(), m.getRecipient(), m.getContent());
                        break;
                    case Protocol.USERS:
                        printUsers(m.getContent());
                        break;
                    case Protocol.INFO:
                        System.out.println("[Info] " + m.getContent());
                        break;
                    case Protocol.ERROR:
                        System.out.println("[Error] " + m.getContent());
                        break;
                    case Protocol.FILE_READY:
                    case Protocol.FILE_DENIED:
                        client.fileReplies.offer(m); // wakes up FileSender
                        break;
                    case Protocol.FILE_SEND:
                        FileReceiver.receive(in, m, client); // reads the raw bytes from the same stream
                        break;
                    case Protocol.FILE_RESULT:
                        if (m.getContent().startsWith("OK:")) {
                            System.out.println("[File] " + m.getSender() + " received and verified " + m.getContent().substring(3));
                        } else {
                            System.out.println("[File] Transfer to " + m.getSender() + " failed: "
                                    + m.getContent().replaceFirst("^FAIL:", ""));
                        }
                        break;
                    default:
                        System.out.println("[Server] " + m.getContent());
                }
            }
        } catch (IOException e) { // EOF or connection reset
            client.onConnectionLost();
        }
    }

    private void printUsers(String content) {
        if (content.isEmpty()) {
            System.out.println("No users online.");
            return;
        }
        System.out.println("Online users:");
        for (String entry : content.split(",")) {
            int at = entry.lastIndexOf('@');
            String name = at > 0 ? entry.substring(0, at) : entry;
            String ip = at > 0 ? entry.substring(at + 1) : "?";
            System.out.println("  - " + name + "  (" + ip + ")");
        }
    }
}
