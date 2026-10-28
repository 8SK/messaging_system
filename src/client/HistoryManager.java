package client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Local message history, one UTF-8 text file per user: history_<username>.txt
 * Line format: DATETIME|TYPE|SENDER|RECIPIENT_OR_GROUP|CONTENT (content escaped)
 */
public class HistoryManager {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Path file;

    public HistoryManager(String username) {
        this.file = Paths.get("history_" + username + ".txt");
    }

    public synchronized void save(String type, String sender, String recipient, String content) {
        String line = String.join("|", LocalDateTime.now().format(TS), type, sender, recipient, escape(content))
                + System.lineSeparator();
        try {
            Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Warning: could not save message history: " + e.getMessage());
        }
    }

    public synchronized void printAll() {
        search(null);
    }

    /** Prints saved messages whose content contains the phrase (null = print all). */
    public synchronized void search(String phrase) {
        if (!Files.exists(file)) {
            System.out.println("No saved history yet.");
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("Error: could not read history file: " + e.getMessage());
            return;
        }
        String needle = phrase == null ? null : phrase.toLowerCase(Locale.ROOT);
        int found = 0;
        for (String line : lines) {
            String[] p = line.split("\\|", 5);
            if (p.length < 5) continue; // skip corrupted line
            String content = unescape(p[4]);
            if (needle != null && !content.toLowerCase(Locale.ROOT).contains(needle)) continue;
            String target = p[1].equals("GROUP_MSG") ? "#" + p[3] : p[3];
            System.out.println("[" + p[0] + "] " + p[2] + " -> " + target + ": " + content);
            found++;
        }
        if (found == 0) {
            System.out.println(needle == null ? "History is empty." : "No saved messages contain \"" + phrase + "\".");
        } else {
            System.out.println("(" + found + " message" + (found == 1 ? "" : "s") + ")");
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
