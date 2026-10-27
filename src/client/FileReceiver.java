package client;

import common.Message;
import common.Protocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Receives file bytes (called by ServerListener right after a FILE_SEND header). */
public final class FileReceiver {
    private FileReceiver() {}

    public static void receive(DataInputStream in, Message header, ChatClient client) throws IOException {
        String from = header.getSender();
        String content = header.getContent();
        int idx = content.lastIndexOf(':');
        String name = content.substring(0, idx);
        long expected = Long.parseLong(content.substring(idx + 1));

        System.out.println("Incoming file '" + name + "' (" + expected + " bytes) from " + from + "...");

        Path target = null;
        OutputStream fileOut = null;
        String error = null;
        try {
            Path dir = Paths.get("downloads_" + client.getUsername());
            Files.createDirectories(dir);
            target = uniquePath(dir, sanitize(name)); // never overwrites an existing file
            fileOut = Files.newOutputStream(target);
        } catch (IOException e) {
            error = "cannot create the file: " + e.getMessage();
        }

        long total = 0;
        boolean aborted = false;
        byte[] buf = new byte[Protocol.MAX_CHUNK];
        try {
            while (true) {
                int len = in.readInt();
                if (len == Protocol.END_OF_FILE) break;
                if (len < 0) {
                    aborted = true;
                    break;
                }
                if (len > Protocol.MAX_CHUNK) throw new IOException("Protocol error: chunk too large");
                in.readFully(buf, 0, len); // always consume the data to stay in sync
                total += len;
                if (fileOut != null) {
                    try {
                        fileOut.write(buf, 0, len);
                    } catch (IOException e) {
                        error = "disk write failed: " + e.getMessage();
                        closeQuietly(fileOut);
                        fileOut = null;
                    }
                }
            }
        } catch (IOException e) { // connection lost in the middle of the file
            closeQuietly(fileOut);
            deleteQuietly(target);
            System.out.println("Error: connection lost while receiving '" + name + "'. Partial file deleted.");
            throw e;
        }

        if (fileOut != null) {
            try {
                fileOut.close();
            } catch (IOException e) {
                error = "disk write failed: " + e.getMessage();
            }
        }

        String result;
        if (error != null) {
            deleteQuietly(target);
            result = "FAIL:" + error;
        } else if (aborted) {
            deleteQuietly(target);
            result = "FAIL:transfer was interrupted";
        } else if (total != expected) {
            deleteQuietly(target);
            result = "FAIL:size mismatch (expected " + expected + " bytes, received " + total + ")";
        } else {
            result = "OK:" + name + " (" + total + " bytes)";
        }

        if (result.startsWith("OK")) {
            System.out.println("File received and verified (" + total + " bytes). Saved as: " + target);
        } else {
            System.out.println("File transfer failed: " + result.substring(5));
        }
        client.trySend(new Message(Protocol.FILE_RESULT, client.getUsername(), from, result));
    }

    /** Keep only the file name part and remove characters that are illegal in file names. */
    private static String sanitize(String name) {
        String n;
        try {
            Path fn = Paths.get(name).getFileName();
            n = fn == null ? "" : fn.toString();
        } catch (InvalidPathException e) {
            n = "";
        }
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "received_file" : n;
    }

    /** report.pdf -> report(1).pdf -> report(2).pdf ... The file is created atomically. */
    private static Path uniquePath(Path dir, String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 0; ; i++) {
            Path candidate = dir.resolve(i == 0 ? name : base + "(" + i + ")" + ext);
            try {
                return Files.createFile(candidate);
            } catch (FileAlreadyExistsException e) {
                // try the next number
            }
        }
    }

    private static void closeQuietly(OutputStream o) {
        try {
            if (o != null) o.close();
        } catch (IOException ignored) {
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            if (p != null) Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
