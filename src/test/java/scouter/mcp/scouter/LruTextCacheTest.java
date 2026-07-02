package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LruTextCacheTest {

    @Test
    void storesAndRetrieves() {
        LruTextCache c = new LruTextCache(10);
        c.put("k", "v");
        assertThat(c.containsKey("k")).isTrue();
        assertThat(c.get("k")).isEqualTo("v");
    }

    @Test
    void cachesNullValueDistinctFromAbsent() {
        LruTextCache c = new LruTextCache(10);
        c.put("missing", null); // "known to have no text" must be cached to avoid re-querying
        assertThat(c.containsKey("missing")).isTrue();
        assertThat(c.get("missing")).isNull();
        assertThat(c.containsKey("never-put")).isFalse();
    }

    @Test
    void evictsEldestBeyondCapacity() {
        LruTextCache c = new LruTextCache(2);
        c.put("a", "1");
        c.put("b", "2");
        c.put("c", "3"); // exceeds capacity -> evict eldest "a"
        assertThat(c.containsKey("a")).isFalse();
        assertThat(c.containsKey("b")).isTrue();
        assertThat(c.containsKey("c")).isTrue();
    }

    @Test
    void accessRefreshesRecency() {
        LruTextCache c = new LruTextCache(2);
        c.put("a", "1");
        c.put("b", "2");
        c.get("a");        // "a" is now most-recently used
        c.put("c", "3");   // evict least-recently used -> "b"
        assertThat(c.containsKey("a")).isTrue();
        assertThat(c.containsKey("b")).isFalse();
        assertThat(c.containsKey("c")).isTrue();
    }
}
