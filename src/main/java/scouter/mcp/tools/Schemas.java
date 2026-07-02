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

    public static final String LIST_COUNTERS = """
        {
          "type": "object",
          "properties": {
            "objType": {"type": "string", "description": "Object type (e.g. tomcat)"}
          },
          "required": ["objType"]
        }
        """;

    public static final String SEARCH_XLOG = """
        {
          "type": "object",
          "properties": {
            "from": {"type": "string", "description": "Start time (e.g. now-1h, 2026-06-29T10:00)"},
            "to": {"type": "string", "description": "End time (e.g. now)"},
            "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment as the user says it (e.g. 'shop-order-api'). Case-insensitive; resolved to ALL matching instances (k8s pods, alive first, max 20) and queried across them. PREFER THIS over objHash — objHash embeds the pod name and changes every deploy. If nothing matches, the error lists candidate names."},
            "objHash": {"type": "integer", "description": "Filter: a specific object hash (advanced; prefer objNameLike). Only use a value obtained from list_objects in this session."},
            "service": {"type": "string", "description": "Filter: service name. Substring match by default (server-side StrMatch), so a short token is enough. Example: 'search-order-info-grade' matches '/api/order/ext/order-info/search-order-info-grade<POST>'. Do not guess the full service name. Advanced: if you include '*', the pattern is used as-is."},
            "login": {"type": "string", "description": "Filter: login user (server-side StrMatch). Effective for tracing a specific user's requests; also relaxes the 5-minute unfiltered-window limit."},
            "ip": {"type": "string", "description": "Filter: client IP (server-side StrMatch). Also counts as a server-side filter for the window limit."},
            "desc": {"type": "string", "description": "Filter: XLog description text (server-side StrMatch)."},
            "minElapsedMs": {"type": "integer", "description": "Filter: minimum elapsed time (ms). Client-side filter"},
            "onlyError": {"type": "boolean", "description": "Filter: error transactions only. Client-side filter"},
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
            "from": {"type": "string", "description": "Start time (e.g. now-1h, 2026-06-29T10:00)"},
            "to": {"type": "string", "description": "End time (e.g. now)"},
            "objNameLike": {"type": "string", "description": "Fuzzy target: app-name fragment (e.g. 'shop-order-api'). Case-insensitive; aggregates over ALL matching instances. PREFER THIS over objHash."},
            "objHash": {"type": "integer", "description": "Filter: a specific object hash (advanced; prefer objNameLike)"},
            "service": {"type": "string", "description": "Filter: service name (substring match, server-side StrMatch)"},
            "login": {"type": "string", "description": "Filter: login user (server-side StrMatch)"},
            "ip": {"type": "string", "description": "Filter: client IP (server-side StrMatch)"},
            "desc": {"type": "string", "description": "Filter: XLog description text (server-side StrMatch)"},
            "minElapsedMs": {"type": "integer", "description": "Filter: minimum elapsed time (ms). Client-side filter"},
            "onlyError": {"type": "boolean", "description": "Filter: error transactions only. Client-side filter"}
          },
          "required": ["from", "to"]
        }
        """;

    public static final String GET_XLOG_DETAIL = """
        {
          "type": "object",
          "properties": {
            "txid": {"type": "string", "description": "Transaction ID — either the raw decimal long OR the Hexa32 string shown in the Scouter client (e.g. 'z3st744n3d2p6q'). Both are accepted."},
            "date": {"type": "string", "description": "Date the transaction occurred, yyyyMMdd (e.g. '20260701'). IMPORTANT: the collector stores XLogs in per-day partitions keyed by this date, so the lookup fails silently if the date is wrong. When calling from a search_xlog result, pass the date portion of that row's endTimeIso. If omitted, derived from 'at' or today."},
            "at": {"type": "string", "description": "Alternative to 'date': an ISO timestamp or relative expression (e.g. '2026-07-01T17:28:00+09:00', 'now-1h'). The date is extracted automatically. Use the endTimeIso value from the search_xlog result to avoid wrong-date misses."},
            "includeBindParams": {"type": "boolean", "description": "Whether to include bind parameters (default true)"}
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
