package scouter.mcp.client;

import scouter.mcp.error.McpError;

import java.util.function.Supplier;

/**
 * Runs a read-only collector action, and on a single session-expiry error (SCOUTER_AUTH_FAILED)
 * performs one recovery (re-login) and retries the action exactly once. The upstream 2-second
 * SessionObserver daemon is not ported, so long-running MCP processes can outlive their session;
 * this recovers transparently without an infinite relogin loop.
 */
public final class SessionRetry {

    private SessionRetry() {
    }

    public static <T> T execute(Supplier<T> action, Runnable recover) {
        try {
            return action.get();
        } catch (McpError e) {
            if (e.code() != McpError.Code.SCOUTER_AUTH_FAILED) {
                throw e;
            }
            recover.run();
            return action.get();
        }
    }
}
