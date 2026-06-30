package scouter.mcp.policy;

/**
 * 운영 스케일(5분에 수십만 XLog) 대비 리소스/토큰 폭발 방지 정책 상수.
 * Scouter 컬렉터 서버 부하, 네트워크 전송량, MCP 힙, LLM 컨텍스트를 동시에 제한한다.
 */
public final class Limits {

    private Limits() {
    }

    // --- search_xlog ---
    /** limit 미지정 시 기본 반환 건수(토큰 절약). */
    public static final int SEARCH_DEFAULT_LIMIT = 20;
    /** limit 상한. 운영에서 1000행을 LLM 컨텍스트로 넣지 않는다. */
    public static final int SEARCH_MAX_LIMIT = 200;
    /**
     * 스트리밍 수신 중 검사한 Pack 상한. 이 수에 도달하면 소켓을 끊어
     * 컬렉터의 스캔/전송과 MCP 힙 사용을 함께 멈춘다(서버측 service/objHash 필터가
     * 없을 때의 firehose 방어선).
     */
    public static final int SEARCH_SCAN_CAP = 5000;
    /** service/objHash 등 서버측 필터가 없을 때 허용하는 최대 조회 기간(5분). */
    public static final long UNFILTERED_MAX_WINDOW_MS = 5L * 60 * 1000;
    /** 필터 유무와 무관한 절대 최대 조회 기간(24시간). */
    public static final long ABS_MAX_WINDOW_MS = 24L * 60 * 60 * 1000;

    // --- get_counter ---
    /** objType 한 번 조회 시 팬아웃하는 objHash(인스턴스) 상한. */
    public static final int COUNTER_MAX_OBJ = 20;
    /** 카운터 시계열 절대 최대 조회 기간(24시간). */
    public static final long COUNTER_ABS_MAX_WINDOW_MS = 24L * 60 * 60 * 1000;
}
