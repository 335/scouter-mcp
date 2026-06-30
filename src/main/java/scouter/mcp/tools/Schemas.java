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
            "objHashes": {"type": "array", "items": {"type": "integer"}, "description": "Target object hashes"},
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
            "objHash": {"type": "integer", "description": "Filter: a specific object hash"},
            "service": {"type": "string", "description": "Filter: service name. Substring match by default (server-side StrMatch), so a short token is enough. Example: 'search-order-info-grade' matches '/api/order/ext/order-info/search-order-info-grade<POST>'. Do not guess the full service name. Advanced: if you include '*', the pattern is used as-is."},
            "minElapsedMs": {"type": "integer", "description": "Filter: minimum elapsed time (ms). Client-side filter"},
            "onlyError": {"type": "boolean", "description": "Filter: error transactions only. Client-side filter"},
            "limit": {"type": "integer", "description": "Max rows to return (default 20, max 200). Keep small at production traffic"}
          },
          "required": ["from", "to"]
        }
        """;

    public static final String GET_XLOG_DETAIL = """
        {
          "type": "object",
          "properties": {
            "txid": {"type": "string", "description": "Transaction ID — either the raw decimal long OR the Hexa32 string shown in the Scouter client (e.g. 'z3st744n3d2p6q'). Both are accepted."},
            "date": {"type": "string", "description": "Query date yyyyMMdd. If omitted, derived from at or today"},
            "at": {"type": "string", "description": "Query time (e.g. now-1h, 2026-06-29T10:00). Used to derive the date when date is omitted"},
            "includeBindParams": {"type": "boolean", "description": "Whether to include bind parameters (default true)"},
            "maskSensitive": {"type": "boolean", "description": "Whether to mask sensitive data (default true)"}
          },
          "required": ["txid"]
        }
        """;

    public static final String GET_XLOG_BY_GXID = """
        {
          "type": "object",
          "properties": {
            "gxid": {"type": "string", "description": "Global transaction ID — either the raw decimal long OR the Hexa32 string shown in the Scouter client. Both are accepted."},
            "date": {"type": "string", "description": "Query date yyyyMMdd. If omitted, derived from at or today"},
            "at": {"type": "string", "description": "Query time (e.g. now-1h). Used to derive the date when date is omitted"}
          },
          "required": ["gxid"]
        }
        """;
}
