package scouter.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import scouter.mcp.config.Config;
import scouter.mcp.i18n.Messages;
import scouter.mcp.policy.Limits;
import scouter.mcp.scouter.ScouterClient;
import scouter.mcp.scouter.TcpScouterClient;
import scouter.mcp.scouter.dto.SearchXlogParams;
import scouter.mcp.time.TimeRange;
import scouter.mcp.tools.Schemas;
import scouter.mcp.tools.Tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class McpMain {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpMain.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    private McpMain() {
    }

    private static McpSchema.CallToolResult toolError(scouter.mcp.error.McpError e) {
        log.warn(e.toLogLine());
        String json;
        try {
            json = MAPPER.writeValueAsString(java.util.Map.of(
                    "code", e.code().name(),
                    "message", e.getMessage() == null ? "" : e.getMessage(),
                    "hints", e.hints()));
        } catch (Exception ex) {
            json = "{\"code\":\"" + e.code().name() + "\"}";
        }
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(json).build();
    }

    public static void main(String[] args) {
        Config config = Config.fromEnv(System.getenv());
        ScouterClient client = new TcpScouterClient(config);
        client.connect();

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();
        StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);

        McpSchema.Tool listObjects = McpSchema.Tool.builder()
                .name("list_objects")
                .description("List Scouter monitored objects (agents). Filter by objType/nameLike.")
                .inputSchema(jsonMapper, Schemas.LIST_OBJECTS)
                .build();

        McpServerFeatures.SyncToolSpecification listObjectsSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(listObjects)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        String objType = asString(arguments, "objType");
                        String nameLike = asString(arguments, "nameLike");
                        String json = Tools.renderListObjects(client, objType, nameLike);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(json)
                                .build();
                    } catch (scouter.mcp.error.McpError e) {
                        return toolError(e);
                    } catch (IllegalArgumentException e) {
                        return toolError(scouter.mcp.error.McpError.of(
                                scouter.mcp.error.McpError.Code.INVALID_INPUT, e.getMessage()));
                    } catch (Exception e) {
                        return toolError(scouter.mcp.error.McpError.of(
                                scouter.mcp.error.McpError.Code.INTERNAL, String.valueOf(e.getMessage())));
                    }
                })
                .build();

        McpSchema.Tool getCounter = McpSchema.Tool.builder()
                .name("get_counter")
                .description("Query counter time series over a past range. One of objHashes/objType, plus counter/from/to, is required.")
                .inputSchema(jsonMapper, Schemas.GET_COUNTER)
                .build();

        McpServerFeatures.SyncToolSpecification getCounterSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(getCounter)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        List<Integer> objHashes = asIntList(arguments, "objHashes");
                        String objType = asString(arguments, "objType");
                        if (objHashes.isEmpty() && objType == null) {
                            throw scouter.mcp.error.McpError.of(
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT,
                                    Messages.get(config.locale(), "error.counter_obj_required"));
                        }
                        if (objHashes.isEmpty() && objType != null) {
                            objHashes = resolveObjHashesByType(client, objType);
                        }
                        // Fan-out cap: an objType may spread across dozens of instances, multiplying the response. Cap it.
                        int totalObj = objHashes.size();
                        boolean objTruncated = totalObj > Limits.COUNTER_MAX_OBJ;
                        if (objTruncated) {
                            objHashes = objHashes.subList(0, Limits.COUNTER_MAX_OBJ);
                        }
                        String counter = asString(arguments, "counter");
                        long now = System.currentTimeMillis();
                        long fromMillis = TimeRange.parseInstant(asString(arguments, "from"), config.zone(), now);
                        long toMillis = TimeRange.parseInstant(asString(arguments, "to"), config.zone(), now);
                        long windowMs = toMillis - fromMillis;
                        if (windowMs <= 0) {
                            throw scouter.mcp.error.McpError.of(
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT,
                                    Messages.get(config.locale(), "error.from_after_to"));
                        }
                        if (windowMs > Limits.COUNTER_ABS_MAX_WINDOW_MS) {
                            throw scouter.mcp.error.McpError.of(
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT,
                                    Messages.get(config.locale(), "error.counter_window_too_long",
                                            Limits.COUNTER_ABS_MAX_WINDOW_MS / 3600000));
                        }
                        String json = Tools.renderGetCounter(config.locale(), client, objHashes, counter,
                                fromMillis, toMillis);
                        McpSchema.CallToolResult.Builder rb = McpSchema.CallToolResult.builder().addTextContent(json);
                        if (objTruncated) {
                            String note = Messages.get(config.locale(), "note.counter_obj_truncated",
                                    Limits.COUNTER_MAX_OBJ, totalObj);
                            rb.addTextContent(MAPPER.writeValueAsString(java.util.Map.of("note", note)));
                        }
                        return rb.build();
                    } catch (scouter.mcp.error.McpError e) {
                        return toolError(e);
                    } catch (IllegalArgumentException e) {
                        return toolError(scouter.mcp.error.McpError.of(
                                scouter.mcp.error.McpError.Code.INVALID_INPUT, e.getMessage()));
                    } catch (Exception e) {
                        return toolError(scouter.mcp.error.McpError.of(
                                scouter.mcp.error.McpError.Code.INTERNAL, String.valueOf(e.getMessage())));
                    }
                })
                .build();

        McpSchema.Tool listCounters = McpSchema.Tool.builder()
                .name("list_counters")
                .description("List available counter metadata (name/displayName/unit) for a given objType.")
                .inputSchema(jsonMapper, Schemas.LIST_COUNTERS)
                .build();

        McpServerFeatures.SyncToolSpecification listCountersSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(listCounters)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        String objType = asString(arguments, "objType");
                        String json = Tools.renderListCounters(config.locale(), client, objType);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(json)
                                .build();
                    } catch (scouter.mcp.error.McpError e) {
                        return toolError(e);
                    } catch (IllegalArgumentException e) {
                        return toolError(scouter.mcp.error.McpError.of(
                                scouter.mcp.error.McpError.Code.INVALID_INPUT, e.getMessage()));
                    } catch (Exception e) {
                        return toolError(scouter.mcp.error.McpError.of(
                                scouter.mcp.error.McpError.Code.INTERNAL, String.valueOf(e.getMessage())));
                    }
                })
                .build();

        McpSchema.Tool searchXlog = McpSchema.Tool.builder()
                .name("search_xlog")
                .description("Search XLogs (transactions). from/to required. At production traffic, without a service or objHash filter only windows up to 5 minutes are allowed, and results are partial once the scan cap is hit (check truncated/hint). Prefer narrowing by service (substring match) or objHash.")
                .inputSchema(jsonMapper, Schemas.SEARCH_XLOG)
                .build();

        McpServerFeatures.SyncToolSpecification searchXlogSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(searchXlog)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        long now = System.currentTimeMillis();
                        long fromMillis = TimeRange.parseInstant(asString(arguments, "from"), config.zone(), now);
                        long toMillis = TimeRange.parseInstant(asString(arguments, "to"), config.zone(), now);
                        Long objHash = asLong(arguments, "objHash");
                        String service = asString(arguments, "service");
                        Integer minElapsedMs = asInteger(arguments, "minElapsedMs");
                        boolean onlyError = Boolean.TRUE.equals(asBoolean(arguments, "onlyError"));
                        int limit = clampLimit(asInteger(arguments, "limit"));
                        SearchXlogParams params = new SearchXlogParams(
                                fromMillis, toMillis, objHash, service, minElapsedMs, onlyError, limit);
                        String json = Tools.renderSearchXlog(config.locale(), client, params);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(json)
                                .build();
                    } catch (scouter.mcp.error.McpError e) {
                        return toolError(e);
                    } catch (IllegalArgumentException e) {
                        return toolError(scouter.mcp.error.McpError.of(
                                scouter.mcp.error.McpError.Code.INVALID_INPUT, e.getMessage()));
                    } catch (Exception e) {
                        return toolError(scouter.mcp.error.McpError.of(
                                scouter.mcp.error.McpError.Code.INTERNAL, String.valueOf(e.getMessage())));
                    }
                })
                .build();

        McpSchema.Tool getXlogDetail = McpSchema.Tool.builder()
                .name("get_xlog_detail")
                .description("Get a single XLog detail (summary + profile steps/SQL/errors). txid required; bind parameters are masked by default.")
                .inputSchema(jsonMapper, Schemas.GET_XLOG_DETAIL)
                .build();

        McpServerFeatures.SyncToolSpecification getXlogDetailSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(getXlogDetail)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        long txid = requireLong(arguments, "txid");
                        String yyyymmdd = resolveYyyymmdd(arguments, config);
                        boolean includeBindParams = !Boolean.FALSE.equals(asBoolean(arguments, "includeBindParams"));
                        boolean maskSensitive = !Boolean.FALSE.equals(asBoolean(arguments, "maskSensitive"));
                        String json = Tools.renderXlogDetail(client, txid, yyyymmdd, includeBindParams, maskSensitive);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(json)
                                .build();
                    } catch (scouter.mcp.error.McpError e) {
                        return toolError(e);
                    } catch (IllegalArgumentException e) {
                        return toolError(scouter.mcp.error.McpError.of(
                                scouter.mcp.error.McpError.Code.INVALID_INPUT, e.getMessage()));
                    } catch (Exception e) {
                        return toolError(scouter.mcp.error.McpError.of(
                                scouter.mcp.error.McpError.Code.INTERNAL, String.valueOf(e.getMessage())));
                    }
                })
                .build();

        McpSchema.Tool getXlogByGxid = McpSchema.Tool.builder()
                .name("get_xlog_by_gxid")
                .description("List XLogs belonging to the same gxid (global transaction). gxid required.")
                .inputSchema(jsonMapper, Schemas.GET_XLOG_BY_GXID)
                .build();

        McpServerFeatures.SyncToolSpecification getXlogByGxidSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(getXlogByGxid)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        long gxid = requireLong(arguments, "gxid");
                        String yyyymmdd = resolveYyyymmdd(arguments, config);
                        String json = Tools.renderXlogByGxid(config.locale(), client, gxid, yyyymmdd);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(json)
                                .build();
                    } catch (scouter.mcp.error.McpError e) {
                        return toolError(e);
                    } catch (IllegalArgumentException e) {
                        return toolError(scouter.mcp.error.McpError.of(
                                scouter.mcp.error.McpError.Code.INVALID_INPUT, e.getMessage()));
                    } catch (Exception e) {
                        return toolError(scouter.mcp.error.McpError.of(
                                scouter.mcp.error.McpError.Code.INTERNAL, String.valueOf(e.getMessage())));
                    }
                })
                .build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("scouter-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(listObjectsSpec, getCounterSpec, listCountersSpec, searchXlogSpec,
                        getXlogDetailSpec, getXlogByGxidSpec)
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } finally {
                client.close();
            }
        }));
    }

    private static String asString(Map<String, Object> arguments, String key) {
        if (arguments == null) {
            return null;
        }
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static List<Integer> asIntList(Map<String, Object> arguments, String key) {
        List<Integer> out = new ArrayList<>();
        if (arguments == null) {
            return out;
        }
        Object value = arguments.get(key);
        if (!(value instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Number number) {
                out.add(number.intValue());
            } else if (item != null) {
                out.add(Integer.parseInt(item.toString().trim()));
            }
        }
        return out;
    }

    private static Integer asInteger(Map<String, Object> arguments, String key) {
        if (arguments == null) {
            return null;
        }
        Object value = arguments.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : Integer.parseInt(text);
    }

    private static Long asLong(Map<String, Object> arguments, String key) {
        if (arguments == null) {
            return null;
        }
        Object value = arguments.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        // Accept both raw decimal strings and Hexa32 display strings ("z...", "+...") from the Scouter client.
        return scouter.mcp.scouter.Hexa32.toLong(text);
    }

    private static Boolean asBoolean(Map<String, Object> arguments, String key) {
        if (arguments == null) {
            return null;
        }
        Object value = arguments.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return null;
        }
        return Boolean.parseBoolean(value.toString().trim());
    }

    private static long requireLong(Map<String, Object> arguments, String key) {
        Long value = asLong(arguments, key);
        if (value == null) {
            throw scouter.mcp.error.McpError.of(
                    scouter.mcp.error.McpError.Code.INVALID_INPUT, key + " is required");
        }
        return value;
    }

    // Prefer date(yyyyMMdd); otherwise derive from at(relative/ISO); if neither, use today in the configured zone.
    private static String resolveYyyymmdd(Map<String, Object> arguments, Config config) {
        String date = asString(arguments, "date");
        if (date != null) {
            return date;
        }
        long now = System.currentTimeMillis();
        String at = asString(arguments, "at");
        long millis = at != null ? TimeRange.parseInstant(at, config.zone(), now) : now;
        return TimeRange.yyyymmdd(millis, config.zone());
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return Limits.SEARCH_DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, Limits.SEARCH_MAX_LIMIT));
    }

    private static List<Integer> resolveObjHashesByType(ScouterClient client, String objType) {
        List<Integer> out = new ArrayList<>();
        client.listObjects().stream()
                .filter(o -> objType.equalsIgnoreCase(o.objType()))
                .forEach(o -> out.add(o.objHash()));
        return out;
    }
}
