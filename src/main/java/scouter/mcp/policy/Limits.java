package scouter.mcp.policy;

/**
 * Policy constants to prevent resource/token explosion at production scale (hundreds of thousands of XLogs per 5 minutes).
 * Simultaneously limits Scouter collector server load, network transfer volume, MCP heap, and LLM context.
 */
public final class Limits {

    private Limits() {
    }

    // --- search_xlog ---
    /** Default number of rows returned when limit is unspecified (token saving). */
    public static final int SEARCH_DEFAULT_LIMIT = 20;
    /** Upper bound for limit. We never feed 1000 rows into the LLM context in production. */
    public static final int SEARCH_MAX_LIMIT = 200;
    /**
     * Upper bound on Packs examined during streaming reception. When this count is reached the socket is closed
     * to stop both the collector's scanning/transfer and MCP heap usage (a firehose defense line for when there is
     * no server-side service/objHash filter).
     */
    public static final int SEARCH_SCAN_CAP = 5000;
    /** Maximum query window allowed when there is no server-side filter such as service/objHash (5 minutes). */
    public static final long UNFILTERED_MAX_WINDOW_MS = 5L * 60 * 1000;
    /**
     * Max object instances an objNameLike target may resolve to per query. A k8s app resolves to one
     * objHash per pod (replicas + rotated pods from past deploys), each queried with its own
     * server-side-filtered pass; this bounds the per-request fan-out. Alive instances are kept first.
     */
    public static final int SEARCH_MAX_OBJ = 20;
    /** Absolute maximum query window regardless of filtering (24 hours). */
    public static final long ABS_MAX_WINDOW_MS = 24L * 60 * 60 * 1000;

    // --- get_service_summary ---
    /**
     * Scan cap for aggregation. Higher than search_xlog's cap because rows are not retained (only
     * per-service counters/elapsed samples accumulate), so a wider sweep is affordable in heap/tokens.
     */
    public static final int SUMMARY_SCAN_CAP = 200_000;
    /** Max distinct services returned (top by count); the rest are dropped with a note. */
    public static final int SUMMARY_MAX_SERVICES = 50;

    // --- get_counter ---
    /** Upper bound on the objHashes (instances) fanned out in a single objType query. */
    public static final int COUNTER_MAX_OBJ = 20;
    /** Absolute maximum query window for counter time series (24 hours). */
    public static final long COUNTER_ABS_MAX_WINDOW_MS = 24L * 60 * 60 * 1000;
    /**
     * Max points returned per counter series. High-resolution counters can yield tens of thousands of
     * points over a day; series exceeding this are bucket-averaged down (summary stats are computed
     * from the full series) to bound LLM tokens.
     */
    public static final int COUNTER_MAX_POINTS = 360;

    // --- response text budgets ---
    /** Max chars of a single SQL text in responses; longer texts get a truncation marker (token bound). */
    public static final int SQL_TEXT_MAX_CHARS = 1500;
    /** Max chars of a single error message text (stack-trace-like messages can reach tens of KB). */
    public static final int ERROR_TEXT_MAX_CHARS = 500;
    /** Max profile steps rendered by get_xlog_detail. Upstream caps blocks (MAX_PROFILE_BLOCK) but not steps. */
    public static final int DETAIL_MAX_STEPS = 150;
    /** Max chars of a thread stack trace in get_thread_detail. */
    public static final int STACK_TEXT_MAX_CHARS = 4000;
    /** Max chars of a single env value in get_object_env (java.class.path can be enormous). */
    public static final int ENV_VALUE_MAX_CHARS = 500;

    // --- fan-out pass budget ---
    /**
     * Hard cap on collector round-trips a single request may fan out to (instances x day segments).
     * Currently unreachable with SEARCH_MAX_OBJ x 2 day segments, but guards the invariant if windows
     * or instance caps ever widen.
     */
    public static final int MAX_QUERY_PASSES = 40;

    // --- list_threads ---
    /** Max alive instances a fuzzy target may fan out to (a JVM can hold hundreds of threads each). */
    public static final int THREAD_MAX_OBJ = 5;
    /** Max thread rows returned per instance (top by cpu); the state histogram always covers all threads. */
    public static final int THREAD_MAX_ROWS = 50;

    // --- get_summary / get_counter_stat (daily pre-aggregated stats) ---
    /** Max calendar days a daily-stat query may span. Cheap per day, but bounded regardless. */
    public static final int DAILY_STAT_MAX_DAYS = 31;
    /** Max rows per get_summary category (top by count). */
    public static final int SUMMARY_TOOL_MAX_ROWS = 50;
}
