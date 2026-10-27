package server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe list of online users (username -> handler). */
public class UserRegistry {
    private final ConcurrentHashMap<String, ClientHandler> users = new ConcurrentHashMap<>();

    /** @return false if the username is already taken by a connected client. */
    public boolean register(String name, ClientHandler handler) {
        return users.putIfAbsent(name, handler) == null;
    }

    public void remove(String name, ClientHandler handler) {
        users.remove(name, handler);
    }

    public ClientHandler get(String name) {
        return users.get(name);
    }

    /** Protocol format: name@ip,name@ip */
    public String toProtocolString() {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, ClientHandler> e : users.entrySet()) {
            parts.add(e.getKey() + "@" + e.getValue().getIp());
        }
        return String.join(",", parts);
    }

    /** Human readable, for server logs. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, ClientHandler> e : users.entrySet()) {
            parts.add(e.getKey() + " (" + e.getValue().getIp() + ")");
        }
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }
}
