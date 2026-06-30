package scouter.mcp.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import scouter.mcp.error.McpError;

/**
 * Minimal server registry shim replacing scouterx.webapp.framework.client.server.ServerManager
 * (Apache License 2.0). Only the lookup behaviour used by the ported net classes is kept;
 * the realtime time-sync daemon thread and javax.validation usage are intentionally dropped.
 */
public final class ServerRegistry {
    private static final Map<Integer, Server> SERVERS = new ConcurrentHashMap<>();

    private ServerRegistry() {
    }

    public static void add(Server server) {
        SERVERS.put(server.getId(), server);
    }

    public static Server get(int id) {
        return SERVERS.get(id);
    }

    public static Server getDefault() {
        for (Server s : SERVERS.values()) {
            return s;
        }
        throw McpError.of(McpError.Code.SCOUTER_CONNECT_FAILED, "no server registered");
    }

    /**
     * Returns the given server if non-null, otherwise the default server. If multiple servers are
     * registered and none is specified, this fails fast.
     */
    public static Server getServerIfNullDefault(Server server) {
        if (server != null) {
            return server;
        }
        if (SERVERS.size() != 1) {
            throw McpError.of(McpError.Code.SCOUTER_CONNECT_FAILED,
                    "multiple servers registered; a target server must be specified");
        }
        return getDefault();
    }
}
