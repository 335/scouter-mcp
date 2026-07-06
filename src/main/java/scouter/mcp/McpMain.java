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

    // Every tool here is read-only against the collector; advertising readOnlyHint lets clients (Claude
    // Code, etc.) skip or streamline the write-permission prompt for these calls.
    private static final McpSchema.ToolAnnotations READ_ONLY =
            McpSchema.ToolAnnotations.builder().readOnlyHint(true).build();

    // Static diagnosis playbook exposed as an MCP prompt, so a client that has never used these tools
    // still invokes them in the right order. Kept in English (stable contract), like the tool schemas.
    private static final String DIAGNOSE_PLAYBOOK = """
            Use these Scouter MCP tools in order to find the root cause of a latency or error incident.

            0. Targeting: users say app-name fragments ("shop-order-api"), never objHash — objHash embeds the
               k8s pod name and changes every deploy. Pass the fragment as objNameLike directly (search_xlog,
               get_service_summary, get_counter, get_active_services all accept it and fan out over every
               matching instance). Do NOT ask the user for an objHash, and do not pre-call list_objects just
               to resolve one. If objNameLike matches nothing, the error returns candidate objNames — pick
               the closest and retry. One app normally resolves to many pods (fine — they aggregate). BUT if
               you are unsure of the exact app, or the fragment could match several DIFFERENT apps (e.g.
               "grm-biz" -> grm-biz-member AND grm-biz-membership), confirm the intended app with the user
               (or list_objects) before scanning — every extra instance is another slow per-instance scan.
            1. list_objects — only for discovery ("what is running?"); nameLike is case-insensitive.
            2. get_summary — FIRST for ranking/frequency questions ("which SQL/service/error is worst today"):
               daily pre-aggregated on the collector, no XLog scanning, up to 31 days. category=error rows
               carry a sample txid for step 5.
            3. search_xlog — find specific transactions (rows carry txid/gxid/objName/endTimeIso/elapsed/
               sqlCount; filter by objNameLike + service/login/ip). Per-transaction fields like sqlCount are
               ONLY here, not in any summary — so a "transactions with sqlCount>N / elapsed>N" request does
               need search_xlog, filtered client-side on the returned rows. BUT if the user named the service
               loosely (a bare token, not an exact URL<METHOD>), resolve the EXACT name via get_summary
               category=service FIRST (step 2, no scan), then search with that exact name + a single objHash +
               a narrow window. A loose service filter triggers a slow raw scan per instance; on PAST dates it
               fans out over every rotated pod and often just times out — for past-date rare services, prefer
               the get_summary aggregate and only drill into raw rows on a recent/narrow window.
            4. get_service_summary — per-service aggregate (count/avg/max/p95/errorRate) within a day-scale
               window; finer time bounds than get_summary's daily grain.
            5. get_xlog_detail(txid, at=endTimeIso) — inspect SQL/bind params/profile steps/errors for one
               transaction. Pass the row's endTimeIso so the per-day partition date is correct.
            6. get_xlog_by_gxid(gxid) — expand a distributed transaction across services.
            7. get_counter / get_counter_stat / list_counters — blast radius with time series: get_counter for
               full resolution within 24h, get_counter_stat for 5-minute stats up to 31 days ("compare with
               last week").
            8. Live triage: get_active_services -> list_threads (state histogram + cpu-top rows) ->
               get_thread_detail(txid[, id]) for the stack trace and lock owner. Thread detail needs an
               ACTIVE txid — take it from the immediately preceding call and drill down right away.
            9. get_object_env — JVM flags/timezone/versions when configuration is the suspect.
            10. list_alerts — what fired; then cross-correlate objName + endTimeIso window + txid/gxid with
                OpenSearch/Datadog logs.

            Tips:
            - service matches request URLs, NOT app names. App name -> objNameLike; URL fragment -> service.
              Mixing them up returns 0 rows (the hint will flag it).
            - Sloppy service input is normalized ("GET orderDetail", "order-detail POST", pasted "name<POST>").
              Server matching is case-sensitive though — if 0 rows come back with a serviceCandidates list,
              retry with one of those exact names instead of guessing again.
            - Tracing one service by name (e.g. "checkOrdCnfCustEastnAddr")? A service filter scans raw XLogs
              per instance and is slow on production, especially over wide or past windows. Do this instead:
              (1) get_summary category=service to get the EXACT service name and per-instance counts with no
              scan; (2) then search_xlog with that exact name plus a single objHash and a narrow window. For
              failures, get_summary category=error already carries a sample txid for get_xlog_detail.
            - 0 rows? Widen the window step by step (now-1h -> now-6h -> now-24h) keeping a filter
              (objNameLike/service/login/ip) — windows over 5 minutes require one.
            - Prefer narrow windows and server-side filters to avoid the scan cap. Watch
              truncated/scanCapReached/hint in results and narrow instead of refetching.
            - Whole-app aggregates over a wide or PAST-date window: use get_summary with objType (one
              server-side aggregation across every instance — fast and complete). objNameLike fans out per
              pod and, for past dates, over every rotated pod; it can time out. get_summary auto-uses the
              objType pass when the app owns its type, but passing objType directly is the surest fast path.
            - Repeated timeouts: do NOT keep retrying the same shape, and do NOT silently fall back to
              another environment. Change the approach - switch to get_summary+objType for an aggregate, or a
              single objHash + a short window for raw rows - and if it still times out, tell the user it is
              timing out and ask them to confirm a narrower window or a specific instance rather than guessing.
            - Past-date raw XLog retrieval of a rare service (individual rows / sqlCount / elapsed of specific
              transactions) is often infeasible here: the per-instance scan is slow and objNameLike fans out
              over every rotated pod. Get the aggregate from get_summary+objType; only drill into raw rows on
              a recent or tightly-bounded window (ideally a single objHash).
            - minElapsedMs/onlyError are client-side: the collector streams rows before they drop. For
              "which is slow/erroring" questions prefer get_summary (sql/error) or get_service_summary.
            - Thread drill-down is a live snapshot: list_threads/get_active_services -> get_thread_detail
              must be back-to-back; a finished txid returns state="end".
            - This MCP only reads monitoring data from the Scouter collector. The monitored applications
              (their source code) live in their own repositories, not here.
            - Presentation: when relaying results to the user, group them by the dimension that matches
              the question — objType or app for object lists, service for transactions, level for alerts —
              with per-group counts, instead of one long flat list. Pick the grouping from context; a flat
              dump of 50 rows is never the answer.
            - Labeling: results carry pre-computed 'app' and 'instance' fields — use those as row labels.
              NEVER shorten an objName yourself by cutting from the front: the trailing hash
              ("deployment-6576f86784") is meaningless and changes every deploy, while the leading words
              are the identity. "app (instance)" is the right short form.
            """;

    private static McpSchema.Tool readOnlyTool(McpJsonMapper jm, String name, String description, String schema) {
        return McpSchema.Tool.builder(name)
                .description(description)
                .inputSchema(jm, schema)
                .annotations(READ_ONLY)
                .build();
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

        McpSchema.Tool listObjects = readOnlyTool(jsonMapper, "list_objects",
                "List Scouter monitored objects (agents), grouped by objType with total/alive counts per group. Filter by objType/nameLike. When relaying to the user, keep the grouped shape (summarize per group) instead of flattening into one long list.",
                Schemas.LIST_OBJECTS);

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

        McpSchema.Tool getCounter = readOnlyTool(jsonMapper, "get_counter",
                "Query counter time series over a past range. One of objHashes/objType, plus counter/from/to, is required.",
                Schemas.GET_COUNTER);

        McpServerFeatures.SyncToolSpecification getCounterSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(getCounter)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        List<Integer> objHashes = asIntList(arguments, "objHashes");
                        String objType = asString(arguments, "objType");
                        String objNameLike = asString(arguments, "objNameLike");
                        if (objHashes.isEmpty() && objType == null && objNameLike == null) {
                            throw scouter.mcp.error.McpError.of(
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT,
                                    Messages.get(config.locale(), "error.counter_obj_required"));
                        }
                        if (objHashes.isEmpty() && objNameLike != null) {
                            objHashes = resolveObjHashesByNameLike(client, objNameLike, config);
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

        McpSchema.Tool listCounters = readOnlyTool(jsonMapper, "list_counters",
                "List available counter metadata (name/displayName/unit). Pass objType if known, or objNameLike (fuzzy app-name fragment) to derive it.",
                Schemas.LIST_COUNTERS);

        McpServerFeatures.SyncToolSpecification listCountersSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(listCounters)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        String objType = asString(arguments, "objType");
                        String objNameLike = asString(arguments, "objNameLike");
                        if (objType == null && objNameLike == null) {
                            throw scouter.mcp.error.McpError.of(
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT,
                                    "one of objType/objNameLike is required");
                        }
                        if (objType == null) {
                            // Derive the objType from the first fuzzy-matched object (users rarely know objType).
                            var all = client.listObjects();
                            var matched = scouter.mcp.scouter.TargetResolver.match(all, objNameLike);
                            if (matched.isEmpty()) {
                                throw scouter.mcp.error.McpError.of(
                                                scouter.mcp.error.McpError.Code.NOT_FOUND,
                                                Messages.get(config.locale(), "error.target_not_found", objNameLike.trim()))
                                        .withHint("candidates",
                                                String.join(", ", scouter.mcp.scouter.TargetResolver.suggest(all, 10)));
                            }
                            objType = matched.get(0).objType();
                        }
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

        McpSchema.Tool searchXlog = readOnlyTool(jsonMapper, "search_xlog",
                "Search XLogs (transactions). from/to required. Target an app with objNameLike (fuzzy app-name fragment, e.g. 'shop-order-api' — resolved to all instances; no need to know objHash). At production traffic, without a filter (objNameLike/service/login/ip) only windows up to 5 minutes are allowed, and results are partial once the scan cap is hit (check truncated/hint).",
                Schemas.SEARCH_XLOG);

        McpServerFeatures.SyncToolSpecification searchXlogSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(searchXlog)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        long now = System.currentTimeMillis();
                        long fromMillis = TimeRange.parseInstant(asString(arguments, "from"), config.zone(), now);
                        long toMillis = TimeRange.parseInstant(asString(arguments, "to"), config.zone(), now);
                        Long objHash = asLong(arguments, "objHash");
                        String objNameLike = asString(arguments, "objNameLike");
                        String service = asString(arguments, "service");
                        String login = asString(arguments, "login");
                        String ip = asString(arguments, "ip");
                        String desc = asString(arguments, "desc");
                        Integer minElapsedMs = asInteger(arguments, "minElapsedMs");
                        boolean onlyError = Boolean.TRUE.equals(asBoolean(arguments, "onlyError"));
                        int limit = clampLimit(asInteger(arguments, "limit"));
                        SearchXlogParams params = new SearchXlogParams(
                                fromMillis, toMillis, objHash, objNameLike, service, login, ip, desc,
                                minElapsedMs, onlyError, limit);
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

        McpSchema.Tool getServiceSummary = readOnlyTool(jsonMapper, "get_service_summary",
                "Aggregate XLogs per service over a window: count/errorCount/errorRate/avgMs/maxMs/p95Ms, top 50 by count. Retains no raw rows and uses a higher scan cap, so it covers wider windows cheaply. Target an app with objNameLike (fuzzy fragment; all instances). Same filters as search_xlog (service/login/ip/desc/minElapsedMs/onlyError). Best first step for 'which API got slow or errored'.",
                Schemas.SERVICE_SUMMARY);

        McpServerFeatures.SyncToolSpecification getServiceSummarySpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(getServiceSummary)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        long now = System.currentTimeMillis();
                        long fromMillis = TimeRange.parseInstant(asString(arguments, "from"), config.zone(), now);
                        long toMillis = TimeRange.parseInstant(asString(arguments, "to"), config.zone(), now);
                        Long objHash = asLong(arguments, "objHash");
                        String objNameLike = asString(arguments, "objNameLike");
                        String service = asString(arguments, "service");
                        String login = asString(arguments, "login");
                        String ip = asString(arguments, "ip");
                        String desc = asString(arguments, "desc");
                        Integer minElapsedMs = asInteger(arguments, "minElapsedMs");
                        boolean onlyError = Boolean.TRUE.equals(asBoolean(arguments, "onlyError"));
                        SearchXlogParams params = new SearchXlogParams(
                                fromMillis, toMillis, objHash, objNameLike, service, login, ip, desc,
                                minElapsedMs, onlyError, 0);
                        String json = Tools.renderServiceSummary(config.locale(), client, params);
                        return McpSchema.CallToolResult.builder().addTextContent(json).build();
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

        McpSchema.Tool getXlogDetail = readOnlyTool(jsonMapper, "get_xlog_detail",
                "Get a single XLog detail (summary + profile steps/SQL/errors). txid is a globally unique ID but the collector indexes XLogs by day, so the correct date is also required. Use the endTimeIso from a search_xlog result row as the 'at' parameter to get the date automatically. Accepts Hexa32 txid strings (e.g. 'z3st744n3d2p6q') as well as raw longs.",
                Schemas.GET_XLOG_DETAIL);

        McpServerFeatures.SyncToolSpecification getXlogDetailSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(getXlogDetail)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        long txid = requireLong(arguments, "txid");
                        String yyyymmdd = resolveYyyymmdd(arguments, config);
                        // Operator kill-switch wins: if disabled server-side, bind params are never included,
                        // regardless of the per-call argument (an LLM cannot opt back in).
                        boolean includeBindParams = config.bindParamsEnabled()
                                && !Boolean.FALSE.equals(asBoolean(arguments, "includeBindParams"));
                        String json = Tools.renderXlogDetail(client, txid, yyyymmdd, includeBindParams);
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

        McpSchema.Tool getXlogByGxid = readOnlyTool(jsonMapper, "get_xlog_by_gxid",
                "List XLogs belonging to the same gxid (global transaction). gxid required.",
                Schemas.GET_XLOG_BY_GXID);

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

        McpServerFeatures.SyncPromptSpecification diagnosePlaybook = new McpServerFeatures.SyncPromptSpecification(
                new McpSchema.Prompt("diagnose_root_cause",
                        "Step-by-step playbook for diagnosing latency/errors with the Scouter MCP tools.",
                        List.of()),
                (exchange, request) -> new McpSchema.GetPromptResult(
                        "Scouter APM root-cause diagnosis playbook",
                        List.of(new McpSchema.PromptMessage(McpSchema.Role.USER,
                                new McpSchema.TextContent(DIAGNOSE_PLAYBOOK)))));

        McpSchema.Tool listAlerts = readOnlyTool(jsonMapper, "list_alerts",
                "List past collector alerts over a window (ALERT_LOAD_TIME). Optional filters: level (INFO/WARN/ERROR/FATAL), object (name), key (keyword in title/message). Good entry point for 'what happened' before drilling into XLogs.",
                Schemas.LIST_ALERTS);

        McpServerFeatures.SyncToolSpecification listAlertsSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(listAlerts)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        long now = System.currentTimeMillis();
                        long fromMillis = TimeRange.parseInstant(asString(arguments, "from"), config.zone(), now);
                        long toMillis = TimeRange.parseInstant(asString(arguments, "to"), config.zone(), now);
                        String level = asString(arguments, "level");
                        String object = asString(arguments, "object");
                        String key = asString(arguments, "key");
                        Integer limit = asInteger(arguments, "limit");
                        String json = Tools.renderListAlerts(config.locale(), client, fromMillis, toMillis,
                                level, object, key, limit == null ? 0 : limit);
                        return McpSchema.CallToolResult.builder().addTextContent(json).build();
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

        McpSchema.Tool getActiveServices = readOnlyTool(jsonMapper, "get_active_services",
                "List services currently running on an agent right now (OBJECT_ACTIVE_SERVICE_LIST). One of objNameLike/objType/objHash is required — prefer objNameLike (fuzzy app-name fragment; queries all alive instances). Use to diagnose live hangs/backlog (long-running threads, stuck SQL).",
                Schemas.ACTIVE_SERVICES);

        McpServerFeatures.SyncToolSpecification getActiveServicesSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(getActiveServices)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        String objType = asString(arguments, "objType");
                        Long objHash = asLong(arguments, "objHash");
                        String objNameLike = asString(arguments, "objNameLike");
                        if (objType == null && objHash == null && objNameLike == null) {
                            throw scouter.mcp.error.McpError.of(
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT,
                                    "one of objType/objHash/objNameLike is required");
                        }
                        String json = Tools.renderActiveServices(config.locale(), client, objType, objHash, objNameLike);
                        return McpSchema.CallToolResult.builder().addTextContent(json).build();
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

        McpSchema.Tool listThreads = readOnlyTool(jsonMapper, "list_threads",
                "List JVM threads on an agent right now (live snapshot): per-instance state histogram (RUNNABLE/BLOCKED/WAITING...) plus the top 50 threads by cpu, each with id/name/state/cpu and txid/service when a service is running on it. Use for hang, thread-pool exhaustion, or lock-contention triage; drill into one thread with get_thread_detail(txid). One of objNameLike/objHash required.",
                Schemas.LIST_THREADS);

        McpServerFeatures.SyncToolSpecification listThreadsSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(listThreads)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        String objNameLike = asString(arguments, "objNameLike");
                        Long objHash = asLong(arguments, "objHash");
                        if (objNameLike == null && objHash == null) {
                            throw scouter.mcp.error.McpError.of(
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT,
                                    "one of objNameLike/objHash is required");
                        }
                        String json = Tools.renderListThreads(config.locale(), client, objNameLike, objHash);
                        return McpSchema.CallToolResult.builder().addTextContent(json).build();
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

        McpSchema.Tool getThreadDetail = readOnlyTool(jsonMapper, "get_thread_detail",
                "Inspect one ACTIVE transaction's thread right now: stack trace, thread state, lock owner (who blocks it), cpu/blocked/waited counters, current SQL and bind values, and the service it runs. Live snapshot - take txid (and id) from get_active_services or list_threads and call this immediately; a finished txid returns state='end'. The standard drill-down for hangs and lock contention.",
                Schemas.GET_THREAD_DETAIL);

        McpServerFeatures.SyncToolSpecification getThreadDetailSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(getThreadDetail)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        String objNameLike = asString(arguments, "objNameLike");
                        Long objHash = asLong(arguments, "objHash");
                        if (objNameLike == null && objHash == null) {
                            throw scouter.mcp.error.McpError.of(
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT,
                                    "one of objNameLike/objHash is required");
                        }
                        long txid = requireLong(arguments, "txid");
                        Long threadId = asLong(arguments, "id");
                        // Operator bind kill-switch also governs the live SQLActiveBindVar field.
                        String json = Tools.renderThreadDetail(config.locale(), client, objNameLike, objHash,
                                threadId, txid, config.bindParamsEnabled());
                        return McpSchema.CallToolResult.builder().addTextContent(json).build();
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

        McpSchema.Tool getObjectEnv = readOnlyTool(jsonMapper, "get_object_env",
                "Read an agent JVM's system properties (java version, -D flags, timezone, paths) for configuration diagnosis. Values of secret-looking keys (password/token/...) are masked server-side. Use keyLike to filter (the full set is large). One of objNameLike/objHash required.",
                Schemas.GET_OBJECT_ENV);

        McpServerFeatures.SyncToolSpecification getObjectEnvSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(getObjectEnv)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        String objNameLike = asString(arguments, "objNameLike");
                        Long objHash = asLong(arguments, "objHash");
                        if (objNameLike == null && objHash == null) {
                            throw scouter.mcp.error.McpError.of(
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT,
                                    "one of objNameLike/objHash is required");
                        }
                        String keyLike = asString(arguments, "keyLike");
                        String json = Tools.renderObjectEnv(config.locale(), client, objNameLike, objHash, keyLike);
                        return McpSchema.CallToolResult.builder().addTextContent(json).build();
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

        McpSchema.Tool getSummary = readOnlyTool(jsonMapper, "get_summary",
                "Daily PRE-AGGREGATED stats from the collector (no XLog scanning, no scan cap - the cheapest wide-window tool). category=sql answers 'which SQL is slowest/hottest today'; error gives error-type x service counts with a sample txid for get_xlog_detail; service/apiCall/ip/userAgent/alert likewise. Top 50 by count. Prefer this over search_xlog for ranking/frequency questions; it is daily-partitioned data, so use day-scale from/to (up to 31 days). TARGETING: for a whole-app total prefer objType (the collector aggregates every instance in ONE server-side pass — fast and complete even for past dates); objNameLike also works and auto-uses the objType pass when the app owns its type, but on a shared type it fans out per pod and can be slow/partial. objHash targets one instance.",
                Schemas.GET_SUMMARY);

        McpServerFeatures.SyncToolSpecification getSummarySpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(getSummary)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        String category = asString(arguments, "category");
                        if (category == null) {
                            throw scouter.mcp.error.McpError.of(
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT, "category is required");
                        }
                        long now = System.currentTimeMillis();
                        long fromMillis = TimeRange.parseInstant(asString(arguments, "from"), config.zone(), now);
                        long toMillis = TimeRange.parseInstant(asString(arguments, "to"), config.zone(), now);
                        String objType = asString(arguments, "objType");
                        String objNameLike = asString(arguments, "objNameLike");
                        Long objHash = asLong(arguments, "objHash");
                        String json = Tools.renderSummary(config.locale(), client, category,
                                fromMillis, toMillis, objType, objHash, objNameLike);
                        return McpSchema.CallToolResult.builder().addTextContent(json).build();
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

        McpSchema.Tool getCounterStat = readOnlyTool(jsonMapper, "get_counter_stat",
                "Counter time series over a LONG range (up to 31 days) at fixed 5-minute resolution, from the collector's daily-stat DB - one cheap round-trip. Use for week-over-week trends and 'compare today vs last week'; use get_counter for full-resolution views within 24h.",
                Schemas.GET_COUNTER_STAT);

        McpServerFeatures.SyncToolSpecification getCounterStatSpec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(getCounterStat)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        List<Integer> objHashes = asIntList(arguments, "objHashes");
                        String objType = asString(arguments, "objType");
                        String objNameLike = asString(arguments, "objNameLike");
                        if (objHashes.isEmpty() && objType == null && objNameLike == null) {
                            throw scouter.mcp.error.McpError.of(
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT,
                                    Messages.get(config.locale(), "error.counter_obj_required"));
                        }
                        if (objHashes.isEmpty() && objNameLike != null) {
                            objHashes = resolveObjHashesByNameLike(client, objNameLike, config);
                        }
                        if (objHashes.isEmpty() && objType != null) {
                            objHashes = resolveObjHashesByType(client, objType);
                        }
                        int totalObj = objHashes.size();
                        boolean objTruncated = totalObj > Limits.COUNTER_MAX_OBJ;
                        if (objTruncated) {
                            objHashes = objHashes.subList(0, Limits.COUNTER_MAX_OBJ);
                        }
                        String counter = asString(arguments, "counter");
                        long now = System.currentTimeMillis();
                        long fromMillis = TimeRange.parseInstant(asString(arguments, "from"), config.zone(), now);
                        long toMillis = TimeRange.parseInstant(asString(arguments, "to"), config.zone(), now);
                        if (toMillis - fromMillis <= 0) {
                            throw scouter.mcp.error.McpError.of(
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT,
                                    Messages.get(config.locale(), "error.from_after_to"));
                        }
                        String sDate = TimeRange.yyyymmdd(fromMillis, config.zone());
                        String eDate = TimeRange.yyyymmdd(toMillis, config.zone());
                        long days = java.time.temporal.ChronoUnit.DAYS.between(
                                java.time.LocalDate.parse(sDate, java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                                java.time.LocalDate.parse(eDate, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)) + 1;
                        if (days > Limits.DAILY_STAT_MAX_DAYS) {
                            throw scouter.mcp.error.McpError.of(
                                    scouter.mcp.error.McpError.Code.INVALID_INPUT,
                                    Messages.get(config.locale(), "error.stat_window_too_long",
                                            Limits.DAILY_STAT_MAX_DAYS));
                        }
                        String json = Tools.renderCounterStat(config.locale(), client, objHashes, counter,
                                sDate, eDate);
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

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("scouter-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).prompts(true).build())
                .tools(listObjectsSpec, getCounterSpec, listCountersSpec, searchXlogSpec,
                        getServiceSummarySpec, getXlogDetailSpec, getXlogByGxidSpec,
                        listAlertsSpec, getActiveServicesSpec, listThreadsSpec, getThreadDetailSpec,
                        getObjectEnvSpec, getSummarySpec, getCounterStatSpec)
                .prompts(Map.of("diagnose_root_cause", diagnosePlaybook))
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

    // Fuzzy app-name resolution for get_counter (users say "shop-order-api", never an objHash —
    // it embeds the pod name and changes every deploy). Fails as NOT_FOUND with candidates.
    private static List<Integer> resolveObjHashesByNameLike(ScouterClient client, String objNameLike, Config config) {
        var all = client.listObjects();
        var matched = scouter.mcp.scouter.TargetResolver.match(all, objNameLike);
        if (matched.isEmpty()) {
            throw scouter.mcp.error.McpError.of(
                            scouter.mcp.error.McpError.Code.NOT_FOUND,
                            Messages.get(config.locale(), "error.target_not_found", objNameLike.trim()))
                    .withHint("candidates",
                            String.join(", ", scouter.mcp.scouter.TargetResolver.suggest(all, 10)));
        }
        List<Integer> out = new ArrayList<>();
        matched.forEach(o -> out.add(o.objHash()));
        return out;
    }
}
