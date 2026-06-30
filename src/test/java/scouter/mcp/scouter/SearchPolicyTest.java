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
 * search_xlog 리소스/토큰 폭발 방지 가드(네트워크 호출 전 순수 검증) 회귀 테스트.
 * 가드는 컬렉터 접속 전에 동작하므로 connect() 없이 검증된다.
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
        long from = now - (Limits.UNFILTERED_MAX_WINDOW_MS + 1000); // 5분 초과, 필터 없음
        assertThatThrownBy(() ->
                client().searchXlog(new SearchXlogParams(from, now, null, null, null, false, 20)))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("필터");
    }

    @Test
    void allowsUnfilteredWithinFiveMinutes() {
        // 5분 이내 + 필터 없음이면 가드는 통과해야 한다(이후 네트워크 단계에서 실패하더라도 McpError 가드는 아님).
        long now = 1_000_000_000_000L;
        long from = now - Limits.UNFILTERED_MAX_WINDOW_MS; // 정확히 5분
        assertThatThrownBy(() ->
                client().searchXlog(new SearchXlogParams(from, now, null, null, null, false, 20)))
                .satisfiesAnyOf(
                        ex -> assertThat(ex).isInstanceOf(McpError.class)
                                .matches(e -> ((McpError) e).code() != McpError.Code.INVALID_INPUT),
                        ex -> assertThat(ex).isNotInstanceOf(McpError.class));
    }

    @Test
    void allowsLongWindowWhenObjHashFilterPresent() {
        long now = 1_000_000_000_000L;
        long from = now - 6L * 60 * 60 * 1000; // 6시간, objHash 필터 있음 → 기간 가드 통과
        assertThatThrownBy(() ->
                client().searchXlog(new SearchXlogParams(from, now, 123L, null, null, false, 20)))
                .satisfiesAnyOf(
                        ex -> assertThat(ex).isInstanceOf(McpError.class)
                                .matches(e -> ((McpError) e).code() != McpError.Code.INVALID_INPUT),
                        ex -> assertThat(ex).isNotInstanceOf(McpError.class));
    }

    @Test
    void rejectsWindowOverAbsoluteMax() {
        long now = 1_000_000_000_000L;
        long from = now - (Limits.ABS_MAX_WINDOW_MS + 1000); // 24시간 초과(필터 있어도 거부)
        assertThatThrownBy(() ->
                client().searchXlog(new SearchXlogParams(from, now, 123L, null, null, false, 20)))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("24");
    }

    @Test
    void rejectsNonPositiveWindow() {
        long now = 1_000_000_000_000L;
        assertThatThrownBy(() ->
                client().searchXlog(new SearchXlogParams(now, now, 123L, null, null, false, 20)))
                .isInstanceOf(McpError.class);
    }
}
