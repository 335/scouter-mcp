package scouter.mcp.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionPoolTest {

    // The pool evicts a connection whose lastUsed is older than the configured stale timeout, so that
    // sockets the collector has already idle-closed are never handed back out (they would EOF on reuse).

    @Test
    void connectionOlderThanStaleTimeoutIsStale() {
        ConnectionPool pool = new ConnectionPool(4);
        pool.setStaleTimeoutMs(5000);
        long now = 1_000_000L;
        assertThat(pool.isStale(now - 6000, now)).isTrue();
    }

    @Test
    void freshlyUsedConnectionIsNotStale() {
        ConnectionPool pool = new ConnectionPool(4);
        pool.setStaleTimeoutMs(5000);
        long now = 1_000_000L;
        assertThat(pool.isStale(now - 1000, now)).isFalse();
    }

    @Test
    void shorterStaleTimeoutEvictsSooner() {
        ConnectionPool pool = new ConnectionPool(4);
        long now = 1_000_000L;
        pool.setStaleTimeoutMs(3000);
        assertThat(pool.isStale(now - 4000, now)).isTrue();
        pool.setStaleTimeoutMs(15000);
        assertThat(pool.isStale(now - 4000, now)).isFalse();
    }
}
