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

    /** Sentinel for early streaming termination (normal flow control, not an error). */
    private static final class StopStreaming extends RuntimeException {
        StopStreaming() {
            super(null, null, false, false);
        }
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

    // Wrap a bare service token as a server-side StrMatch substring pattern (*term*); honor explicit '*'.
    private static String buildServicePattern(String service) {
        if (service == null || service.isBlank()) {
            return null;
        }
        String svc = service.trim();
        return svc.indexOf('*') >= 0 ? svc : "*" + svc + "*";
    }

    /**
     * Turn the caller's object constraint into the concrete objHash list to query.
     * Explicit objHash wins; otherwise objNameLike is fuzzy-resolved to every matching instance
     * (alive first, capped at SEARCH_MAX_OBJ); no constraint yields [null] (single unfiltered pass).
     * An unresolvable objNameLike fails fast as NOT_FOUND with candidate names, so the caller can
     * self-correct in one step instead of scanning nothing.
     */
    private List<Long> resolveTargetHashes(Long objHash, String objNameLike) {
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
        // scan/transfer and the MCP heap stop together.
        validateSearchWindow(params);

        String servicePattern = buildServicePattern(params.service());
        List<Long> targetHashes = resolveTargetHashes(params.objHash(), params.objNameLike());

        final int limit = params.limit() <= 0
                ? Limits.SEARCH_DEFAULT_LIMIT
                : Math.min(params.limit(), Limits.SEARCH_MAX_LIMIT);

        // Streaming state, accumulated across per-day segments and per-instance passes. The collector
        // partitions XLogs by day (DaySplitter) and filters by a single objHash per pass, so a fuzzy
        // target (k8s app -> many pods) fans out over instances; limit and scan cap stay global.
        final List<XLogPack> kept = new ArrayList<>();
        final int[] examined = {0};
        final boolean[] stoppedByLimit = {false};

        outer:
        for (Long hash : targetHashes) {
            for (DaySplitter.Segment seg : DaySplitter.splitByCalendarDay(params.fromMillis(), params.toMillis(), config.zone())) {
                boolean stop = scanXlogSegment(seg, hash, servicePattern, params, limit, kept, examined, stoppedByLimit);
                if (stop) {
                    break outer;
                }
            }
        }

        boolean scanCapReached = examined[0] >= Limits.SEARCH_SCAN_CAP && !stoppedByLimit[0];
        boolean truncated = stoppedByLimit[0] || scanCapReached;

        return new XlogSearchResult(mapRows(kept), truncated, scanCapReached, examined[0]);
    }

    /**
     * Scan a single per-day segment (SEARCH_XLOG_LIST) into the shared streaming state.
     * Returns true when scanning should stop entirely (global limit or scan cap reached), so the
     * caller skips remaining segments. Text-dictionary round-trips happen later (kept rows <= limit).
     */
    private boolean scanXlogSegment(DaySplitter.Segment seg, Long objHash, String servicePattern,
                                    SearchXlogParams params, int limit,
                                    List<XLogPack> kept, int[] examined, boolean[] stoppedByLimit) {
        final Integer minElapsed = params.minElapsedMs();
        final boolean onlyError = params.onlyError();

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

        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            tcp.process(RequestCmd.SEARCH_XLOG_LIST, param, in -> {
                Pack p = in.readPack();
                examined[0]++;
                if (p instanceof XLogPack) {
                    XLogPack xp = (XLogPack) p;
                    boolean pass = (minElapsed == null || xp.elapsed >= minElapsed) && (!onlyError || xp.error != 0);
                    if (pass && kept.size() < limit) {
                        kept.add(xp);
                    }
                }
                if (kept.size() >= limit) {
                    stoppedByLimit[0] = true;
                    throw new StopStreaming();
                }
                if (examined[0] >= Limits.SEARCH_SCAN_CAP) {
                    throw new StopStreaming();
                }
            });
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (!(cause instanceof StopStreaming) && !(e instanceof StopStreaming)) {
                if (cause instanceof McpError) {
                    throw (McpError) cause;
                }
                throw McpError.of(McpError.Code.INTERNAL, String.valueOf(cause.getMessage()));
            }
            // Intended early stop: process()'s internal catch already closed the socket, so the finally's
            // TcpProxy.close sees isValid()=false and performs realClose, avoiding connection-pool poisoning.
        } finally {
            TcpProxy.close(tcp);
        }

        return stoppedByLimit[0] || examined[0] >= Limits.SEARCH_SCAN_CAP;
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

    private XlogSummaryResult getServiceSummaryImpl(SearchXlogParams params) {
        // Same SEARCH_XLOG_LIST stream as search_xlog, but rows are folded into per-service counters
        // instead of retained. This answers "which service got slow/errored" over a wide window while
        // keeping the response to a few dozen lines. Scan cap is higher since no rows are held.
        validateSearchWindow(params);
        String servicePattern = buildServicePattern(params.service());
        List<Long> targetHashes = resolveTargetHashes(params.objHash(), params.objNameLike());
        Integer minElapsed = params.minElapsedMs();
        boolean onlyError = params.onlyError();

        Map<Integer, ServiceAcc> byService = new HashMap<>();
        int[] examined = {0};
        boolean[] capped = {false};

        record Pass(Long hash, DaySplitter.Segment seg) {
        }
        List<Pass> passes = new ArrayList<>();
        for (Long hash : targetHashes) {
            for (DaySplitter.Segment seg : DaySplitter.splitByCalendarDay(params.fromMillis(), params.toMillis(), config.zone())) {
                passes.add(new Pass(hash, seg));
            }
        }
        for (Pass qp : passes) {
            Long objHash = qp.hash();
            DaySplitter.Segment seg = qp.seg();
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

            TcpProxy tcp = TcpProxy.getTcpProxy(server);
            try {
                tcp.process(RequestCmd.SEARCH_XLOG_LIST, param, in -> {
                    Pack p = in.readPack();
                    examined[0]++;
                    if (p instanceof XLogPack xp) {
                        boolean pass = (minElapsed == null || xp.elapsed >= minElapsed) && (!onlyError || xp.error != 0);
                        if (pass) {
                            long ymd = Long.parseLong(scouter.mcp.time.TimeRange.yyyymmdd(xp.endTime, config.zone()));
                            byService.computeIfAbsent(xp.service, k -> new ServiceAcc())
                                    .add(xp.elapsed, xp.error != 0, ymd);
                        }
                    }
                    if (examined[0] >= Limits.SUMMARY_SCAN_CAP) {
                        capped[0] = true;
                        throw new StopStreaming();
                    }
                });
            } catch (RuntimeException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (!(cause instanceof StopStreaming) && !(e instanceof StopStreaming)) {
                    if (cause instanceof McpError) {
                        throw (McpError) cause;
                    }
                    throw McpError.of(McpError.Code.INTERNAL, String.valueOf(cause.getMessage()));
                }
            } finally {
                TcpProxy.close(tcp);
            }
            if (capped[0]) {
                break;
            }
        }

        return buildSummary(byService, examined[0], capped[0]);
    }

    private XlogSummaryResult buildSummary(Map<Integer, ServiceAcc> byService, int examined, boolean capped) {
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
        return new XlogSummaryResult(list, totalCount, capped, examined);
    }

    /**
     * Map XLogPacks to DTOs, batch-prefetching service/error text so the dictionary is hit from cache
     * instead of performing per-row GET_TEXT_PACK round-trips (matters for large result sets).
     */
    private List<XLogRowDto> mapRows(List<XLogPack> packs) {
        TextDictionary dict = new TextDictionary(server, buildObjNameMap());
        Map<Long, Set<Integer>> serviceByYmd = new HashMap<>();
        Map<Long, Set<Integer>> errorByYmd = new HashMap<>();
        for (XLogPack xp : packs) {
            long ymd = Long.parseLong(scouter.mcp.time.TimeRange.yyyymmdd(xp.endTime, config.zone()));
            serviceByYmd.computeIfAbsent(ymd, k -> new LinkedHashSet<>()).add(xp.service);
            if (xp.error != 0) {
                errorByYmd.computeIfAbsent(ymd, k -> new LinkedHashSet<>()).add(xp.error);
            }
        }
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

    // objName is cosmetic (decoded object names); cache briefly to avoid an OBJECT_LIST round-trip per call.
    private static final long OBJNAME_TTL_MS = 30_000L;
    private volatile Map<Integer, String> objNameCache;
    private volatile long objNameCacheAt;
    // Counter definitions are effectively static at runtime; cache the engine for the session.
    private volatile CounterEngine counterEngineCache;

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
