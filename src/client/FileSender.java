package client;

import common.Message;
import common.Protocol;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** Sends one file to one online user through the server, using byte streams. */
public final class FileSender {
    private FileSender() {}

    public static void sendFile(ChatClient client, String recipient, String pathText) throws IOException {
        pathText = pathText.trim();
        if (pathText.length() >= 2 && pathText.startsWith("\"") && pathText.endsWith("\"")) {
            pathText = pathText.substring(1, pathText.length() - 1);
        }

        Path path;
        try {
            path = Paths.get(pathText);
        } catch (InvalidPathException e) {
            System.out.println("Error: invalid file path.");
            return;
        }
        if (!Files.exists(path)) {
            System.out.println("Error: file not found: " + path);
            return;
        }
        if (!Files.isRegularFile(path)) {
            System.out.println("Error: not a regular file: " + path);
            return;
        }
        if (!Files.isReadable(path)) {
            System.out.println("Error: no permission to read: " + path);
            return;
        }

        long size = Files.size(path);
        String fileName = path.getFileName().toString();

        InputStream fin;
        try {
            fin = new BufferedInputStream(Files.newInputStream(path));
        } catch (IOException e) {
            System.out.println("Error: cannot open file: " + e.getMessage());
            return;
        }

        try {
            // 1) header frame, then wait for the server to accept it
            client.fileReplies.clear();
            client.send(new Message(Protocol.FILE_SEND, client.getUsername(), recipient, fileName + ":" + size));

            Message reply;
            try {
                reply = client.fileReplies.poll(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (reply == null) {
                throw new IOException("No response from the server for the file transfer");
            }
            if (Protocol.FILE_DENIED.equals(reply.getType())) {
                System.out.println("File transfer refused: " + reply.getContent());
                return;
            }

            // 2) stream the bytes as chunks; hold the write lock so no other frame is mixed in
            System.out.println("Sending '" + fileName + "' (" + size + " bytes) to " + recipient + "...");
            long sent = 0;
            byte[] buf = new byte[Protocol.MAX_CHUNK];
            synchronized (client.writeLock) {
                DataOutputStream out = client.out;
                while (true) {
                    int n;
                    try {
                        n = fin.read(buf);
                    } catch (IOException e) { // file problem in the middle: tell the server to abort
                        out.writeInt(Protocol.ABORT);
                        out.flush();
                        System.out.println("Error: failed while reading the file: " + e.getMessage());
                        return;
                    }
                    if (n == -1) break;
                    out.writeInt(n);
                    out.write(buf, 0, n);
                    sent += n;
                }
                out.writeInt(Protocol.END_OF_FILE);
                out.flush();
            }
            System.out.println("Upload finished (" + sent + " bytes). Waiting for the server/recipient confirmation...");
        } finally {
            try {
                fin.close();
            } catch (IOException ignored) {
            }
        }
    }
}
