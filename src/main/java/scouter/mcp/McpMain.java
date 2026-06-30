package scouter.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import scouter.mcp.config.Config;
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

    private McpMain() {
    }

    public static void main(String[] args) {
        Config config = Config.fromEnv(System.getenv());
        ScouterClient client = new TcpScouterClient(config);
        client.connect();

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();
        StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);

        McpSchema.Tool listObjects = McpSchema.Tool.builder()
                .name("list_objects")
                .description("Scouter 수집 대상 오브젝트(에이전트) 목록을 조회한다. objType/nameLike로 필터링 가능")
                .inputSchema(jsonMapper, Schemas.LIST_OBJECTS)
                .build();

        McpServerFeatures.SyncToolSpecification listObjectsSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(listObjects)
                .callHandler((exchange, request) -> {
                    Map<String, Object> arguments = request.arguments();
                    String objType = asString(arguments, "objType");
                    String nameLike = asString(arguments, "nameLike");
                    String json = Tools.renderListObjects(client, objType, nameLike);
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(json)
                            .build();
                })
                .build();

        McpSchema.Tool getCounter = McpSchema.Tool.builder()
                .name("get_counter")
                .description("과거 구간 카운터 시계열을 조회한다. objHashes 또는 objType 중 하나, counter/from/to 필수")
                .inputSchema(jsonMapper, Schemas.GET_COUNTER)
                .build();

        McpServerFeatures.SyncToolSpecification getCounterSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(getCounter)
                .callHandler((exchange, request) -> {
                    Map<String, Object> arguments = request.arguments();
                    List<Integer> objHashes = asIntList(arguments, "objHashes");
                    String objType = asString(arguments, "objType");
                    if (objHashes.isEmpty() && objType != null) {
                        objHashes = resolveObjHashesByType(client, objType);
                    }
                    String counter = asString(arguments, "counter");
                    long now = System.currentTimeMillis();
                    long fromMillis = TimeRange.parseInstant(asString(arguments, "from"), config.zone(), now);
                    long toMillis = TimeRange.parseInstant(asString(arguments, "to"), config.zone(), now);
                    String json = Tools.renderGetCounter(client, objHashes, counter, fromMillis, toMillis);
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(json)
                            .build();
                })
                .build();

        McpSchema.Tool listCounters = McpSchema.Tool.builder()
                .name("list_counters")
                .description("주어진 objType에서 사용 가능한 카운터 메타(name/displayName/unit)를 조회한다")
                .inputSchema(jsonMapper, Schemas.LIST_COUNTERS)
                .build();

        McpServerFeatures.SyncToolSpecification listCountersSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(listCounters)
                .callHandler((exchange, request) -> {
                    Map<String, Object> arguments = request.arguments();
                    String objType = asString(arguments, "objType");
                    String json = Tools.renderListCounters(client, objType);
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(json)
                            .build();
                })
                .build();

        McpSchema.Tool searchXlog = McpSchema.Tool.builder()
                .name("search_xlog")
                .description("XLog(트랜잭션) 목록을 검색한다. from/to 필수, objHash/service/minElapsedMs/onlyError/limit로 필터링")
                .inputSchema(jsonMapper, Schemas.SEARCH_XLOG)
                .build();

        McpServerFeatures.SyncToolSpecification searchXlogSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(searchXlog)
                .callHandler((exchange, request) -> {
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
                    String json = Tools.renderSearchXlog(client, params);
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(json)
                            .build();
                })
                .build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("scouter-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(listObjectsSpec, getCounterSpec, listCountersSpec, searchXlogSpec)
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
        return text.isEmpty() ? null : Long.parseLong(text);
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

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return 100;
        }
        return Math.max(1, Math.min(limit, 1000));
    }

    private static List<Integer> resolveObjHashesByType(ScouterClient client, String objType) {
        List<Integer> out = new ArrayList<>();
        client.listObjects().stream()
                .filter(o -> objType.equalsIgnoreCase(o.objType()))
                .forEach(o -> out.add(o.objHash()));
        return out;
    }
}
