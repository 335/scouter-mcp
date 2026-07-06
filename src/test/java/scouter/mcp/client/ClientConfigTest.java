package scouter.mcp.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientConfigTest {

    // effectiveReadTimeoutMs: the collector advertises a short SO_TIMEOUT (~8s) tuned for its own
    // agents, but our read-only client runs wide/sparse scans where nothing arrives for many seconds.
    // We must never read-timeout below the stream floor, while honoring a larger collector value.

    @Test
    void readTimeoutRaisesShortCollectorValueToStreamFloor() {
        assertThat(ClientConfig.effectiveReadTimeoutMs(8000))
                .isEqualTo(ClientConfig.STREAM_READ_TIMEOUT_MS);
    }

    @Test
    void readTimeoutKeepsLargerCollectorValue() {
        int large = ClientConfig.STREAM_READ_TIMEOUT_MS + 60000;
        assertThat(ClientConfig.effectiveReadTimeoutMs(large)).isEqualTo(large);
    }

    @Test
    void readTimeoutHandlesMissingCollectorValue() {
        assertThat(ClientConfig.effectiveReadTimeoutMs(0)).isEqualTo(ClientConfig.STREAM_READ_TIMEOUT_MS);
    }

    // poolStaleTimeoutMs: pooled connections must be evicted a few seconds BEFORE the collector closes
    // its idle sockets (idle-close tracks so_time_out), so a reused socket is never one the collector
    // already dropped (which surfaces as an immediate EOFException).

    @Test
    void poolStaleSitsBelowCollectorIdleClose() {
        int stale = ClientConfig.poolStaleTimeoutMs(8000);
        assertThat(stale).isLessThan(8000);
        assertThat(stale).isGreaterThanOrEqualTo(2000);
    }

    @Test
    void poolStaleNeverExceedsDefaultForLargeCollectorValue() {
        assertThat(ClientConfig.poolStaleTimeoutMs(300000))
                .isEqualTo(ClientConfig.NET_WEBAPP_TCP_CLIENT_POOL_TIMEOUT_MS);
    }

    @Test
    void poolStaleClampsToMinForTinyOrMissingCollectorValue() {
        assertThat(ClientConfig.poolStaleTimeoutMs(0)).isEqualTo(2000);
        assertThat(ClientConfig.poolStaleTimeoutMs(1000)).isEqualTo(2000);
    }
}
