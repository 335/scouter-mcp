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
    public static final int NET_TCP_CLIENT_SO_TIMEOUT_MS = 30000;
    public static final int NET_TCP_CLIENT_POOL_SIZE = 12;
    public static final int NET_TCP_CLIENT_POOL_TIMEOUT_MS = 30000;
    public static final int NET_TCP_CLIENT_CONNECTION_TIMEOUT_MS = 3000;
    public static final String NET_LOCAL_UDP_ADDR = null;

    // --- Added to satisfy ported classes ---
    // Server.connPool size: StandAloneConfigure.net_webapp_tcp_client_pool_size (apply default = 100).
    // We cap to the smaller MCP-oriented pool size (NET_TCP_CLIENT_POOL_SIZE) for our headless usage.
    public static final int NET_WEBAPP_TCP_CLIENT_POOL_SIZE = NET_TCP_CLIENT_POOL_SIZE;
    // ConnectionPool stale timeout: StandAloneConfigure.apply() reads net_webapp_tcp_client_pool_timeout default 15000.
    public static final int NET_WEBAPP_TCP_CLIENT_POOL_TIMEOUT_MS = 15000;
    // TcpProxy.realClose trace flag: StandAloneConfigure._trace default false.
    public static final boolean TRACE = false;

    private ClientConfig() {
    }
}
