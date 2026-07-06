package scouter.mcp.scouter;

import lombok.extern.slf4j.Slf4j;
import scouter.lang.AlertLevel;
import scouter.lang.Counter;
import scouter.lang.ObjectType;
import scouter.lang.TextTypes;
import scouter.lang.pack.AlertPack;
import scouter.lang.constants.ParamConstant;
import scouter.lang.counters.CounterEngine;
import scouter.lang.pack.MapPack;
import scouter.lang.pack.ObjectPack;
import scouter.lang.pack.Pack;
import scouter.lang.pack.XLogPack;
import scouter.lang.pack.XLogProfilePack;
import scouter.lang.step.ApiCallStep;
import scouter.lang.step.MethodStep;
import scouter.lang.step.SqlStep;
import scouter.lang.step.Step;
import scouter.lang.value.BlobValue;
import scouter.lang.value.ListValue;
import scouter.lang.value.Value;
import scouter.net.RequestCmd;
import scouter.mcp.client.LoginMgr;
import scouter.mcp.client.LoginRequest;
import scouter.mcp.client.Server;
import scouter.mcp.client.ServerRegistry;
import scouter.mcp.client.SessionRetry;
import scouter.mcp.client.TcpProxy;
import scouter.mcp.config.Config;
import scouter.mcp.error.McpError;
import scouter.mcp.i18n.Messages;
import scouter.mcp.scouter.dto.ActiveServiceDto;
import scouter.mcp.scouter.dto.AlertDto;
import scouter.mcp.scouter.dto.CounterMetaDto;
import scouter.mcp.scouter.dto.CounterSeriesDto;
import scouter.mcp.scouter.dto.SObjectDto;
import scouter.mcp.scouter.dto.SearchXlogParams;
import scouter.mcp.scouter.dto.ServiceSummaryDto;
import scouter.mcp.policy.Truncate;
import scouter.mcp.scouter.dto.AlertSummaryRowDto;
import scouter.mcp.scouter.dto.EnvDto;
import scouter.mcp.scouter.dto.ErrorSummaryRowDto;
import scouter.mcp.scouter.dto.SummaryResult;
import scouter.mcp.scouter.dto.SummaryRowDto;
import scouter.mcp.scouter.dto.ThreadDetailDto;
import scouter.mcp.scouter.dto.ThreadListDto;
import scouter.mcp.scouter.dto.ThreadRowDto;
import scouter.mcp.scouter.dto.XlogSummaryResult;
import scouter.mcp.scouter.dto.XLogDetailDto;
import scouter.mcp.scouter.dto.XLogRowDto;
import scouter.mcp.scouter.dto.XlogSearchResult;
import scouter.mcp.policy.Limits;
import scouter.mcp.time.DaySplitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

@Slf4j
public final class TcpScouterClient implements ScouterClient {

    private static final int MAX_PROFILE_BLOCK = 10;

    private final Config config;
    private Server server;

    public TcpScouterClient(Config config) {
        this.config = config;
    }

    @Override
    public void connect() {
        Server s = new Server(config.host(), String.valueOf(config.port()));
        s.setUserId(config.user());
        s.setPassword(config.password());
        ServerRegistry.add(s);
        try {
            LoginRequest result = LoginMgr.login(s);
            if (!result.success || !s.isOpen()) {
                throw McpError.of(McpError.Code.SCOUTER_AUTH_FAILED, result.getErrorMessage())
                        .withHint("host", config.host());
            }
            this.server = s;
        } catch (McpError e) {
            throw e;
        } catch (Exception e) {
            throw McpError.of(McpError.Code.SCOUTER_CONNECT_FAILED, String.valueOf(e.getMessage()))
                    .withHint("host", config.host())
                    .withHint("port", String.valueOf(config.port()));
        }
    }

    // Re-login recovery for session expiry. LoginMgr.login refreshes the session on the same Server
    // instance (and re-inits its connection pool); a failed relogin surfaces as SCOUTER_AUTH_FAILED.
    private void relogin() {
        log.info("session expired, attempting relogin: host={}", config.host());
        LoginRequest result = LoginMgr.login(server);
        if (!result.success || !server.isOpen()) {
            throw McpError.of(McpError.Code.SCOUTER_AUTH_FAILED, result.getErrorMessage())
                    .withHint("host", config.host());
        }
    }

    @Override
    public List<SObjectDto> listObjects() {
        return SessionRetry.execute(this::listObjectsImpl, this::relogin);
    }

    private List<SObjectDto> listObjectsImpl() {
        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            List<SObjectDto> out = new ArrayList<>();
            List<Pack> packs = tcp.process(RequestCmd.OBJECT_LIST_REAL_TIME, null);
            if (packs == null) {
                return out;
            }
            for (Pack p : packs) {
                ObjectPack op = (ObjectPack) p;
                out.add(new SObjectDto(op.objHash, op.objName, op.objType, op.address, op.alive));
            }
            return out;
        } finally {
            TcpProxy.close(tcp);
        }
    }

    /**
     * Object list persisted in the collector's daily DB (OBJECT_LIST_LOAD_DATE): includes instances
     * that stopped reporting since — deploy-replaced pods above all. The daily DB stores identity
     * only, so alive is false and address empty. Failures degrade to an empty list: this is a
     * best-effort widening of the real-time list, never a reason to fail the query itself.
     */
    private List<SObjectDto> listObjectsByDateImpl(String yyyymmdd) {
        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            MapPack param = new MapPack();
            param.put(ParamConstant.DATE, yyyymmdd);
            Pack p = tcp.getSingle(RequestCmd.OBJECT_LIST_LOAD_DATE, param);
            List<SObjectDto> out = new ArrayList<>();
            if (!(p instanceof MapPack)) {
                return out;
            }
            MapPack mp = (MapPack) p;
            ListValue hashLv = mp.getList(ParamConstant.OBJ_HASH);
            ListValue nameLv = mp.getList("objName"); // ObjectRD.getDailyAgent key; no ParamConstant exists
            ListValue typeLv = mp.getList(ParamConstant.OBJ_TYPE);
            if (hashLv == null) {
                return out;
            }
            for (int i = 0; i < hashLv.size(); i++) {
                String name = nameLv == null ? null : nameLv.getString(i);
                String type = typeLv == null ? null : typeLv.getString(i);
                out.add(new SObjectDto((int) hashLv.getLong(i), name, type, "", false));
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("daily object list unavailable, realtime-only resolution: date={}, cause={}",
                    yyyymmdd, String.valueOf(e.getMessage()));
            return new ArrayList<>();
        } finally {
            TcpProxy.close(tcp);
        }
    }

    @Override
    public List<CounterSeriesDto> getCounter(List<Integer> objHashes, String counter, long fromMillis, long toMillis) {
        return SessionRetry.execute(() -> getCounterImpl(objHashes, counter, fromMillis, toMillis), this::relogin);
    }

    private List<CounterSeriesDto> getCounterImpl(List<Integer> objHashes, String counter, long fromMillis, long toMillis) {
        // Counter data is stored in per-day files, and COUNTER_PAST_TIME_ALL is single-day. Like the upstream
        // CounterConsumer, split [from,to] into per-calendar-day segments (in the configured zone), query each
        // day, and merge points by objHash. Otherwise a window straddling midnight loses data on one side.
        Map<Integer, List<PackMapper.Point>> mergedByObj = new LinkedHashMap<>();
        for (DaySplitter.Segment seg : DaySplitter.splitByCalendarDay(fromMillis, toMillis, config.zone())) {
            for (CounterSeriesDto day : queryCounterDay(objHashes, counter, seg.fromMillis(), seg.toMillis())) {
                mergedByObj.computeIfAbsent(day.objHash(), k -> new ArrayList<>()).addAll(day.points());
            }
        }
        List<CounterSeriesDto> out = new ArrayList<>(mergedByObj.size());
        for (Map.Entry<Integer, List<PackMapper.Point>> e : mergedByObj.entrySet()) {
            out.add(new CounterSeriesDto(e.getKey(), counter, e.getValue()));
        }
        return out;
    }

    // (self-note) getCounter/searchXlog day-splitting is centralized in DaySplitter to keep both consistent.

    /** Single-day counter query (COUNTER_PAST_TIME_ALL), ported from webapp CounterConsumer.retrieveCounterInDay. */
    private List<CounterSeriesDto> queryCounterDay(List<Integer> objHashes, String counter, long stime, long etime) {
        MapPack param = new MapPack();
        ListValue objHashLv = param.newList(ParamConstant.OBJ_HASH);
        if (objHashes != null) {
            for (Integer objHash : objHashes) {
                objHashLv.add((long) objHash);
            }
        }
        param.put(ParamConstant.COUNTER, counter);
        param.put(ParamConstant.STIME, stime);
        param.put(ParamConstant.ETIME, etime);

        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            List<CounterSeriesDto> out = new ArrayList<>();
            tcp.process(RequestCmd.COUNTER_PAST_TIME_ALL, param, in -> {
                Pack p = in.readPack();
                if (!(p instanceof MapPack)) {
                    return;
                }
                MapPack mp = (MapPack) p;
                int objHash = mp.getInt(ParamConstant.OBJ_HASH);
                ListValue time = mp.getList(ParamConstant.TIME);
                ListValue value = mp.getList(ParamConstant.VALUE);
                out.add(new CounterSeriesDto(objHash, counter, PackMapper.toPoints(time, value)));
            });
            return out;
        } finally {
            TcpProxy.close(tcp);
        }
    }

    @Override
    public List<CounterSeriesDto> getCounterStat(List<Integer> objHashes, String counter,
                                                 String sDateYmd, String eDateYmd) {
        return SessionRetry.execute(() -> getCounterStatImpl(objHashes, counter, sDateYmd, eDateYmd), this::relogin);
    }

    private List<CounterSeriesDto> getCounterStatImpl(List<Integer> objHashes, String counter,
                                                      String sDateYmd, String eDateYmd) {
        // COUNTER_PAST_LONGDATE_ALL: {counter, sDate, eDate, objHash:ListValue} -> per (day x objHash)
        // MapPack{objHash,time,value} stream at fixed 5-min resolution (daily-stat DB). One round-trip
        // covers the whole range (the server iterates days), so no DaySplitter here. Param keys are
        // camelCase: sDate/eDate.
        long startedAt = System.currentTimeMillis();
        MapPack param = new MapPack();
        param.put(ParamConstant.COUNTER, counter);
        param.put(ParamConstant.SDATE, sDateYmd);
        param.put(ParamConstant.EDATE, eDateYmd);
        ListValue lv = param.newList(ParamConstant.OBJ_HASH);
        if (objHashes != null) {
            for (Integer h : objHashes) {
                lv.add((long) h);
            }
        }
        Map<Integer, List<PackMapper.Point>> merged = new LinkedHashMap<>();
        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            tcp.process(RequestCmd.COUNTER_PAST_LONGDATE_ALL, param, in -> {
                Pack p = in.readPack();
                if (p instanceof MapPack mp) {
                    int objHash = mp.getInt(ParamConstant.OBJ_HASH);
                    merged.computeIfAbsent(objHash, k -> new ArrayList<>())
                            .addAll(PackMapper.toPoints(mp.getList(ParamConstant.TIME),
                                    mp.getList(ParamConstant.VALUE)));
                }
            });
        } catch (Exception e) {
            if (!isEof(e)) {
                throw McpError.of(McpError.Code.INTERNAL, String.valueOf(e.getMessage()));
            }
            // EOF: no stat data in the range - routine.
        } finally {
            TcpProxy.close(tcp);
        }
        List<CounterSeriesDto> out = new ArrayList<>(merged.size());
        for (Map.Entry<Integer, List<PackMapper.Point>> e : merged.entrySet()) {
            e.getValue().sort(java.util.Comparator.comparingLong(PackMapper.Point::timeMillis));
            out.add(new CounterSeriesDto(e.getKey(), counter, e.getValue()));
        }
        log.info("get_counter_stat done: counter={}, objs={}, series={}, tookMs={}",
                counter, objHashes == null ? 0 : objHashes.size(), out.size(),
                System.currentTimeMillis() - startedAt);
        return out;
    }

    @Override
    public List<CounterMetaDto> listCounters(String objType) {
        return SessionRetry.execute(() -> listCountersImpl(objType), this::relogin);
    }

    private List<CounterMetaDto> listCountersImpl(String objType) {
        // Fetch the counter-definition XML (default + custom) via GET_XML_COUNTER and load it into CounterEngine.
        // Response: MapPack { "default": BlobValue, ("custom": BlobValue)? }  (ConfigureService.getCounterXml)
        CounterEngine engine = loadCounterEngine();
        List<CounterMetaDto> out = new ArrayList<>();
        if (engine == null) {
            return out;
        }
        ObjectType ot = engine.getObjectType(objType);
        if (ot == null) {
            return out;
        }
        Counter[] counters = ot.listCounters();
        if (counters == null) {
            return out;
        }
        for (Counter c : counters) {
            out.add(new CounterMetaDto(c.getName(), c.getDisplayName(), c.getUnit()));
        }
        return out;
    }

    /** Fan-out guard: (instances x day segments) round-trips per request must stay within the pass budget. */
    static void ensurePassBudget(int passes, java.util.Locale locale) {
        if (passes > Limits.MAX_QUERY_PASSES) {
            throw McpError.of(McpError.Code.INVALID_INPUT,
                            Messages.get(locale, "error.too_many_passes", passes, Limits.MAX_QUERY_PASSES))
                    .withHint("passes", String.valueOf(passes))
                    .withHint("maxPasses", String.valueOf(Limits.MAX_QUERY_PASSES));
        }
    }

    /** Sentinel for early streaming termination (normal flow control, not an error). */
    private static final class StopStreaming extends RuntimeException {
        StopStreaming() {
            super(null, null, false, false);
        }
    }

    /**
     * True when a RuntimeException from tcp.process() represents a normal, non-error stream end:
     * either our own StopStreaming sentinel (limit/scan-cap reached) or an EOFException (the
     * collector had no — or no more — data for this request). Both must be swallowed, not surfaced
     * as INTERNAL errors. Centralized because scanning fans out per objHash/day segment when a
     * fuzzy objNameLike target resolves to several instances, and any per-pass EOF is routine.
     */
    private static boolean isNormalStreamStop(RuntimeException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause instanceof StopStreaming || e instanceof StopStreaming || isEof(e);
    }

    @Override
    public XlogSearchResult searchXlog(SearchXlogParams params) {
        return SessionRetry.execute(() -> searchXlogImpl(params), this::relogin);
    }

    // Shared window/filter guard for XLog streaming tools (search + summary). Rejects inverted windows,
    // windows over the absolute cap, and unfiltered windows over the short cap (firehose defense).
    private void validateSearchWindow(SearchXlogParams params) {
        long windowMs = params.toMillis() - params.fromMillis();
        if (windowMs <= 0) {
            throw McpError.of(McpError.Code.INVALID_INPUT, Messages.get(config.locale(), "error.from_after_to"));
        }
        if (windowMs > Limits.ABS_MAX_WINDOW_MS) {
            throw McpError.of(McpError.Code.INVALID_INPUT,
                            Messages.get(config.locale(), "error.window_too_long", Limits.ABS_MAX_WINDOW_MS / 3600000))
                    .withHint("windowSec", String.valueOf(windowMs / 1000))
                    .withHint("maxSec", String.valueOf(Limits.ABS_MAX_WINDOW_MS / 1000));
        }
        boolean serverFilter = (params.objHash() != null && params.objHash() != 0L)
                || (params.objNameLike() != null && !params.objNameLike().isBlank())
                || (params.service() != null && !params.service().isBlank())
                || (params.login() != null && !params.login().isBlank())
                || (params.ip() != null && !params.ip().isBlank())
                || (params.desc() != null && !params.desc().isBlank());
        if (!serverFilter && windowMs > Limits.UNFILTERED_MAX_WINDOW_MS) {
            throw McpError.of(McpError.Code.INVALID_INPUT,
                            Messages.get(config.locale(), "error.unfiltered_window",
                                    Limits.UNFILTERED_MAX_WINDOW_MS / 60000))
                    .withHint("windowSec", String.valueOf(windowMs / 1000))
                    .withHint("maxUnfilteredSec", String.valueOf(Limits.UNFILTERED_MAX_WINDOW_MS / 1000));
        }
    }

    // Normalize sloppy service input (method words, whitespace, pasted "<POST>" names) into a
    // StrMatch-safe server pattern. See ServiceQueryNormalizer.
    private static String buildServicePattern(String service) {
        if (service == null || service.isBlank()) {
            return null;
        }
        return ServiceQueryNormalizer.normalize(service).serverPattern();
    }

    /**
     * Turn the caller's object constraint into the concrete objHash list to query.
     * Explicit objHash wins; otherwise objNameLike is fuzzy-resolved to every matching instance
     * (alive first, capped at SEARCH_MAX_OBJ); no constraint yields [null] (single unfiltered pass).
     * Resolution matches against the union of the real-time object list and the collector's daily
     * object DB for each queried date: OBJECT_LIST_REAL_TIME drops deploy-replaced pods, so without
     * the daily union their already-stored XLogs would be unreachable by name for past windows.
     * An unresolvable objNameLike fails fast as NOT_FOUND with candidate names, so the caller can
     * self-correct in one step instead of scanning nothing.
     */
    private List<Long> resolveTargetHashes(Long objHash, String objNameLike, List<DaySplitter.Segment> segments) {
        if (objHash != null && objHash != 0L) {
            List<Long> one = new ArrayList<>();
            one.add(objHash);
            return one;
        }
        if (objNameLike == null || objNameLike.isBlank()) {
            List<Long> none = new ArrayList<>();
            none.add(null);
            return none;
        }
        List<SObjectDto> all = listObjectsImpl();
        Set<String> dates = new LinkedHashSet<>();
        for (DaySplitter.Segment seg : segments) {
            dates.add(scouter.mcp.time.TimeRange.yyyymmdd(seg.fromMillis(), config.zone()));
        }
        for (String ymd : dates) {
            all = TargetResolver.unionByHash(all, listObjectsByDateImpl(ymd));
        }
        List<SObjectDto> matched = TargetResolver.match(all, objNameLike);
        if (matched.isEmpty()) {
            throw McpError.of(McpError.Code.NOT_FOUND,
                            Messages.get(config.locale(), "error.target_not_found", objNameLike.trim()))
                    .withHint("candidates", String.join(", ", TargetResolver.suggest(all, 10)));
        }
        if (matched.size() > Limits.SEARCH_MAX_OBJ) {
            log.warn("objNameLike matched too many instances, capping: query={}, matched={}, cap={}",
                    objNameLike.trim(), matched.size(), Limits.SEARCH_MAX_OBJ);
            matched = matched.subList(0, Limits.SEARCH_MAX_OBJ);
        }
        List<Long> hashes = new ArrayList<>(matched.size());
        for (SObjectDto o : matched) {
            hashes.add((long) o.objHash());
        }
        return hashes;
    }

    private XlogSearchResult searchXlogImpl(SearchXlogParams params) {
        // Ported from webapp XLogConsumer.searchXLogList: SEARCH_XLOG_LIST.
        // Policy (Limits): to defend against the production firehose, validate the window/filters and,
        // during streaming, cut the socket once the limit or scan cap is reached so that the collector
        // scan/transfer and the MCP heap stop together. The per-instance/day passes run with bounded
        // concurrency under a wall-clock deadline (see fanout) so a fuzzy target (many pods) stays well
        // under the MCP client timeout instead of adding up sequential round-trips.
        validateSearchWindow(params);

        String servicePattern = buildServicePattern(params.service());
        List<DaySplitter.Segment> segments =
                DaySplitter.splitByCalendarDay(params.fromMillis(), params.toMillis(), config.zone());
        List<Long> targetHashes = resolveTargetHashes(params.objHash(), params.objNameLike(), segments);

        final int limit = params.limit() <= 0
                ? Limits.SEARCH_DEFAULT_LIMIT
                : Math.min(params.limit(), Limits.SEARCH_MAX_LIMIT);

        // Shared streaming state across concurrent passes. limit and scan cap stay global; each pass
        // collects into its own list (thread-confined) so only these counters need to be atomic.
        final List<Pass> passes = buildPasses(targetHashes, segments);
        final Integer minElapsed = params.minElapsedMs();
        final boolean onlyError = params.onlyError();
        final AtomicInteger examined = new AtomicInteger();
        final AtomicBoolean stoppedByLimit = new AtomicBoolean(false);
        final AtomicBoolean partial = new AtomicBoolean(false);

        ensurePassBudget(passes.size(), config.locale());
        long startedAt = System.currentTimeMillis();

        Fanout<List<XLogPack>> fo = fanout(passes.size(), (tcp, idx) -> {
            List<XLogPack> local = new ArrayList<>();
            if (stoppedByLimit.get()) {
                return local; // another pass already filled the global limit
            }
            Pass qp = passes.get(idx);
            MapPack param = buildXlogParam(qp.seg(), qp.hash(), servicePattern, params);
            try {
                tcp.process(RequestCmd.SEARCH_XLOG_LIST, param, in -> {
                    Pack p = in.readPack();
                    int ex = examined.incrementAndGet();
                    if (p instanceof XLogPack xp) {
                        boolean keep = (minElapsed == null || xp.elapsed >= minElapsed) && (!onlyError || xp.error != 0);
                        if (keep && local.size() < limit) {
                            local.add(xp);
                        }
                    }
                    if (local.size() >= limit) {
                        stoppedByLimit.set(true);
                        throw new StopStreaming();
                    }
                    if (ex >= Limits.SEARCH_SCAN_CAP) {
                        throw new StopStreaming();
                    }
                });
            } catch (RuntimeException e) {
                if (isNormalStreamStop(e)) {
                    // Intended early stop, or EOF: routine per-pass, fan-out continues.
                } else if (isReadTimeout(e)) {
                    // This instance's server-side scan exceeded the read timeout; keep what it produced
                    // and mark the overall result partial rather than failing the whole search.
                    partial.set(true);
                } else {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof McpError) {
                        throw (McpError) cause;
                    }
                    throw McpError.of(McpError.Code.INTERNAL, String.valueOf(cause.getMessage()));
                }
            }
            return local;
        });

        boolean timedOut = fo.timedOut() || partial.get();
        List<XLogPack> kept = mergeCapped(fo.results(), limit);
        boolean limitReached = kept.size() >= limit;
        boolean scanCapReached = examined.get() >= Limits.SEARCH_SCAN_CAP && !limitReached;
        boolean truncated = limitReached || scanCapReached || timedOut;

        // Structured stderr telemetry: per-request fan-out/scan volume for post-hoc load analysis.
        log.info("search_xlog done: passes={}, examined={}, kept={}, truncated={}, timedOut={}, tookMs={}",
                passes.size(), examined.get(), kept.size(), truncated, timedOut,
                System.currentTimeMillis() - startedAt);

        boolean looksLikeApp = false;
        List<String> candidates = List.of();
        if (kept.isEmpty() && !timedOut && params.service() != null && !params.service().isBlank()) {
            looksLikeApp = serviceFilterLooksLikeApp(params.service());
            if (!looksLikeApp) {
                candidates = discoverServiceCandidates(params, targetHashes);
            }
        }
        return new XlogSearchResult(mapRows(kept), truncated, scanCapReached, examined.get(),
                looksLikeApp, candidates, timedOut);
    }

    /**
     * Detects the classic mistake behind empty results: an application name ("shop-order-api") passed
     * as the service filter, which matches request URLs, never app names. If the token matches an
     * objName, the caller should have used objNameLike — surfaced as a targeted hint instead of the
     * generic "widen the window" advice. Only evaluated on empty results (one OBJECT_LIST round-trip).
     */
    private boolean serviceFilterLooksLikeApp(String service) {
        if (service == null || service.isBlank()) {
            return false;
        }
        try {
            return !TargetResolver.match(listObjectsImpl(), service).isEmpty();
        } catch (RuntimeException e) {
            log.debug("service-vs-app check skipped: cause={}", String.valueOf(e.getMessage()));
            return false;
        }
    }

    /**
     * Empty-result rescue for sloppy service queries the case-sensitive server pattern cannot match
     * ("orderdetail" vs "orderDetail", reordered words...). Re-scans the same window WITHOUT the service
     * filter (bounded by SEARCH_SCAN_CAP), decodes the distinct service names seen, and returns the
     * ones containing every query token case-insensitively (falling back to any-token), ordered by
     * traffic. The caller retries with an exact name — one extra bounded pass, only on empty results.
     */
    private List<String> discoverServiceCandidates(SearchXlogParams params, List<Long> targetHashes) {
        List<String> tokens = ServiceQueryNormalizer.normalize(params.service()).tokens();
        Map<Integer, long[]> countAndYmd = new HashMap<>(); // serviceHash -> {count, anyYmd}
        int[] examined = {0};
        try {
            outer:
            for (Long hash : targetHashes) {
                for (DaySplitter.Segment seg : DaySplitter.splitByCalendarDay(params.fromMillis(), params.toMillis(), config.zone())) {
                    MapPack param = new MapPack();
                    param.put(ParamConstant.DATE, scouter.mcp.time.TimeRange.yyyymmdd(seg.fromMillis(), config.zone()));
                    param.put(ParamConstant.XLOG_START_TIME, seg.fromMillis());
                    param.put(ParamConstant.XLOG_END_TIME, seg.toMillis());
                    if (hash != null) {
                        param.put(ParamConstant.OBJ_HASH, hash);
                    }
                    TcpProxy tcp = TcpProxy.getTcpProxy(server);
                    try {
                        tcp.process(RequestCmd.SEARCH_XLOG_LIST, param, in -> {
                            Pack p = in.readPack();
                            examined[0]++;
                            if (p instanceof XLogPack xp) {
                                long ymd = Long.parseLong(scouter.mcp.time.TimeRange.yyyymmdd(xp.endTime, config.zone()));
                                countAndYmd.computeIfAbsent(xp.service, k -> new long[]{0, ymd})[0]++;
                            }
                            if (examined[0] >= Limits.SEARCH_SCAN_CAP) {
                                throw new StopStreaming();
                            }
                        });
                    } catch (RuntimeException e) {
                        if (!isNormalStreamStop(e)) {
                            throw e;
                        }
                        // Normal stop (StopStreaming or EOF): keep scanning remaining instances/segments.
                    } finally {
                        TcpProxy.close(tcp);
                    }
                    if (examined[0] >= Limits.SEARCH_SCAN_CAP) {
                        break outer;
                    }
                }
            }

            TextDictionary dict = new TextDictionary(server, null);
            Map<Long, Set<Integer>> byYmd = new HashMap<>();
            countAndYmd.forEach((h, cy) -> byYmd.computeIfAbsent(cy[1], k -> new LinkedHashSet<>()).add(h));
            byYmd.forEach((ymd, hashes) -> dict.prefetch(TextTypes.SERVICE, ymd, hashes));

            record Named(String name, long count) {
            }
            List<Named> all = new ArrayList<>();
            countAndYmd.forEach((h, cy) -> {
                String name = dict.service(cy[1], h);
                if (name != null) {
                    all.add(new Named(name, cy[0]));
                }
            });
            List<Named> matched = new ArrayList<>();
            for (Named n : all) {
                String lower = n.name().toLowerCase();
                boolean allTokens = !tokens.isEmpty() && tokens.stream().allMatch(lower::contains);
                if (allTokens) {
                    matched.add(n);
                }
            }
            if (matched.isEmpty() && !tokens.isEmpty()) {
                for (Named n : all) {
                    String lower = n.name().toLowerCase();
                    if (tokens.stream().anyMatch(lower::contains)) {
                        matched.add(n);
                    }
                }
            }
            matched.sort((a, b) -> Long.compare(b.count(), a.count()));
            List<String> out = new ArrayList<>();
            for (int i = 0; i < Math.min(10, matched.size()); i++) {
                out.add(matched.get(i).name());
            }
            return out;
        } catch (RuntimeException e) {
            // Discovery is best-effort: never let the rescue pass break the original (empty) result.
            log.debug("service candidate discovery skipped: cause={}", String.valueOf(e.getMessage()));
            return List.of();
        }
    }

    /** One collector round-trip target: a single objHash (null = unfiltered) over a single day segment. */
    private record Pass(Long hash, DaySplitter.Segment seg) {
    }

    /** Cartesian product of target instances x day segments, the unit of fan-out for XLog streaming. */
    private static List<Pass> buildPasses(List<Long> hashes, List<DaySplitter.Segment> segments) {
        List<Pass> passes = new ArrayList<>(hashes.size() * segments.size());
        for (Long hash : hashes) {
            for (DaySplitter.Segment seg : segments) {
                passes.add(new Pass(hash, seg));
            }
        }
        return passes;
    }

    /** SEARCH_XLOG_LIST request params for one pass (shared by search/summary). */
    private MapPack buildXlogParam(DaySplitter.Segment seg, Long objHash, String servicePattern, SearchXlogParams params) {
        MapPack param = new MapPack();
        param.put(ParamConstant.DATE, scouter.mcp.time.TimeRange.yyyymmdd(seg.fromMillis(), config.zone()));
        param.put(ParamConstant.XLOG_START_TIME, seg.fromMillis());
        param.put(ParamConstant.XLOG_END_TIME, seg.toMillis());
        if (objHash != null) {
            param.put(ParamConstant.OBJ_HASH, objHash);
        }
        if (servicePattern != null) {
            param.put(ParamConstant.XLOG_SERVICE, servicePattern);
        }
        if (params.login() != null && !params.login().isBlank()) {
            param.put(ParamConstant.XLOG_LOGIN, params.login().trim());
        }
        if (params.ip() != null && !params.ip().isBlank()) {
            param.put(ParamConstant.XLOG_IP, params.ip().trim());
        }
        if (params.desc() != null && !params.desc().isBlank()) {
            param.put(ParamConstant.XLOG_DESC, params.desc().trim());
        }
        return param;
    }

    /** Concatenate per-pass results in pass order and cap the total, so the global limit is deterministic. */
    static <T> List<T> mergeCapped(List<List<T>> parts, int cap) {
        List<T> out = new ArrayList<>();
        for (List<T> part : parts) {
            for (T item : part) {
                if (out.size() >= cap) {
                    return out;
                }
                out.add(item);
            }
        }
        return out;
    }

    /** Per-pass outputs in original pass order, plus whether the deadline forced remaining passes to abort. */
    private record Fanout<T>(List<T> results, boolean timedOut) {
    }

    /**
     * Run {@code passCount} independent collector round-trips with bounded concurrency
     * (Limits.FANOUT_CONCURRENCY) under a wall-clock deadline (Limits.FANOUT_DEADLINE_MS). Each pass
     * gets its own pooled TcpProxy and streams into a thread-confined result via {@code work}. On
     * deadline expiry, in-flight sockets are aborted to unblock their reads and the results gathered so
     * far (in pass order) are returned with timedOut=true — a wide target degrades to a partial answer
     * instead of blowing the MCP client timeout. A genuine error from any pass propagates (fail-fast).
     */
    private <T> Fanout<T> fanout(int passCount, BiFunction<TcpProxy, Integer, T> work) {
        if (passCount <= 0) {
            return new Fanout<>(List.of(), false);
        }
        long deadline = System.currentTimeMillis() + Limits.FANOUT_DEADLINE_MS;
        int concurrency = Math.max(1, Math.min(passCount, Limits.FANOUT_CONCURRENCY));
        ExecutorService exec = Executors.newFixedThreadPool(concurrency);
        Set<TcpProxy> inFlight = ConcurrentHashMap.newKeySet();
        Map<Integer, T> results = new ConcurrentHashMap<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        AtomicBoolean aborted = new AtomicBoolean(false);
        try {
            for (int i = 0; i < passCount; i++) {
                final int idx = i;
                exec.submit(() -> {
                    if (aborted.get() || failure.get() != null) {
                        return;
                    }
                    TcpProxy tcp = TcpProxy.getTcpProxy(server);
                    inFlight.add(tcp);
                    try {
                        T r = work.apply(tcp, idx);
                        if (r != null) {
                            results.put(idx, r);
                        }
                    } catch (RuntimeException e) {
                        // A read interrupted by a deadline abort surfaces here too; only record genuine errors.
                        if (!aborted.get()) {
                            failure.compareAndSet(null, e);
                        }
                    } finally {
                        inFlight.remove(tcp);
                        TcpProxy.close(tcp);
                    }
                });
            }
            exec.shutdown();
            long wait = Math.max(0, deadline - System.currentTimeMillis());
            boolean finished = exec.awaitTermination(wait, TimeUnit.MILLISECONDS);
            if (!finished) {
                aborted.set(true);
                for (TcpProxy t : inFlight) {
                    t.abort(); // directly close the socket to unblock a read in progress
                }
                exec.awaitTermination(2, TimeUnit.SECONDS); // brief unwind grace
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exec.shutdownNow();
        }
        RuntimeException f = failure.get();
        if (f != null) {
            throw f;
        }
        List<T> ordered = new ArrayList<>(results.size());
        for (int i = 0; i < passCount; i++) {
            T r = results.get(i);
            if (r != null) {
                ordered.add(r);
            }
        }
        return new Fanout<>(ordered, aborted.get());
    }

    @Override
    public XlogSummaryResult getServiceSummary(SearchXlogParams params) {
        return SessionRetry.execute(() -> getServiceSummaryImpl(params), this::relogin);
    }

    /** Per-service accumulator. Retains elapsed samples (bounded by the scan cap) for an exact p95. */
    private static final class ServiceAcc {
        long count;
        long errorCount;
        long sumElapsed;
        long maxElapsed;
        long anyYmd;
        final List<Integer> elapseds = new ArrayList<>();

        void add(int elapsed, boolean error, long ymd) {
            count++;
            if (error) {
                errorCount++;
            }
            sumElapsed += elapsed;
            if (elapsed > maxElapsed) {
                maxElapsed = elapsed;
            }
            elapseds.add(elapsed);
            if (anyYmd == 0) {
                anyYmd = ymd;
            }
        }
    }

    /** Fold another pass's per-service accumulator into a merged one (used to combine parallel passes). */
    private static void mergeAcc(ServiceAcc into, ServiceAcc from) {
        into.count += from.count;
        into.errorCount += from.errorCount;
        into.sumElapsed += from.sumElapsed;
        if (from.maxElapsed > into.maxElapsed) {
            into.maxElapsed = from.maxElapsed;
        }
        if (into.anyYmd == 0) {
            into.anyYmd = from.anyYmd;
        }
        into.elapseds.addAll(from.elapseds);
    }

    private XlogSummaryResult getServiceSummaryImpl(SearchXlogParams params) {
        // Same SEARCH_XLOG_LIST stream as search_xlog, but rows are folded into per-service counters
        // instead of retained. This answers "which service got slow/errored" over a wide window while
        // keeping the response to a few dozen lines. Scan cap is higher since no rows are held. Passes
        // fan out with bounded concurrency under the same deadline as search (see fanout).
        validateSearchWindow(params);
        String servicePattern = buildServicePattern(params.service());
        List<DaySplitter.Segment> segments =
                DaySplitter.splitByCalendarDay(params.fromMillis(), params.toMillis(), config.zone());
        List<Long> targetHashes = resolveTargetHashes(params.objHash(), params.objNameLike(), segments);
        Integer minElapsed = params.minElapsedMs();
        boolean onlyError = params.onlyError();

        List<Pass> passes = buildPasses(targetHashes, segments);
        AtomicInteger examined = new AtomicInteger();
        AtomicBoolean capped = new AtomicBoolean(false);
        AtomicBoolean partial = new AtomicBoolean(false);

        ensurePassBudget(passes.size(), config.locale());
        long startedAt = System.currentTimeMillis();

        Fanout<Map<Integer, ServiceAcc>> fo = fanout(passes.size(), (tcp, idx) -> {
            Map<Integer, ServiceAcc> local = new HashMap<>();
            if (capped.get()) {
                return local; // the global scan cap was already reached by other passes
            }
            Pass qp = passes.get(idx);
            MapPack param = buildXlogParam(qp.seg(), qp.hash(), servicePattern, params);
            try {
                tcp.process(RequestCmd.SEARCH_XLOG_LIST, param, in -> {
                    Pack p = in.readPack();
                    int ex = examined.incrementAndGet();
                    if (p instanceof XLogPack xp) {
                        boolean keep = (minElapsed == null || xp.elapsed >= minElapsed) && (!onlyError || xp.error != 0);
                        if (keep) {
                            long ymd = Long.parseLong(scouter.mcp.time.TimeRange.yyyymmdd(xp.endTime, config.zone()));
                            local.computeIfAbsent(xp.service, k -> new ServiceAcc())
                                    .add(xp.elapsed, xp.error != 0, ymd);
                        }
                    }
                    if (ex >= Limits.SUMMARY_SCAN_CAP) {
                        capped.set(true);
                        throw new StopStreaming();
                    }
                });
            } catch (RuntimeException e) {
                if (isNormalStreamStop(e)) {
                    // EOF: no data for this objHash/day segment — routine when fanning out over instances.
                } else if (isReadTimeout(e)) {
                    // Instance too slow to stream within the read timeout; fold what it gave and flag partial.
                    partial.set(true);
                } else {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof McpError) {
                        throw (McpError) cause;
                    }
                    throw McpError.of(McpError.Code.INTERNAL, String.valueOf(cause.getMessage()));
                }
            }
            return local;
        });

        boolean timedOut = fo.timedOut() || partial.get();
        Map<Integer, ServiceAcc> byService = new HashMap<>();
        for (Map<Integer, ServiceAcc> part : fo.results()) {
            part.forEach((svc, acc) -> mergeAcc(byService.computeIfAbsent(svc, k -> new ServiceAcc()), acc));
        }

        log.info("get_service_summary done: passes={}, examined={}, services={}, capped={}, timedOut={}, tookMs={}",
                passes.size(), examined.get(), byService.size(), capped.get(), timedOut,
                System.currentTimeMillis() - startedAt);

        boolean looksLikeApp = false;
        List<String> candidates = List.of();
        if (byService.isEmpty() && !timedOut && params.service() != null && !params.service().isBlank()) {
            looksLikeApp = serviceFilterLooksLikeApp(params.service());
            if (!looksLikeApp) {
                candidates = discoverServiceCandidates(params, targetHashes);
            }
        }
        return buildSummary(byService, examined.get(), capped.get(), looksLikeApp, candidates, timedOut);
    }

    private XlogSummaryResult buildSummary(Map<Integer, ServiceAcc> byService, int examined, boolean capped,
                                           boolean serviceLooksLikeApp, List<String> serviceCandidates,
                                           boolean timedOut) {
        // Decode service names in one GET_TEXT_PACK batch per day (objName is not needed here).
        TextDictionary dict = new TextDictionary(server, null);
        Map<Long, Set<Integer>> byYmd = new HashMap<>();
        for (Map.Entry<Integer, ServiceAcc> e : byService.entrySet()) {
            byYmd.computeIfAbsent(e.getValue().anyYmd, k -> new LinkedHashSet<>()).add(e.getKey());
        }
        byYmd.forEach((ymd, hashes) -> dict.prefetch(TextTypes.SERVICE, ymd, hashes));

        List<ServiceSummaryDto> list = new ArrayList<>(byService.size());
        int totalCount = 0;
        for (Map.Entry<Integer, ServiceAcc> e : byService.entrySet()) {
            ServiceAcc a = e.getValue();
            totalCount += a.count;
            String name = dict.service(a.anyYmd, e.getKey());
            String service = name != null ? name : "#" + e.getKey();
            double errorRate = a.count == 0 ? 0 : (double) a.errorCount / a.count;
            double avg = a.count == 0 ? 0 : (double) a.sumElapsed / a.count;
            long p95 = Percentiles.nearestRank(a.elapseds, 95);
            list.add(new ServiceSummaryDto(service, a.count, a.errorCount, errorRate, avg, a.maxElapsed, p95));
        }
        list.sort((x, y) -> Long.compare(y.count(), x.count())); // top by traffic
        if (list.size() > Limits.SUMMARY_MAX_SERVICES) {
            list = new ArrayList<>(list.subList(0, Limits.SUMMARY_MAX_SERVICES));
        }
        return new XlogSummaryResult(list, totalCount, capped, examined, serviceLooksLikeApp, serviceCandidates,
                timedOut);
    }

    /**
     * Map XLogPacks to DTOs, batch-prefetching service/error text so the dictionary is hit from cache
     * instead of performing per-row GET_TEXT_PACK round-trips (matters for large result sets).
     */
    private List<XLogRowDto> mapRows(List<XLogPack> packs) {
        Map<Long, Set<Integer>> serviceByYmd = new HashMap<>();
        Map<Long, Set<Integer>> errorByYmd = new HashMap<>();
        for (XLogPack xp : packs) {
            long ymd = Long.parseLong(scouter.mcp.time.TimeRange.yyyymmdd(xp.endTime, config.zone()));
            serviceByYmd.computeIfAbsent(ymd, k -> new LinkedHashSet<>()).add(xp.service);
            if (xp.error != 0) {
                errorByYmd.computeIfAbsent(ymd, k -> new LinkedHashSet<>()).add(xp.error);
            }
        }
        TextDictionary dict = new TextDictionary(server, objNameMapFor(packs, serviceByYmd.keySet()));
        serviceByYmd.forEach((ymd, hashes) -> dict.prefetch(TextTypes.SERVICE, ymd, hashes));
        errorByYmd.forEach((ymd, hashes) -> dict.prefetch(TextTypes.ERROR, ymd, hashes));

        List<XLogRowDto> out = new ArrayList<>(packs.size());
        for (XLogPack xp : packs) {
            out.add(PackMapper.toXLogRow(xp, config.zone(), dict));
        }
        return out;
    }

    @Override
    public XLogDetailDto getXlogDetail(long txid, String yyyymmdd, boolean includeBindParams) {
        return SessionRetry.execute(() -> getXlogDetailImpl(txid, yyyymmdd, includeBindParams), this::relogin);
    }

    private XLogDetailDto getXlogDetailImpl(long txid, String yyyymmdd, boolean includeBindParams) {
        // Ported from webapp XLogConsumer.retrieveByTxid + ProfileConsumer.retrieveProfile.
        // 1) XLOG_READ_BY_TXID(getSingle) -> XLogPack summary. 2) TRANX_PROFILE(getSingle) -> XLogProfilePack.profile -> Step[].
        TextDictionary dict = new TextDictionary(server, buildObjNameMap());
        long ymd = Long.parseLong(yyyymmdd);

        XLogRowDto summary = null;
        Step[] steps = null;

        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            // Summary lookup: if the txid is not retained, the collector ends with an empty response (seen as EOF).
            // That is a "not found" signal, so leave summary=null and continue (only surface non-EOF errors).
            try {
                MapPack txidParam = new MapPack();
                txidParam.put(ParamConstant.DATE, yyyymmdd);
                txidParam.put(ParamConstant.XLOG_TXID, txid);
                Pack summaryPack = tcp.getSingle(RequestCmd.XLOG_READ_BY_TXID, txidParam);
                if (summaryPack instanceof XLogPack xp) {
                    summary = PackMapper.toXLogRow(xp, config.zone(), dict);
                }
            } catch (Exception e) {
                if (!isEof(e)) {
                    throw e;
                }
                log.debug("xlog summary not found: txid={}, date={}", txid, yyyymmdd);
            }

            // Profile lookup: trivial/unretained transactions have no profile and show up as EOF.
            // Leaving steps=null yields an empty detail (only surface non-EOF errors).
            try {
                MapPack profileParam = new MapPack();
                profileParam.put(ParamConstant.DATE, yyyymmdd);
                profileParam.put(ParamConstant.XLOG_TXID, txid);
                profileParam.put(ParamConstant.PROFILE_MAX, MAX_PROFILE_BLOCK);
                Pack profilePack = tcp.getSingle(RequestCmd.TRANX_PROFILE, profileParam);
                if (profilePack instanceof XLogProfilePack pp && pp.profile != null) {
                    steps = Step.toObjects(pp.profile);
                }
            } catch (Exception e) {
                if (!isEof(e)) {
                    throw e;
                }
                log.debug("xlog profile not found: txid={}, date={}", txid, yyyymmdd);
            }
        } catch (Exception e) {
            throw McpError.of(McpError.Code.INTERNAL, String.valueOf(e.getMessage()))
                    .withHint("txid", String.valueOf(txid))
                    .withHint("date", yyyymmdd);
        } finally {
            TcpProxy.close(tcp);
        }

        // Batch-decode SQL/ERROR/METHOD text in one round-trip per type so toDetail hits the cache
        // instead of a per-step GET_TEXT_PACK round-trip (profiles can have dozens/hundreds of steps).
        prefetchDetailText(dict, steps, ymd);

        return PackMapper.toDetail(summary, steps, ymd, includeBindParams, dict);
    }

    private void prefetchDetailText(TextDictionary dict, Step[] steps, long ymd) {
        if (steps == null) {
            return;
        }
        Set<Integer> sqls = new LinkedHashSet<>();
        Set<Integer> errors = new LinkedHashSet<>();
        Set<Integer> methods = new LinkedHashSet<>();
        for (Step s : steps) {
            if (s instanceof SqlStep sq) {
                sqls.add(sq.hash);
            } else if (s instanceof ApiCallStep api) {
                if (api.error != 0) {
                    errors.add(api.error);
                }
            } else if (s instanceof MethodStep m) {
                methods.add(m.hash);
            }
        }
        dict.prefetch(TextTypes.SQL, ymd, sqls);
        dict.prefetch(TextTypes.ERROR, ymd, errors);
        dict.prefetch(TextTypes.METHOD, ymd, methods);
    }

    @Override
    public List<XLogRowDto> getXlogByGxid(long gxid, String yyyymmdd) {
        return SessionRetry.execute(() -> getXlogByGxidImpl(gxid, yyyymmdd), this::relogin);
    }

    private List<XLogRowDto> getXlogByGxidImpl(long gxid, String yyyymmdd) {
        // Ported from webapp XLogConsumer.retrieveXLogPacksByGxid: XLOG_READ_BY_GXID(process) -> List<XLogPack>.
        MapPack param = new MapPack();
        param.put(ParamConstant.DATE, yyyymmdd);
        param.put(ParamConstant.XLOG_GXID, gxid);

        List<XLogPack> packs = new ArrayList<>();
        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            List<Pack> resp = tcp.process(RequestCmd.XLOG_READ_BY_GXID, param);
            if (resp != null) {
                for (Pack p : resp) {
                    if (p instanceof XLogPack xp) {
                        packs.add(xp);
                    }
                }
            }
        } finally {
            TcpProxy.close(tcp);
        }
        return mapRows(packs);
    }

    @Override
    public List<AlertDto> getAlerts(long fromMillis, long toMillis, String level, String object, String key, int limit) {
        return SessionRetry.execute(() -> getAlertsImpl(fromMillis, toMillis, level, object, key, limit), this::relogin);
    }

    private List<AlertDto> getAlertsImpl(long fromMillis, long toMillis, String level, String object, String key, int limit) {
        // ALERT_LOAD_TIME: MapPack{date, stime, etime, count, level?, object?, key?} -> stream of AlertPack.
        // Alerts are stored per day, so split the window by calendar day (same as counters/xlogs).
        if (toMillis - fromMillis <= 0) {
            throw McpError.of(McpError.Code.INVALID_INPUT, Messages.get(config.locale(), "error.from_after_to"));
        }
        int cap = limit <= 0 ? 100 : Math.min(limit, 1000);
        Map<Integer, String> objNames = buildObjNameMap();
        List<AlertDto> out = new ArrayList<>();
        for (DaySplitter.Segment seg : DaySplitter.splitByCalendarDay(fromMillis, toMillis, config.zone())) {
            if (out.size() >= cap) {
                break;
            }
            MapPack param = new MapPack();
            param.put("date", scouter.mcp.time.TimeRange.yyyymmdd(seg.fromMillis(), config.zone()));
            param.put("stime", seg.fromMillis());
            param.put("etime", seg.toMillis());
            param.put("count", cap);
            if (level != null && !level.isBlank()) {
                param.put("level", level.trim().toUpperCase());
            }
            if (object != null && !object.isBlank()) {
                param.put("object", object.trim());
            }
            if (key != null && !key.isBlank()) {
                param.put("key", key.trim());
            }
            TcpProxy tcp = TcpProxy.getTcpProxy(server);
            try {
                tcp.process(RequestCmd.ALERT_LOAD_TIME, param, in -> {
                    Pack p = in.readPack();
                    if (p instanceof AlertPack ap && out.size() < cap) {
                        String levelName = AlertLevel.getName(ap.level);
                        String objName = objNames.getOrDefault(ap.objHash, "#" + ap.objHash);
                        out.add(new AlertDto(ap.time, scouter.mcp.time.TimeRange.toIso(ap.time, config.zone()),
                                levelName, ap.objType, ap.objHash, objName, ap.title, ap.message));
                    }
                });
            } catch (Exception e) {
                if (!isEof(e)) {
                    throw McpError.of(McpError.Code.INTERNAL, String.valueOf(e.getMessage()));
                }
            } finally {
                TcpProxy.close(tcp);
            }
        }
        return out;
    }

    @Override
    public List<ActiveServiceDto> getActiveServices(String objType, Long objHash, String objNameLike) {
        return SessionRetry.execute(() -> getActiveServicesResolved(objType, objHash, objNameLike), this::relogin);
    }

    private List<ActiveServiceDto> getActiveServicesResolved(String objType, Long objHash, String objNameLike) {
        if ((objType != null && !objType.isBlank()) || (objHash != null && objHash != 0L)) {
            String objName = objHash != null && objHash != 0L ? buildObjNameMap().get(objHash.intValue()) : null;
            return getActiveServicesImpl(objType, objHash, objName);
        }
        // Fuzzy target: this is a live snapshot, so only alive instances are queried (dead pods have no threads).
        List<SObjectDto> all = listObjectsImpl();
        List<SObjectDto> alive = new ArrayList<>();
        for (SObjectDto o : TargetResolver.match(all, objNameLike)) {
            if (o.alive()) {
                alive.add(o);
            }
        }
        if (alive.isEmpty()) {
            throw McpError.of(McpError.Code.NOT_FOUND,
                            Messages.get(config.locale(), "error.target_not_found", String.valueOf(objNameLike).trim()))
                    .withHint("candidates", String.join(", ", TargetResolver.suggest(all, 10)));
        }
        if (alive.size() > Limits.SEARCH_MAX_OBJ) {
            alive = alive.subList(0, Limits.SEARCH_MAX_OBJ);
        }
        List<ActiveServiceDto> out = new ArrayList<>();
        for (SObjectDto o : alive) {
            out.addAll(getActiveServicesImpl(null, (long) o.objHash(), o.objName()));
        }
        return out;
    }

    private List<ActiveServiceDto> getActiveServicesImpl(String objType, Long objHash, String objName) {
        // OBJECT_ACTIVE_SERVICE_LIST: MapPack{objType|objHash} -> MapPack(s) of parallel ListValues.
        MapPack param = new MapPack();
        if (objType != null && !objType.isBlank()) {
            param.put("objType", objType.trim());
        }
        if (objHash != null && objHash != 0L) {
            param.put("objHash", objHash.intValue());
        }
        List<ActiveServiceDto> out = new ArrayList<>();
        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            tcp.process(RequestCmd.OBJECT_ACTIVE_SERVICE_LIST, param, in -> {
                Pack p = in.readPack();
                if (!(p instanceof MapPack mp)) {
                    return;
                }
                ListValue id = mp.getList("id");
                ListValue name = mp.getList("name");
                ListValue stat = mp.getList("stat");
                ListValue cpu = mp.getList("cpu");
                ListValue txid = mp.getList("txid");
                ListValue service = mp.getList("service");
                ListValue ip = mp.getList("ip");
                ListValue elapsed = mp.getList("elapsed");
                ListValue sql = mp.getList("sql");
                ListValue subcall = mp.getList("subcall");
                ListValue login = mp.getList("login");
                ListValue desc = mp.getList("desc");
                int n = id != null ? id.size() : 0;
                for (int i = 0; i < n; i++) {
                    out.add(new ActiveServiceDto(
                            objName, lvLong(id, i), lvStr(name, i), lvStr(stat, i), lvLong(cpu, i), lvStr(txid, i),
                            lvStr(service, i), lvStr(ip, i), lvLong(elapsed, i),
                            lvStr(sql, i), lvStr(subcall, i), lvStr(login, i), lvStr(desc, i)));
                }
            });
        } catch (Exception e) {
            if (!isEof(e)) {
                throw McpError.of(McpError.Code.INTERNAL, String.valueOf(e.getMessage()));
            }
        } finally {
            TcpProxy.close(tcp);
        }
        return out;
    }

    /**
     * Resolve a fuzzy/explicit target to alive instances, capped. objHash wins over objNameLike.
     * Used by the live agent-relayed tools (threads/env) where dead pods cannot answer.
     */
    private List<SObjectDto> resolveAliveTargets(String objNameLike, Long objHash, int cap) {
        List<SObjectDto> all = listObjectsImpl();
        if (objHash != null && objHash != 0L) {
            for (SObjectDto o : all) {
                if (o.objHash() == objHash.intValue()) {
                    return List.of(o);
                }
            }
            // Unknown hash: attempt the call anyway (the collector may know an object we cannot list).
            return List.of(new SObjectDto(objHash.intValue(), "#" + objHash, null, null, true));
        }
        List<SObjectDto> alive = new ArrayList<>();
        for (SObjectDto o : TargetResolver.match(all, objNameLike)) {
            if (o.alive()) {
                alive.add(o);
            }
        }
        if (alive.isEmpty()) {
            throw McpError.of(McpError.Code.NOT_FOUND,
                            Messages.get(config.locale(), "error.target_not_found", String.valueOf(objNameLike).trim()))
                    .withHint("candidates", String.join(", ", TargetResolver.suggest(all, 10)));
        }
        return alive.size() > cap ? alive.subList(0, cap) : alive;
    }

    @Override
    public List<ThreadListDto> listThreads(String objNameLike, Long objHash) {
        return SessionRetry.execute(() -> listThreadsImpl(objNameLike, objHash), this::relogin);
    }

    private List<ThreadListDto> listThreadsImpl(String objNameLike, Long objHash) {
        // OBJECT_THREAD_LIST: {objHash} -> single MapPack of parallel ListValues
        // (id/name/stat/cpu + java-agent extras txid/elapsed/service; the latter are NullValue
        // when no service runs on the thread). The collector relays to the agent live.
        List<ThreadListDto> out = new ArrayList<>();
        for (SObjectDto o : resolveAliveTargets(objNameLike, objHash, Limits.THREAD_MAX_OBJ)) {
            MapPack param = new MapPack();
            param.put(ParamConstant.OBJ_HASH, o.objHash());
            TcpProxy tcp = TcpProxy.getTcpProxy(server);
            try {
                Pack p = tcp.getSingle(RequestCmd.OBJECT_THREAD_LIST, param);
                if (!(p instanceof MapPack mp)) {
                    continue;
                }
                ListValue id = mp.getList("id");
                ListValue name = mp.getList("name");
                ListValue stat = mp.getList("stat");
                ListValue cpu = mp.getList("cpu");
                ListValue txid = mp.getList("txid");
                ListValue elapsed = mp.getList("elapsed");
                ListValue service = mp.getList("service");
                int n = id != null ? id.size() : 0;
                List<ThreadRowDto> rows = new ArrayList<>(n);
                Map<String, Integer> states = new java.util.TreeMap<>();
                for (int i = 0; i < n; i++) {
                    String state = lvStr(stat, i);
                    states.merge(state != null ? state : "UNKNOWN", 1, Integer::sum);
                    String tx = lvStr(txid, i);
                    rows.add(new ThreadRowDto(lvLong(id, i), lvStr(name, i), state, lvLong(cpu, i),
                            tx, tx != null ? lvLong(elapsed, i) : null, lvStr(service, i)));
                }
                rows.sort((a, b) -> Long.compare(b.cpuMs(), a.cpuMs())); // busiest first
                boolean truncated = rows.size() > Limits.THREAD_MAX_ROWS;
                if (truncated) {
                    rows = new ArrayList<>(rows.subList(0, Limits.THREAD_MAX_ROWS));
                }
                out.add(new ThreadListDto(o.objHash(), o.objName(), n, states, rows, truncated));
            } catch (Exception e) {
                if (!isEof(e)) {
                    throw McpError.of(McpError.Code.INTERNAL, String.valueOf(e.getMessage()));
                }
                // EOF: the agent did not answer (down or foreign agent type) - skip this instance.
            } finally {
                TcpProxy.close(tcp);
            }
        }
        return out;
    }

    @Override
    public ThreadDetailDto getThreadDetail(String objNameLike, Long objHash, Long threadId, long txid,
                                           boolean includeBindParams) {
        return SessionRetry.execute(
                () -> getThreadDetailImpl(objNameLike, objHash, threadId, txid, includeBindParams), this::relogin);
    }

    private ThreadDetailDto getThreadDetailImpl(String objNameLike, Long objHash, Long threadId, long txid,
                                                boolean includeBindParams) {
        // OBJECT_THREAD_DETAIL: {objHash, id, txid} -> single MapPack of scalar keys (space-separated names).
        // The server ignores the request when the "id" key is absent, so id is always sent (0 = let the
        // agent find the thread by txid). A finished txid yields {"Thread Name":"[No Thread] End","State":"end"}.
        SObjectDto target = resolveAliveTargets(objNameLike, objHash, 1).get(0);
        MapPack param = new MapPack();
        param.put(ParamConstant.OBJ_HASH, target.objHash());
        param.put("id", threadId != null ? threadId : 0L);
        param.put("txid", txid);
        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            Pack p = tcp.getSingle(RequestCmd.OBJECT_THREAD_DETAIL, param);
            if (!(p instanceof MapPack mp)) {
                throw McpError.of(McpError.Code.NOT_FOUND,
                        Messages.get(config.locale(), "error.thread_detail_empty"));
            }
            return new ThreadDetailDto(
                    target.objHash(), target.objName(),
                    textOrNull(mp, "Thread Name"), longOrNull(mp, "Thread Id"),
                    textOrNull(mp, "State"),
                    Truncate.text(textOrNull(mp, "Stack Trace"), Limits.STACK_TEXT_MAX_CHARS),
                    longOrNull(mp, "Thread Cpu Time"), longOrNull(mp, "Thread User Time"),
                    longOrNull(mp, "Blocked Count"), longOrNull(mp, "Blocked Time"),
                    longOrNull(mp, "Waited Count"), longOrNull(mp, "Waited Time"),
                    textOrNull(mp, "Lock Name"), longOrNull(mp, "Lock Owner Id"), textOrNull(mp, "Lock Owner Name"),
                    textOrNull(mp, "Service Txid"), textOrNull(mp, "Service Name"), longOrNull(mp, "Service Elapsed"),
                    textOrNull(mp, "SQL"),
                    includeBindParams ? textOrNull(mp, "SQLActiveBindVar") : null,
                    textOrNull(mp, "Subcall"));
        } catch (McpError e) {
            throw e;
        } catch (Exception e) {
            if (isEof(e)) {
                throw McpError.of(McpError.Code.NOT_FOUND,
                        Messages.get(config.locale(), "error.thread_detail_empty"));
            }
            throw McpError.of(McpError.Code.INTERNAL, String.valueOf(e.getMessage()));
        } finally {
            TcpProxy.close(tcp);
        }
    }

    @Override
    public EnvDto getObjectEnv(String objNameLike, Long objHash) {
        return SessionRetry.execute(() -> getObjectEnvImpl(objNameLike, objHash), this::relogin);
    }

    private EnvDto getObjectEnvImpl(String objNameLike, Long objHash) {
        // OBJECT_ENV: {objHash} -> single MapPack with the whole System.getProperties() flattened (no fixed keys).
        SObjectDto target = resolveAliveTargets(objNameLike, objHash, 1).get(0);
        MapPack param = new MapPack();
        param.put(ParamConstant.OBJ_HASH, target.objHash());
        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            Pack p = tcp.getSingle(RequestCmd.OBJECT_ENV, param);
            Map<String, String> props = new java.util.TreeMap<>();
            if (p instanceof MapPack mp) {
                java.util.Iterator<String> it = mp.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    props.put(k, mp.getText(k));
                }
            }
            return new EnvDto(target.objHash(), target.objName(), props);
        } catch (Exception e) {
            if (isEof(e)) {
                // Agent did not answer (down / foreign type): surface as an empty property set.
                return new EnvDto(target.objHash(), target.objName(), Map.of());
            }
            throw McpError.of(McpError.Code.INTERNAL, String.valueOf(e.getMessage()));
        } finally {
            TcpProxy.close(tcp);
        }
    }

    @Override
    public SummaryResult getSummary(String category, long fromMillis, long toMillis,
                                    String objType, Long objHash, String objNameLike) {
        return SessionRetry.execute(
                () -> getSummaryImpl(category, fromMillis, toMillis, objType, objHash, objNameLike), this::relogin);
    }

    private static String summaryCmd(String category) {
        return switch (category) {
            case "service" -> RequestCmd.LOAD_SERVICE_SUMMARY;
            case "sql" -> RequestCmd.LOAD_SQL_SUMMARY;
            case "apiCall" -> RequestCmd.LOAD_APICALL_SUMMARY;
            case "ip" -> RequestCmd.LOAD_IP_SUMMARY;
            case "userAgent" -> RequestCmd.LOAD_UA_SUMMARY;
            case "error" -> RequestCmd.LOAD_SERVICE_ERROR_SUMMARY;
            case "alert" -> RequestCmd.LOAD_ALERT_SUMMARY;
            default -> null;
        };
    }

    /** Accumulator for id-keyed summary rows merged across days/instances. */
    static final class SumAcc {
        long count;
        long error;
        long elapsed;
        long anyYmd;
        long sampleTxid;
        int serviceHash;
        int messageHash;
        String title;
        int level;
    }

    private SummaryResult getSummaryImpl(String category, long fromMillis, long toMillis,
                                         String objType, Long objHash, String objNameLike) {
        // LOAD_*_SUMMARY: {date, stime, etime, objType?, objHash} -> single columnar MapPack per day.
        // The collector pre-aggregates, so this never streams XLog rows: the cheapest wide-window tool.
        String cmd = summaryCmd(category);
        if (cmd == null) {
            throw McpError.of(McpError.Code.INVALID_INPUT,
                    Messages.get(config.locale(), "error.summary_bad_category", String.valueOf(category)));
        }
        if (toMillis - fromMillis <= 0) {
            throw McpError.of(McpError.Code.INVALID_INPUT, Messages.get(config.locale(), "error.from_after_to"));
        }
        List<DaySplitter.Segment> segments = DaySplitter.splitByCalendarDay(fromMillis, toMillis, config.zone());
        if (segments.size() > Limits.DAILY_STAT_MAX_DAYS) {
            throw McpError.of(McpError.Code.INVALID_INPUT,
                    Messages.get(config.locale(), "error.summary_window_too_long", Limits.DAILY_STAT_MAX_DAYS));
        }
        // Target: explicit objHash > fuzzy objNameLike > objType (server-side) > all.
        // objNameLike normally fans out per instance, but for a daily summary that is ruinous on a wide or
        // past-date window (it resolves to every rotated pod). When the app owns its objType (one type,
        // no members outside the match — true in this deployment), aggregate the WHOLE app in ONE
        // server-side pass by objType instead: far faster and complete (covers rotated pods, not capped).
        String effectiveObjType = objType;
        List<Long> hashes = new ArrayList<>();
        if (objHash != null && objHash != 0L) {
            hashes.add(objHash);
        } else if (objNameLike != null && !objNameLike.isBlank()) {
            List<SObjectDto> all = listObjectsImpl();
            List<SObjectDto> matched = TargetResolver.match(all, objNameLike);
            if (matched.isEmpty()) {
                throw McpError.of(McpError.Code.NOT_FOUND,
                                Messages.get(config.locale(), "error.target_not_found", objNameLike.trim()))
                        .withHint("candidates", String.join(", ", TargetResolver.suggest(all, 10)));
            }
            String soleType = (objType == null || objType.isBlank())
                    ? TargetResolver.soleExclusiveObjType(all, matched) : null;
            if (soleType != null) {
                effectiveObjType = soleType; // single server-side aggregation, no fan-out
                hashes.add(0L);
            } else {
                for (SObjectDto o : matched.size() > Limits.SEARCH_MAX_OBJ
                        ? matched.subList(0, Limits.SEARCH_MAX_OBJ) : matched) {
                    hashes.add((long) o.objHash());
                }
            }
        } else {
            hashes.add(0L); // 0 = no objHash restriction (whole objType / whole collector)
        }
        final String queryObjType = effectiveObjType;
        List<Pass> passes = buildPasses(hashes, segments);
        ensurePassBudget(passes.size(), config.locale());
        long startedAt = System.currentTimeMillis();

        // Each daily-summary pass is one small pre-aggregated read, but a fuzzy objNameLike still fans
        // out over many instances; run them with the same bounded concurrency/deadline as the XLog tools
        // so a wide target degrades to a partial aggregate instead of exceeding the client timeout.
        AtomicBoolean partial = new AtomicBoolean(false);
        Fanout<SummaryPass> fo = fanout(passes.size(), (tcp, idx) -> {
            Pass qp = passes.get(idx);
            String ymd = scouter.mcp.time.TimeRange.yyyymmdd(qp.seg().fromMillis(), config.zone());
            MapPack param = new MapPack();
            param.put(ParamConstant.DATE, ymd);
            param.put(ParamConstant.STIME, qp.seg().fromMillis());
            param.put(ParamConstant.ETIME, qp.seg().toMillis());
            if (queryObjType != null && !queryObjType.isBlank()) {
                param.put(ParamConstant.OBJ_TYPE, queryObjType.trim());
            }
            param.put(ParamConstant.OBJ_HASH, qp.hash().intValue());
            try {
                Pack p = tcp.getSingle(cmd, param);
                if (p instanceof MapPack mp) {
                    return new SummaryPass(mp, Long.parseLong(ymd));
                }
            } catch (RuntimeException e) {
                if (isEof(e)) {
                    // EOF: no summary data for this day/target - routine.
                } else if (isReadTimeout(e)) {
                    partial.set(true);
                } else {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof McpError) {
                        throw (McpError) cause;
                    }
                    throw McpError.of(McpError.Code.INTERNAL, String.valueOf(cause.getMessage()));
                }
            }
            return null;
        });

        // Fold day MapPacks single-threaded (mergeSummaryDay stays the one authoritative fold path).
        Map<String, SumAcc> acc = new LinkedHashMap<>();
        for (SummaryPass sp : fo.results()) {
            if (sp != null) {
                mergeSummaryDay(category, sp.mp(), sp.ymd(), acc);
            }
        }
        boolean timedOut = fo.timedOut() || partial.get();
        SummaryResult result = buildSummaryResult(category, acc, timedOut);
        log.info("get_summary done: category={}, passes={}, rows={}, timedOut={}, tookMs={}",
                category, passes.size(), acc.size(), timedOut, System.currentTimeMillis() - startedAt);
        return result;
    }

    /** One day's raw pre-aggregated summary MapPack from a single pass, tagged with its date. */
    private record SummaryPass(MapPack mp, long ymd) {
    }

    /**
     * Fold one day's columnar summary MapPack into the id-keyed accumulator. For the error category the
     * "error" column is a TEXT HASH (not a count) and rows are keyed by (id,error,service); everywhere
     * else count/error/elapsed are additive metrics keyed by id alone.
     */
    static void mergeSummaryDay(String category, MapPack mp, long ymd, Map<String, SumAcc> acc) {
        ListValue id = mp.getList("id");
        int n = id != null ? id.size() : 0;
        ListValue count = mp.getList("count");
        ListValue error = mp.getList("error");
        ListValue elapsed = mp.getList("elapsed");
        ListValue service = mp.getList("service");
        ListValue message = mp.getList("message");
        ListValue txid = mp.getList("txid");
        ListValue title = mp.getList("title");
        ListValue level = mp.getList("level");
        for (int i = 0; i < n; i++) {
            String key;
            if ("error".equals(category)) {
                key = id.getInt(i) + ":" + (error != null ? error.getInt(i) : 0)
                        + ":" + (service != null ? service.getInt(i) : 0);
            } else {
                key = String.valueOf(id.getInt(i));
            }
            SumAcc a = acc.computeIfAbsent(key, k -> new SumAcc());
            if (a.anyYmd == 0) {
                a.anyYmd = ymd;
            }
            a.count += count != null && i < count.size() ? count.getLong(i) : 0;
            if ("error".equals(category)) {
                a.serviceHash = service != null ? service.getInt(i) : 0;
                a.messageHash = message != null && i < message.size() ? message.getInt(i) : 0;
                a.error = error != null ? error.getInt(i) : 0; // error text hash (not a count)
                if (a.sampleTxid == 0 && txid != null && i < txid.size()) {
                    a.sampleTxid = txid.getLong(i);
                }
            } else {
                a.error += error != null && i < error.size() ? error.getLong(i) : 0;
                a.elapsed += elapsed != null && i < elapsed.size() ? elapsed.getLong(i) : 0;
            }
            if ("alert".equals(category)) {
                if (a.title == null && title != null && i < title.size()) {
                    a.title = title.getString(i);
                }
                a.level = level != null && i < level.size() ? level.getInt(i) : 0;
            }
        }
    }

    private SummaryResult buildSummaryResult(String category, Map<String, SumAcc> acc, boolean timedOut) {
        // Sort by count desc, cap rows, then batch-resolve hashes to text (one GET_TEXT_PACK per day).
        List<Map.Entry<String, SumAcc>> entries = new ArrayList<>(acc.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue().count, a.getValue().count));
        boolean truncated = entries.size() > Limits.SUMMARY_TOOL_MAX_ROWS;
        if (truncated) {
            entries = entries.subList(0, Limits.SUMMARY_TOOL_MAX_ROWS);
        }
        TextDictionary dict = new TextDictionary(server, null);
        String textType = switch (category) {
            case "service" -> TextTypes.SERVICE;
            case "sql" -> TextTypes.SQL;
            case "apiCall" -> TextTypes.APICALL;
            case "userAgent" -> TextTypes.USER_AGENT;
            default -> null;
        };
        if (textType != null) {
            Map<Long, Set<Integer>> byYmd = new HashMap<>();
            for (Map.Entry<String, SumAcc> e : entries) {
                byYmd.computeIfAbsent(e.getValue().anyYmd, k -> new LinkedHashSet<>())
                        .add(Integer.parseInt(e.getKey()));
            }
            byYmd.forEach((ymd, hs) -> dict.prefetch(textType, ymd, hs));
        }
        if ("error".equals(category)) {
            Map<Long, Set<Integer>> errByYmd = new HashMap<>();
            Map<Long, Set<Integer>> svcByYmd = new HashMap<>();
            for (Map.Entry<String, SumAcc> e : entries) {
                SumAcc a = e.getValue();
                errByYmd.computeIfAbsent(a.anyYmd, k -> new LinkedHashSet<>()).add((int) a.error);
                errByYmd.computeIfAbsent(a.anyYmd, k -> new LinkedHashSet<>()).add(a.messageHash);
                svcByYmd.computeIfAbsent(a.anyYmd, k -> new LinkedHashSet<>()).add(a.serviceHash);
            }
            errByYmd.forEach((ymd, hs) -> dict.prefetch(TextTypes.ERROR, ymd, hs));
            svcByYmd.forEach((ymd, hs) -> dict.prefetch(TextTypes.SERVICE, ymd, hs));
        }

        List<SummaryRowDto> rows = null;
        List<ErrorSummaryRowDto> errorRows = null;
        List<AlertSummaryRowDto> alertRows = null;
        switch (category) {
            case "service", "sql", "apiCall" -> {
                rows = new ArrayList<>(entries.size());
                for (Map.Entry<String, SumAcc> e : entries) {
                    SumAcc a = e.getValue();
                    int hash = Integer.parseInt(e.getKey());
                    String raw = dict.text(textType, a.anyYmd, hash);
                    String name = Truncate.text(raw != null ? raw : "#" + hash, Limits.SQL_TEXT_MAX_CHARS);
                    double avg = a.count == 0 ? 0 : (double) a.elapsed / a.count;
                    rows.add(new SummaryRowDto(name, a.count, a.error, a.elapsed, Math.round(avg * 10) / 10.0));
                }
            }
            case "ip" -> {
                rows = new ArrayList<>(entries.size());
                for (Map.Entry<String, SumAcc> e : entries) {
                    // Summary stores client IPs int-encoded; IPUtil renders the dotted form.
                    String ip = scouter.util.IPUtil.toString(Integer.parseInt(e.getKey()));
                    rows.add(new SummaryRowDto(ip, e.getValue().count, null, null, null));
                }
            }
            case "userAgent" -> {
                rows = new ArrayList<>(entries.size());
                for (Map.Entry<String, SumAcc> e : entries) {
                    SumAcc a = e.getValue();
                    int hash = Integer.parseInt(e.getKey());
                    String ua = dict.text(textType, a.anyYmd, hash);
                    rows.add(new SummaryRowDto(ua != null ? ua : "#" + hash, a.count, null, null, null));
                }
            }
            case "error" -> {
                errorRows = new ArrayList<>(entries.size());
                for (Map.Entry<String, SumAcc> e : entries) {
                    SumAcc a = e.getValue();
                    String err = dict.error(a.anyYmd, (int) a.error);
                    String svc = dict.service(a.anyYmd, a.serviceHash);
                    String msg = dict.error(a.anyYmd, a.messageHash);
                    errorRows.add(new ErrorSummaryRowDto(
                            Truncate.text(err != null ? err : "#" + a.error, Limits.ERROR_TEXT_MAX_CHARS),
                            svc != null ? svc : "#" + a.serviceHash,
                            Truncate.text(msg != null ? msg : "#" + a.messageHash, Limits.ERROR_TEXT_MAX_CHARS),
                            a.count,
                            a.sampleTxid != 0 ? Hexa32.toString32(a.sampleTxid) : null));
                }
            }
            case "alert" -> {
                alertRows = new ArrayList<>(entries.size());
                for (Map.Entry<String, SumAcc> e : entries) {
                    SumAcc a = e.getValue();
                    alertRows.add(new AlertSummaryRowDto(
                            a.title != null ? a.title : "#" + e.getKey(),
                            AlertLevel.getName((byte) a.level), a.count));
                }
            }
            default -> {
            }
        }
        return new SummaryResult(category, entries.size(), truncated, rows, errorRows, alertRows, timedOut);
    }

    private static String textOrNull(MapPack mp, String key) {
        String v = mp.getText(key);
        return v == null || v.isEmpty() ? null : v;
    }

    private static Long longOrNull(MapPack mp, String key) {
        return mp.containsKey(key) ? mp.getLong(key) : null;
    }

    private static String lvStr(ListValue lv, int i) {
        return lv != null && i < lv.size() ? lv.getString(i) : null;
    }

    private static long lvLong(ListValue lv, int i) {
        return lv != null && i < lv.size() ? lv.getLong(i) : 0L;
    }

    // process()/getSingle() may wrap the reader's EOFException in a RuntimeException, so inspect the cause chain.
    // EOF is the normal "not found" signal when the collector has no such data (not an error).
    private static boolean isEof(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.io.EOFException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    // A socket read timeout during streaming means the collector accepted the request but is too slow
    // to produce (more) data for THIS instance/window - on production the per-request time-to-first-byte
    // of a wide XLog scan can exceed the read timeout. In a fan-out this must abandon only that pass and
    // yield a partial result, never fail the whole call (distinct from a dead-socket EOF or a real error).
    private static boolean isReadTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.net.SocketTimeoutException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    // objName is cosmetic (decoded object names); cache briefly to avoid an OBJECT_LIST round-trip per call.
    private static final long OBJNAME_TTL_MS = 30_000L;
    private volatile Map<Integer, String> objNameCache;
    private volatile long objNameCacheAt;
    // Counter definitions are effectively static at runtime; cache the engine for the session.
    private volatile CounterEngine counterEngineCache;

    /**
     * objName map for the rows being rendered. Starts from the cached real-time map; when a row's
     * objHash is missing there (a deploy-replaced pod), widens it with the daily object DB for the
     * days the rows span — otherwise dead instances would render as opaque "#hash" names.
     */
    private Map<Integer, String> objNameMapFor(List<XLogPack> packs, Set<Long> ymds) {
        Map<Integer, String> base = buildObjNameMap();
        boolean allKnown = true;
        for (XLogPack xp : packs) {
            if (!base.containsKey(xp.objHash)) {
                allKnown = false;
                break;
            }
        }
        if (allKnown) {
            return base;
        }
        Map<Integer, String> widened = new HashMap<>(base);
        for (Long ymd : ymds) {
            for (SObjectDto o : listObjectsByDateImpl(String.valueOf(ymd))) {
                widened.putIfAbsent(o.objHash(), o.objName());
            }
        }
        return widened;
    }

    private Map<Integer, String> buildObjNameMap() {
        Map<Integer, String> cached = objNameCache;
        if (cached != null && System.currentTimeMillis() - objNameCacheAt < OBJNAME_TTL_MS) {
            return cached;
        }
        Map<Integer, String> map = new HashMap<>();
        for (SObjectDto o : listObjectsImpl()) {
            map.put(o.objHash(), o.objName());
        }
        objNameCache = map;
        objNameCacheAt = System.currentTimeMillis();
        return map;
    }

    private CounterEngine loadCounterEngine() {
        CounterEngine cached = counterEngineCache;
        if (cached != null) {
            return cached;
        }
        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            Pack p = tcp.getSingle(RequestCmd.GET_XML_COUNTER, new MapPack());
            if (!(p instanceof MapPack)) {
                log.warn("GET_XML_COUNTER returned no MapPack, objType counters unavailable");
                return null;
            }
            MapPack mp = (MapPack) p;
            CounterEngine engine = new CounterEngine();
            byte[] defaultXml = blob(mp.get("default"));
            if (defaultXml != null && defaultXml.length > 0) {
                engine.parse(defaultXml);
            }
            byte[] customXml = blob(mp.get("custom"));
            if (customXml != null && customXml.length > 0) {
                engine.parse(customXml);
            }
            counterEngineCache = engine;
            return engine;
        } finally {
            TcpProxy.close(tcp);
        }
    }

    private static byte[] blob(Value v) {
        if (v instanceof BlobValue) {
            return ((BlobValue) v).value;
        }
        return null;
    }

    @Override
    public void close() {
        // Connections are managed by the pool; nothing extra to clean up at this stage.
    }
}
