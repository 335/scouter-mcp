package scouter.mcp.scouter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small thread-safe LRU cache for decoded dictionary text keyed by (serverId:type:yyyymmdd:hash).
 * These mappings are immutable for a given day, so caching them across MCP tool calls (process-wide)
 * eliminates repeated GET_TEXT_PACK round-trips during a diagnosis session that keeps drilling into
 * the same services. Null values are cached too (a hash known to have no text), so the sentinel for
 * "not cached" is {@link #containsKey}, not a null return.
 */
public final class LruTextCache {

    private final int max;
    private final LinkedHashMap<String, String> map;

    public LruTextCache(int max) {
        this.max = max;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > LruTextCache.this.max;
            }
        };
    }

    public synchronized boolean containsKey(String key) {
        return map.containsKey(key);
    }

    public synchronized String get(String key) {
        return map.get(key);
    }

    public synchronized void put(String key, String value) {
        map.put(key, value);
    }
}
