package server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Groups and memberships, kept in memory only while the server runs. */
public class GroupManager {
    private final Map<String, Set<String>> groups = new HashMap<>();

    /** @return false if the group already exists. Creator becomes a member. */
    public synchronized boolean create(String group, String user) {
        if (groups.containsKey(group)) return false;
        Set<String> members = new HashSet<>();
        members.add(user);
        groups.put(group, members);
        return true;
    }

    /** @return null on success, otherwise an error message. */
    public synchronized String join(String group, String user) {
        Set<String> members = groups.get(group);
        if (members == null) return "Group '" + group + "' does not exist";
        if (!members.add(user)) return "You are already a member of '" + group + "'";
        return null;
    }

    /** @return null on success, otherwise an error message. */
    public synchronized String leave(String group, String user) {
        Set<String> members = groups.get(group);
        if (members == null) return "Group '" + group + "' does not exist";
        if (!members.remove(user)) return "You are not a member of '" + group + "'";
        return null;
    }

    /** @return a copy of the member set, or null if the group does not exist. */
    public synchronized Set<String> members(String group) {
        Set<String> members = groups.get(group);
        return members == null ? null : new HashSet<>(members);
    }

    /** Called when a user disconnects. */
    public synchronized void removeUser(String user) {
        for (Set<String> members : groups.values()) {
            members.remove(user);
        }
    }
}
