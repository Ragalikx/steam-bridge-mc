package steambridge.core;

import java.util.HashMap;
import java.util.Map;

public final class ConnectionRegistry {
    public enum Kind {
        GAME,
        UDP
    }

    public static final class Entry {
        private final Object owner;
        private final Kind kind;

        private Entry(Object owner, Kind kind) {
            this.owner = owner;
            this.kind = kind;
        }

        public Object owner() {
            return owner;
        }

        public Kind kind() {
            return kind;
        }
    }

    private final Map<Integer, Entry> entries = new HashMap<Integer, Entry>();

    public synchronized void register(int handle, Object owner, Kind kind) {
        if (handle == 0 || owner == null || kind == null) throw new IllegalArgumentException();
        Entry previous = entries.get(handle);
        if (previous != null && previous.owner != owner) {
            throw new IllegalStateException("Connection already has an owner");
        }
        entries.put(handle, new Entry(owner, kind));
    }

    public synchronized boolean owns(int handle, Object owner) {
        Entry entry = entries.get(handle);
        return entry != null && entry.owner == owner;
    }

    public synchronized Entry get(int handle) {
        return entries.get(handle);
    }

    public synchronized boolean remove(int handle, Object owner) {
        if (!owns(handle, owner)) return false;
        entries.remove(handle);
        return true;
    }

    public synchronized void remove(int handle) {
        entries.remove(handle);
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }
}
