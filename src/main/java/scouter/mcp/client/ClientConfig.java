package scouter.mcp.client;

/**
 * Net-related configuration shim replacing scouterx.webapp.framework.configure.ConfigureAdaptor /
 * ConfigureManager. Holds only the values the ported net/server classes read.
 *
 * Defaults copied from scouter.webapp v2.20.0 StandAloneConfigure (Apache License 2.0):
 *   scouterx.webapp.framework.configure.StandAloneConfigure
 */
public final class ClientConfig {
    // Copied from scouter.webapp Configure defaults (net-related only).
    public static final int NET_TCP_CLIENT_POOL_SIZE = 12;

    // --- Added to satisfy ported classes ---
    // Server.connPool size: StandAloneConfigure.net_webapp_tcp_client_pool_size (apply default = 100).
    // We cap to the smaller MCP-oriented pool size (NET_TCP_CLIENT_POOL_SIZE) for our headless usage.
    public static final int NET_WEBAPP_TCP_CLIENT_POOL_SIZE = NET_TCP_CLIENT_POOL_SIZE;
    // ConnectionPool stale timeout: StandAloneConfigure.apply() reads net_webapp_tcp_client_pool_timeout default 15000.
    public static final int NET_WEBAPP_TCP_CLIENT_POOL_TIMEOUT_MS = 15000;
    // TcpProxy.realClose trace flag: StandAloneConfigure._trace default false.
    public static final boolean TRACE = false;

    /**
     * Minimum socket read timeout for our read-only client. The collector advertises a short
     * SO_TIMEOUT via so_time_out (~8s) tuned for its own agents' fast request/response, but our tools
     * occasionally run wide or sparse XLog scans where the collector legitimately streams nothing for
     * many seconds. Read-timing out at 8s turned those into spurious "Read timed out" failures.
     */
    public static final int STREAM_READ_TIMEOUT_MS = 30000;
    /** Evict pooled connections this many ms before the collector's idle-close (which tracks so_time_out). */
    static final int POOL_STALE_SAFETY_MS = 3000;
    /** Floor for the derived pool-stale timeout, so eviction still happens for tiny/absent so_time_out. */
    static final int POOL_STALE_MIN_MS = 2000;

    private ClientConfig() {
    }

    /**
     * Socket read timeout to apply after login, given the collector-reported so_time_out. Honors a
     * larger collector value but never drops below {@link #STREAM_READ_TIMEOUT_MS}.
     */
    public static int effectiveReadTimeoutMs(int collectorSoTimeoutMs) {
        return Math.max(collectorSoTimeoutMs, STREAM_READ_TIMEOUT_MS);
    }

    /**
     * Pool-eviction threshold to apply after login. Sits a safety margin below the collector's idle
     * socket close (~so_time_out) so a pooled socket the collector already dropped is discarded before
     * reuse instead of throwing EOFException; clamped to [{@link #POOL_STALE_MIN_MS},
     * {@link #NET_WEBAPP_TCP_CLIENT_POOL_TIMEOUT_MS}].
     */
    public static int poolStaleTimeoutMs(int collectorSoTimeoutMs) {
        int candidate = collectorSoTimeoutMs - POOL_STALE_SAFETY_MS;
        return Math.max(POOL_STALE_MIN_MS, Math.min(NET_WEBAPP_TCP_CLIENT_POOL_TIMEOUT_MS, candidate));
    }
}
