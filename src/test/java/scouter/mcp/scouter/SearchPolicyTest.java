package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;
import scouter.mcp.config.Config;
import scouter.mcp.error.McpError;
import scouter.mcp.policy.Limits;
import scouter.mcp.scouter.dto.SearchXlogParams;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression test for the search_xlog resource/token explosion guard
 * (pure validation before any network call).
 * The guard runs before connecting to the collector, so it is verified without connect().
 */
class SearchPolicyTest {

    private TcpScouterClient client() {
        Config c = Config.fromEnv(Map.of(
                "SCOUTER_COLLECTOR_HOST", "h", "SCOUTER_COLLECTOR_PORT", "6100",
                "SCOUTER_USER", "u", "SCOUTER_PASSWORD", "p"));
        return new TcpScouterClient(c);
    }

    @Test
    void rejectsUnfilteredWindowOverFiveMinutes() {
        long now = 1_000_000_000_000L;
        long from = now - (Limits.UNFILTERED_MAX_WINDOW_MS + 1000); // over 5 minutes, no filter
        // Assert on the error CODE (locale-independent), not the localized message text.
        assertThatThrownBy(() ->
                client().searchXlog(new SearchXlogParams(from, now, null, null, null, null, null, null, null, false, 20)))
                .isInstanceOf(McpError.class)
                .matches(e -> ((McpError) e).code() == McpError.Code.INVALID_INPUT);
    }

    @Test
    void allowsUnfilteredWithinFiveMinutes() {
        // Within 5 minutes + no filter, the guard should pass (even if it later fails at the network stage, that is not the McpError guard).
        long now = 1_000_000_000_000L;
        long from = now - Limits.UNFILTERED_MAX_WINDOW_MS; // exactly 5 minutes
        assertThatThrownBy(() ->
                client().searchXlog(new SearchXlogParams(from, now, null, null, null, null, null, null, null, false, 20)))
                .satisfiesAnyOf(
                        ex -> assertThat(ex).isInstanceOf(McpError.class)
                                .matches(e -> ((McpError) e).code() != McpError.Code.INVALID_INPUT),
                        ex -> assertThat(ex).isNotInstanceOf(McpError.class));
    }

    @Test
    void allowsLongWindowWhenObjHashFilterPresent() {
        long now = 1_000_000_000_000L;
        long from = now - 6L * 60 * 60 * 1000; // 6 hours, objHash filter present -> passes the window guard
        assertThatThrownBy(() ->
                client().searchXlog(new SearchXlogParams(from, now, 123L, null, null, null, null, null, null, false, 20)))
                .satisfiesAnyOf(
                        ex -> assertThat(ex).isInstanceOf(McpError.class)
                                .matches(e -> ((McpError) e).code() != McpError.Code.INVALID_INPUT),
                        ex -> assertThat(ex).isNotInstanceOf(McpError.class));
    }

    @Test
    void rejectsWindowOverAbsoluteMax() {
        long now = 1_000_000_000_000L;
        long from = now - (Limits.ABS_MAX_WINDOW_MS + 1000); // over 24 hours (rejected even with a filter)
        assertThatThrownBy(() ->
                client().searchXlog(new SearchXlogParams(from, now, 123L, null, null, null, null, null, null, false, 20)))
                .isInstanceOf(McpError.class)
                .matches(e -> ((McpError) e).code() == McpError.Code.INVALID_INPUT);
    }

    @Test
    void rejectsNonPositiveWindow() {
        long now = 1_000_000_000_000L;
        assertThatThrownBy(() ->
                client().searchXlog(new SearchXlogParams(now, now, 123L, null, null, null, null, null, null, false, 20)))
                .isInstanceOf(McpError.class);
    }
}
