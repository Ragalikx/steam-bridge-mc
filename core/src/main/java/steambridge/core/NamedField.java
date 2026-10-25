package steambridge.core;

import java.lang.reflect.Field;
import java.util.Arrays;

public final class NamedField<T> {
    private final Class<?> owner;
    private final Class<T> type;
    private final String[] names;
    private volatile Field resolved;

    public NamedField(Class<?> owner, Class<T> type, String... names) {
        this.owner = owner;
        this.type = type;
        this.names = names.clone();
        if (names.length == 0) throw new IllegalArgumentException("Field names required");
    }

    private Field resolve() {
        Field field = resolved;
        if (field != null) return field;
        synchronized (this) {
            if (resolved != null) return resolved;
            for (String name : names) {
                try {
                    field = owner.getDeclaredField(name);
                    Class<?> actual = field.getType();
                    boolean matches =
                            actual == type
                                    || (type == Boolean.class && actual == boolean.class)
                                    || (type == Integer.class && actual == int.class);
                    if (!matches) throw new IllegalStateException("Wrong GUI field type: " + this);
                    field.setAccessible(true);
                    resolved = field;
                    return field;
                } catch (NoSuchFieldException absent) {
                }
            }
            throw new IllegalStateException("Missing GUI field: " + this);
        }
    }

    public T get(Object instance) {
        try {
            return type.cast(resolve().get(instance));
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read GUI field: " + this, failure);
        }
    }

    public void set(Object instance, T value) {
        try {
            resolve().set(instance, value);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot write GUI field: " + this, failure);
        }
    }

    @Override
    public String toString() {
        return owner.getName() + Arrays.toString(names);
    }
}
