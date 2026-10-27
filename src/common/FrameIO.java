package common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Length-prefixed text frames (UTF-8). Solves message boundaries on a TCP stream. */
public final class FrameIO {
    private FrameIO() {}

    public static final int MAX_FRAME = 1_000_000; // 1 MB safety limit

    public static void writeFrame(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME) {
            throw new IOException("Message too large (" + bytes.length + " bytes)");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    /** Blocks until a full frame arrives. Throws EOFException if the peer closed. */
    public static String readFrame(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_FRAME) {
            throw new IOException("Invalid frame length: " + len);
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
