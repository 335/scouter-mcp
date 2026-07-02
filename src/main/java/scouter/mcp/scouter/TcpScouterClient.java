package scouter.mcp.scouter;

import lombok.extern.slf4j.Slf4j;
import scouter.lang.Counter;
import scouter.lang.ObjectType;
import scouter.lang.TextTypes;
import scouter.lang.constants.ParamConstant;
import scouter.lang.counters.CounterEngine;
import scouter.lang.pack.MapPack;
import scouter.lang.pack.ObjectPack;
import scouter.lang.pack.Pack;
import scouter.lang.pack.XLogPack;
import scouter.lang.pack.XLogProfilePack;
import scouter.lang.step.Step;
import scouter.lang.value.BlobValue;
import scouter.lang.value.ListValue;
import scouter.lang.value.Value;
import scouter.net.RequestCmd;
import scouter.mcp.client.LoginMgr;
import scouter.mcp.client.LoginRequest;
import scouter.mcp.client.Server;
import scouter.mcp.client.ServerRegistry;
import scouter.mcp.client.TcpProxy;
import scouter.mcp.config.Config;
import scouter.mcp.error.McpError;
import scouter.mcp.i18n.Messages;
import scouter.mcp.scouter.dto.CounterMetaDto;
import scouter.mcp.scouter.dto.CounterSeriesDto;
import scouter.mcp.scouter.dto.SObjectDto;
import scouter.mcp.scouter.dto.SearchXlogParams;
import scouter.mcp.scouter.dto.XLogDetailDto;
import scouter.mcp.scouter.dto.XLogRowDto;
import scouter.mcp.scouter.dto.XlogSearchResult;
import scouter.mcp.policy.Limits;

import java.time.Instant;
import java.time.ZoneId;
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

    @Override
    public List<SObjectDto> listObjects() {
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
        // Counter data is stored in per-day files, and COUNTER_PAST_TIME_ALL is single-day. Like the upstream
        // CounterConsumer, split [from,to] into per-calendar-day segments (in the configured zone), query each
        // day, and merge points by objHash. Otherwise a window straddling midnight loses data on one side.
        Map<Integer, List<PackMapper.Point>> mergedByObj = new LinkedHashMap<>();
        ZoneId zone = config.zone();
        long cursor = fromMillis;
        while (cursor < toMillis) {
            long dayEndExclusive = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
                    .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long segFrom = cursor;
            long segTo = Math.min(dayEndExclusive - 1, toMillis);
            for (CounterSeriesDto day : queryCounterDay(objHashes, counter, segFrom, segTo)) {
                mergedByObj.computeIfAbsent(day.objHash(), k -> new ArrayList<>()).addAll(day.points());
            }
            cursor = dayEndExclusive;
        }
        List<CounterSeriesDto> out = new ArrayList<>(mergedByObj.size());
        for (Map.Entry<Integer, List<PackMapper.Point>> e : mergedByObj.entrySet()) {
            out.add(new CounterSeriesDto(e.getKey(), counter, e.getValue()));
        }
        return out;
    }

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
        // Ported from webapp XLogConsumer.searchXLogList: SEARCH_XLOG_LIST.
        // Policy (Limits): to defend against the production firehose, validate the window/filters and,
        // during streaming, cut the socket once the limit or scan cap is reached so that the collector
        // scan/transfer and the MCP heap stop together.
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
                || (params.service() != null && !params.service().isBlank());
        if (!serverFilter && windowMs > Limits.UNFILTERED_MAX_WINDOW_MS) {
            throw McpError.of(McpError.Code.INVALID_INPUT,
                            Messages.get(config.locale(), "error.unfiltered_window",
                                    Limits.UNFILTERED_MAX_WINDOW_MS / 60000))
                    .withHint("windowSec", String.valueOf(windowMs / 1000))
                    .withHint("maxUnfilteredSec", String.valueOf(Limits.UNFILTERED_MAX_WINDOW_MS / 1000));
        }

        MapPack param = new MapPack();
        param.put(ParamConstant.DATE, scouter.mcp.time.TimeRange.yyyymmdd(params.fromMillis(), config.zone()));
        param.put(ParamConstant.XLOG_START_TIME, params.fromMillis());
        param.put(ParamConstant.XLOG_END_TIME, params.toMillis());
        if (params.objHash() != null && params.objHash() != 0L) {
            param.put(ParamConstant.OBJ_HASH, params.objHash());
        }
        if (params.service() != null && !params.service().isBlank()) {
            // The collector filters service with StrMatch (scouter.util.StrMatch). A plain string without '*'
            // is an exact match, so a short token would never match. If the caller did not use '*', wrap it as
            // a substring match (*term*) so it matches server-side without knowing the full service name.
            // If '*' is already present, honor the pattern as-is.
            String svc = params.service().trim();
            String pattern = svc.indexOf('*') >= 0 ? svc : "*" + svc + "*";
            param.put(ParamConstant.XLOG_SERVICE, pattern);
        }

        final int limit = params.limit() <= 0
                ? Limits.SEARCH_DEFAULT_LIMIT
                : Math.min(params.limit(), Limits.SEARCH_MAX_LIMIT);
        final Integer minElapsed = params.minElapsedMs();
        final boolean onlyError = params.onlyError();

        // Streaming: collect matches up to the limit, and stop once the number of examined Packs hits the scan cap.
        // Text-dictionary round-trips are performed after the socket is closed (kept rows <= limit).
        final List<XLogPack> kept = new ArrayList<>();
        final int[] examined = {0};
        final boolean[] stoppedByLimit = {false};

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

        boolean scanCapReached = examined[0] >= Limits.SEARCH_SCAN_CAP && !stoppedByLimit[0];
        boolean truncated = stoppedByLimit[0] || scanCapReached;

        return new XlogSearchResult(mapRows(kept), truncated, scanCapReached, examined[0]);
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

        return PackMapper.toDetail(summary, steps, ymd, includeBindParams, dict);
    }

    @Override
    public List<XLogRowDto> getXlogByGxid(long gxid, String yyyymmdd) {
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
        for (SObjectDto o : listObjects()) {
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
