package common;

import java.util.regex.Pattern;

/**
 * Protocol definition shared by client and server.
 *
 * Text frame  : [4-byte length][UTF-8 bytes of "TYPE|SENDER|RECIPIENT|CONTENT"]
 * Only the first three '|' are separators, so CONTENT may contain any characters.
 *
 * File data   : after a FILE_SEND header frame, the file is sent as a series of
 *               binary chunks: [4-byte len > 0][len raw bytes] ... then
 *               [0] = end of file, or [-1] = transfer aborted.
 */
public final class Protocol {
    private Protocol() {}

    public static final String SEP = "|";
    public static final String SERVER = "SERVER";

    // Client -> Server
    public static final String REGISTER     = "REGISTER";
    public static final String PRIVATE      = "PRIVATE";
    public static final String GROUP_CREATE = "GROUP_CREATE";
    public static final String GROUP_JOIN   = "GROUP_JOIN";
    public static final String GROUP_LEAVE  = "GROUP_LEAVE";
    public static final String GROUP_MSG    = "GROUP_MSG";
    public static final String LIST_USERS   = "LIST_USERS";
    public static final String FILE_SEND    = "FILE_SEND";   // content = filename:size
    public static final String FILE_RESULT  = "FILE_RESULT"; // receiver -> sender (OK:.. / FAIL:..)
    public static final String QUIT         = "QUIT";

    // Server -> Client
    public static final String REGISTER_OK   = "REGISTER_OK";
    public static final String REGISTER_FAIL = "REGISTER_FAIL";
    public static final String PRIVATE_SENT  = "PRIVATE_SENT"; // delivery confirmation
    public static final String USERS         = "USERS";        // content = name@ip,name@ip
    public static final String FILE_READY    = "FILE_READY";
    public static final String FILE_DENIED   = "FILE_DENIED";
    public static final String INFO          = "INFO";
    public static final String ERROR         = "ERROR";

    // File chunk framing
    public static final int MAX_CHUNK   = 64 * 1024;
    public static final int END_OF_FILE = 0;
    public static final int ABORT       = -1;

    /** Usernames and group names: letters (any language incl. Arabic), digits, _ . - */
    private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{M}\\p{N}_.\\-]{1,20}$");

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public static String encode(Message m) {
        if (m.getType().contains(SEP) || m.getSender().contains(SEP) || m.getRecipient().contains(SEP)) {
            throw new IllegalArgumentException("Header fields must not contain '|'");
        }
        return m.getType() + SEP + m.getSender() + SEP + m.getRecipient() + SEP + m.getContent();
    }

    public static Message parse(String raw) {
        String[] p = raw.split("\\|", 4); // limit 4 => content keeps any extra '|'
        if (p.length < 4 || p[0].isEmpty()) {
            throw new IllegalArgumentException("expected TYPE|SENDER|RECIPIENT|CONTENT");
        }
        return new Message(p[0], p[1], p[2], p[3]);
    }
}
