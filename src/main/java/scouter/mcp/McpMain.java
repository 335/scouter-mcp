package scouter.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import scouter.mcp.config.Config;
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
            new com.fasterxml.jackson.databind.ObjectMapper();

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
                .description("Scouter 수집 대상 오브젝트(에이전트) 목록을 조회한다. objType/nameLike로 필터링 가능")
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
                .description("과거 구간 카운터 시계열을 조회한다. objHashes 또는 objType 중 하나, counter/from/to 필수")
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
                                    "objHashes 또는 objType 중 하나는 필수입니다");
                        }
                        if (objHashes.isEmpty() && objType != null) {
                            objHashes = resolveObjHashesByType(client, objType);
                        }
                        // 팬아웃 캡: objType 이 수십 인스턴스로 퍼지면 응답이 곱으로 커진다. 상한으로 자른다.
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
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT, "from 은 to 보다 앞서야 합니다");
                        }
                        if (windowMs > Limits.COUNTER_ABS_MAX_WINDOW_MS) {
                            throw scouter.mcp.error.McpError.of(
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT, "카운터 조회 기간이 너무 깁니다(최대 24시간)");
                        }
                        String json = Tools.renderGetCounter(client, objHashes, counter, fromMillis, toMillis);
                        McpSchema.CallToolResult.Builder rb = McpSchema.CallToolResult.builder().addTextContent(json);
                        if (objTruncated) {
                            rb.addTextContent("{\"note\":\"objType 인스턴스 " + totalObj + "개 중 상위 "
                                    + Limits.COUNTER_MAX_OBJ + "개만 조회함. 특정 objHash 를 지정하면 정확히 조회됩니다\"}");
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
                .description("주어진 objType에서 사용 가능한 카운터 메타(name/displayName/unit)를 조회한다")
                .inputSchema(jsonMapper, Schemas.LIST_COUNTERS)
                .build();

        McpServerFeatures.SyncToolSpecification listCountersSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(listCounters)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        String objType = asString(arguments, "objType");
                        String json = Tools.renderListCounters(client, objType);
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
                .description("XLog(트랜잭션) 목록을 검색한다. from/to 필수. 운영은 트래픽이 많아 service 또는 objHash 필터 없이는 5분 이내만 허용되고, 스캔 상한 도달 시 일부만 반환된다(truncated/hint 확인). 가능하면 service(부분일치)나 objHash로 좁혀라")
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
                        String json = Tools.renderSearchXlog(client, params);
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
                .description("단일 XLog 상세(요약 + 프로파일 스텝/SQL/에러)를 조회한다. txid 필수, 바인드는 기본 마스킹")
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
                .description("동일 gxid(글로벌 트랜잭션)에 속한 XLog 목록을 조회한다. gxid 필수")
                .inputSchema(jsonMapper, Schemas.GET_XLOG_BY_GXID)
                .build();

        McpServerFeatures.SyncToolSpecification getXlogByGxidSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(getXlogByGxid)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        long gxid = requireLong(arguments, "gxid");
                        String yyyymmdd = resolveYyyymmdd(arguments, config);
                        String json = Tools.renderXlogByGxid(client, gxid, yyyymmdd);
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

    private static long requireLong(Map<String, Object> arguments, String key) {
        Long value = asLong(arguments, key);
        if (value == null) {
            throw scouter.mcp.error.McpError.of(
                    scouter.mcp.error.McpError.Code.INVALID_INPUT, key + " is required");
        }
        return value;
    }

    // date(yyyyMMdd) 우선, 없으면 at(상대/ISO)에서 산출, 둘 다 없으면 설정 zone 기준 오늘.
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
