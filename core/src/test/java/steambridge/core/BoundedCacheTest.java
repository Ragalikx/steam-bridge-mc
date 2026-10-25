package steambridge.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class BoundedCacheTest {
    @Test
    public void evictionFollowsAccessAndReleasesExactResource() {
        List<String> released = new ArrayList<String>();
        BoundedCache<Long, String> cache = new BoundedCache<Long, String>(2, released::add);
        cache.put(1L, "old-1");
        cache.put(2L, "old-2");
        assertEquals("old-1", cache.get(1L));
        cache.put(3L, "new-3");
        assertFalse(cache.containsKey(2L));
        assertEquals(java.util.Arrays.asList("old-2"), released);
        cache.put(1L, "new-1");
        assertEquals(java.util.Arrays.asList("old-2", "old-1"), released);
        assertEquals("new-1", cache.remove(1L));
        cache.clear();
        assertEquals(java.util.Arrays.asList("old-2", "old-1", "new-3"), released);
    }

    @Test
    public void thousandsOfProfilesStayBounded() {
        BoundedCache<Long, String> cache = new BoundedCache<Long, String>(256);
        for (long i = 0; i < 10000; i++) cache.put(i, Long.toString(i));
        assertEquals(256, cache.size());
    }
}
