package scouter.mcp.tools;

/**
 * JSON input schemas for the MCP tools. These are part of the tool contract presented to the
 * MCP client/LLM and are kept in English (static). Only dynamic, user-facing output (error
 * messages, hints, notes) is localized via {@link scouter.mcp.i18n.Messages}.
 */
public final class Schemas {
    private Schemas() {
    }

    public static final String LIST_OBJECTS = """
        {
          "type": "object",
          "properties": {
            "objType": {"type": "string", "description": "Filter: object type (e.g. tomcat)"},
            "nameLike": {"type": "string", "description": "Filter: substring match on objName"}
          }
        }
        """;

    public static final String GET_COUNTER = """
        {
          "type": "object",
          "properties": {
            "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment (e.g. 'shop-order-api'). Case-insensitive; resolved to all matching instances (max 20). PREFER THIS over objHashes."},
            "objHashes": {"type": "array", "items": {"type": "integer"}, "description": "Target object hashes (advanced; prefer objNameLike)"},
            "objType": {"type": "string", "description": "When set, targets all objects of this type (capped at 20 instances)"},
            "counter": {"type": "string", "description": "Counter name (e.g. Cpu, Heap)"},
            "from": {"type": "string", "description": "Start time (e.g. now-1h, 2026-06-29T10:00)"},
            "to": {"type": "string", "description": "End time (e.g. now)"}
          },
          "required": ["counter", "from", "to"]
        }
        """;

    public static final String GET_COUNTER_STAT = """
        {
          "type": "object",
          "properties": {
            "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment (max 20 instances). PREFER THIS over objHashes."},
            "objHashes": {"type": "array", "items": {"type": "integer"}, "description": "Target object hashes (advanced)"},
            "objType": {"type": "string", "description": "Targets all objects of this type (capped at 20 instances)"},
            "counter": {"type": "string", "description": "Counter name (e.g. TPS, Cpu, Heap)"},
            "from": {"type": "string", "description": "Start time (e.g. now-7d, 2026-06-01). Day granularity, up to 31 days"},
            "to": {"type": "string", "description": "End time (e.g. now)"}
          },
          "required": ["counter", "from", "to"]
        }
        """;

    public static final String LIST_COUNTERS = """
        {
          "type": "object",
          "properties": {
            "objType": {"type": "string", "description": "Object type (e.g. tomcat)"},
            "objNameLike": {"type": "string", "description": "Alternative to objType: fuzzy app-name fragment (e.g. 'shop-order-api') — the objType is derived from the first matching object. Use when the objType is unknown."}
          }
        }
        """;

    public static final String SEARCH_XLOG = """
        {
          "type": "object",
          "properties": {
            "from": {"type": "string", "description": "Start time (e.g. now-1h, 2026-06-29T10:00). Start narrow (now-1h) and widen stepwise (now-6h -> now-24h) only when results are empty"},
            "to": {"type": "string", "description": "End time (e.g. now)"},
            "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment as the user says it (e.g. 'shop-order-api'). Case-insensitive; resolved to ALL matching instances (k8s pods, alive first, max 20) and queried across them. PREFER THIS over objHash — objHash embeds the pod name and changes every deploy. If nothing matches, the error lists candidate names."},
            "objHash": {"type": "integer", "description": "Filter: a specific object hash (advanced; prefer objNameLike). Only use a value obtained from list_objects in this session."},
            "service": {"type": "string", "description": "Filter: service (request URL) name. Sloppy input is fine — 'orderDetail', 'GET /api/order/order-detail', 'order-detail POST', or a pasted 'name<POST>' are all normalized (HTTP method extracted, longest token used server-side). Matching is case-sensitive substring; if nothing matches, the result returns serviceCandidates with real service names from the same window — retry with one of those. Advanced: '*' patterns are used as-is."},
            "login": {"type": "string", "description": "Filter: login user (server-side StrMatch). Effective for tracing a specific user's requests; also relaxes the 5-minute unfiltered-window limit."},
            "ip": {"type": "string", "description": "Filter: client IP (server-side StrMatch). Also counts as a server-side filter for the window limit."},
            "desc": {"type": "string", "description": "Filter: XLog description text (server-side StrMatch)."},
            "minElapsedMs": {"type": "integer", "description": "Filter: minimum elapsed time (ms). CLIENT-SIDE: the collector streams every row before this drops them - always combine with a server-side filter (service/objNameLike), or prefer get_service_summary/get_summary for 'which is slow' questions"},
            "onlyError": {"type": "boolean", "description": "Filter: error transactions only. CLIENT-SIDE (rows are streamed then dropped) - combine with a server-side filter, or prefer get_summary category=error"},
            "limit": {"type": "integer", "description": "Max rows to return (default 20, max 200). Keep small at production traffic"}
          },
          "required": ["from", "to"]
        }
        """;

    public static final String LIST_ALERTS = """
        {
          "type": "object",
          "properties": {
            "from": {"type": "string", "description": "Start time (e.g. now-1h, 2026-06-29T10:00)"},
            "to": {"type": "string", "description": "End time (e.g. now)"},
            "level": {"type": "string", "description": "Filter: minimum/specific alert level (INFO, WARN, ERROR, FATAL)"},
            "object": {"type": "string", "description": "Filter: object name"},
            "key": {"type": "string", "description": "Filter: keyword in alert title or message"},
            "limit": {"type": "integer", "description": "Max alerts (default 100, max 1000)"}
          },
          "required": ["from", "to"]
        }
        """;

    public static final String ACTIVE_SERVICES = """
        {
          "type": "object",
          "properties": {
            "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment (e.g. 'shop-order-api'). Resolved to all ALIVE matching instances. PREFER THIS. One of objNameLike/objType/objHash is required."},
            "objType": {"type": "string", "description": "Target object type (all agents of this type)"},
            "objHash": {"type": "integer", "description": "Target a single object hash (advanced; prefer objNameLike)"}
          }
        }
        """;

    public static final String SERVICE_SUMMARY = """
        {
          "type": "object",
          "properties": {
            "from": {"type": "string", "description": "Start time (e.g. now-1h, 2026-06-29T10:00). Start narrow (now-1h) and widen stepwise only when results are empty"},
            "to": {"type": "string", "description": "End time (e.g. now)"},
            "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment (e.g. 'shop-order-api'). Case-insensitive; aggregates over ALL matching instances. PREFER THIS over objHash."},
            "objHash": {"type": "integer", "description": "Filter: a specific object hash (advanced; prefer objNameLike)"},
            "service": {"type": "string", "description": "Filter: service (request URL) name. Sloppy input is normalized like search_xlog; on zero matches, serviceCandidates lists real names"},
            "login": {"type": "string", "description": "Filter: login user (server-side StrMatch)"},
            "ip": {"type": "string", "description": "Filter: client IP (server-side StrMatch)"},
            "desc": {"type": "string", "description": "Filter: XLog description text (server-side StrMatch)"},
            "minElapsedMs": {"type": "integer", "description": "Filter: minimum elapsed time (ms). CLIENT-SIDE: rows are streamed from the collector before this drops them - combine with a server-side filter (service/objNameLike)"},
            "onlyError": {"type": "boolean", "description": "Filter: error transactions only. CLIENT-SIDE - combine with a server-side filter, or prefer get_summary category=error"}
          },
          "required": ["from", "to"]
        }
        """;

    public static final String LIST_THREADS = """
        {
          "type": "object",
          "properties": {
            "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment (e.g. 'shop-order-api'). Resolved to alive instances, max 5 (a JVM holds hundreds of threads). One of objNameLike/objHash is required."},
            "objHash": {"type": "integer", "description": "Target a single object hash (advanced; prefer objNameLike)"}
          }
        }
        """;

    public static final String GET_THREAD_DETAIL = """
        {
          "type": "object",
          "properties": {
            "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment. Resolved to the first alive matching instance. One of objNameLike/objHash is required."},
            "objHash": {"type": "integer", "description": "Target object hash (advanced; prefer objNameLike)"},
            "txid": {"type": "string", "description": "ACTIVE transaction id from get_active_services or list_threads rows (decimal or Hexa32). The agent locates the thread by this; a finished txid returns state='end' - fetch a fresh one and retry immediately."},
            "id": {"type": "integer", "description": "Thread id from list_threads/get_active_services (optional; enables cpu/lock/stack fields)"}
          },
          "required": ["txid"]
        }
        """;

    public static final String GET_SUMMARY = """
        {
          "type": "object",
          "properties": {
            "category": {"type": "string", "enum": ["service", "sql", "apiCall", "ip", "userAgent", "error", "alert"], "description": "What to aggregate: service (per-URL), sql (per-statement - 'which SQL is slow/hot'), apiCall (outbound calls), ip (client IPs), userAgent, error (error type x service with a sample txid), alert (alert titles)"},
            "from": {"type": "string", "description": "Start time (e.g. now-24h, 2026-06-29T10:00). Daily granularity; up to 31 days"},
            "to": {"type": "string", "description": "End time (e.g. now)"},
            "objType": {"type": "string", "description": "Filter: object type (e.g. tomcat). Server-side"},
            "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment, fanned out over matching instances"},
            "objHash": {"type": "integer", "description": "Filter: a single object hash (advanced)"}
          },
          "required": ["category", "from", "to"]
        }
        """;

    public static final String GET_OBJECT_ENV = """
        {
          "type": "object",
          "properties": {
            "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment. Resolved to the first alive matching instance. One of objNameLike/objHash is required."},
            "objHash": {"type": "integer", "description": "Target object hash (advanced; prefer objNameLike)"},
            "keyLike": {"type": "string", "description": "Filter: case-insensitive substring on property keys (e.g. 'mem', 'timezone', 'version'). Recommended - the full property set is large."}
          }
        }
        """;

    public static final String GET_XLOG_DETAIL = """
        {
          "type": "object",
          "properties": {
            "txid": {"type": "string", "description": "Transaction ID — either the raw decimal long OR the Hexa32 string shown in the Scouter client (e.g. 'z3st744n3d2p6q'). Both are accepted."},
            "date": {"type": "string", "description": "Date the transaction occurred, yyyyMMdd (e.g. '20260701'). IMPORTANT: the collector stores XLogs in per-day partitions keyed by this date, so the lookup fails silently if the date is wrong. When calling from a search_xlog result, pass the date portion of that row's endTimeIso. If omitted, derived from 'at' or today."},
            "at": {"type": "string", "description": "Alternative to 'date': an ISO timestamp or relative expression (e.g. '2026-07-01T17:28:00+09:00', 'now-1h'). The date is extracted automatically. Use the endTimeIso value from the search_xlog result to avoid wrong-date misses."},
            "includeBindParams": {"type": "boolean", "description": "Whether to include SQL bind parameters (default true; an operator may disable them server-side). Bind values appear ONLY in the result's sqls[].bindParams field, matched positionally to the @{N} placeholders in sqls[].sql — the steps[] entries never carry binds, so do not conclude 'binds not captured' from steps alone."}
          },
          "required": ["txid"]
        }
        """;

    public static final String GET_XLOG_BY_GXID = """
        {
          "type": "object",
          "properties": {
            "gxid": {"type": "string", "description": "Global transaction ID — either the raw decimal long OR the Hexa32 string shown in the Scouter client. Both are accepted."},
            "date": {"type": "string", "description": "Date yyyyMMdd. Same per-day partition requirement as get_xlog_detail: use the date portion of endTimeIso from any row in the same transaction group. If omitted, derived from 'at' or today."},
            "at": {"type": "string", "description": "Alternative to 'date': ISO timestamp or relative expression (e.g. endTimeIso from a search_xlog row). The date is extracted automatically."}
          },
          "required": ["gxid"]
        }
        """;
}
