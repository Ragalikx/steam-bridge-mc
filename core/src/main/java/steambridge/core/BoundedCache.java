package steambridge.core;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class BoundedCache<K, V> extends AbstractMap<K, V> {
    private final int capacity;
    private final Consumer<V> onEviction;
    private final LinkedHashMap<K, V> entries = new LinkedHashMap<K, V>(16, 0.75f, true);

    public BoundedCache(int capacity) {
        this(capacity, value -> {});
    }

    public BoundedCache(int capacity, Consumer<V> onEviction) {
        if (capacity <= 0 || onEviction == null) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.onEviction = onEviction;
    }

    @Override
    public synchronized V get(Object key) {
        return entries.get(key);
    }

    @Override
    public synchronized boolean containsKey(Object key) {
        return entries.containsKey(key);
    }

    @Override
    public synchronized int size() {
        return entries.size();
    }

    @Override
    public synchronized V remove(Object key) {
        return entries.remove(key);
    }

    @Override
    public V put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException();
        V previous, evicted = null;
        synchronized (this) {
            previous = entries.put(key, value);
            if (entries.size() > capacity) {
                Map.Entry<K, V> oldest = entries.entrySet().iterator().next();
                evicted = oldest.getValue();
                entries.remove(oldest.getKey());
            }
        }
        if (previous != null && !previous.equals(value)) onEviction.accept(previous);
        if (evicted != null) onEviction.accept(evicted);
        return previous;
    }

    @Override
    public synchronized Set<Map.Entry<K, V>> entrySet() {
        Set<Map.Entry<K, V>> copy = new LinkedHashSet<Map.Entry<K, V>>();
        for (Map.Entry<K, V> entry : entries.entrySet())
            copy.add(new SimpleImmutableEntry<K, V>(entry));
        return copy;
    }

    @Override
    public void clear() {
        java.util.List<V> values;
        synchronized (this) {
            values = new java.util.ArrayList<V>(entries.values());
            entries.clear();
        }
        for (V value : values) onEviction.accept(value);
    }
}
