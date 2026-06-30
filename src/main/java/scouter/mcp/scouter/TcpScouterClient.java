package scouter.mcp.scouter;

import lombok.extern.slf4j.Slf4j;
import scouter.lang.Counter;
import scouter.lang.ObjectType;
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
import scouter.mcp.masking.Masker;
import scouter.mcp.client.LoginMgr;
import scouter.mcp.client.LoginRequest;
import scouter.mcp.client.Server;
import scouter.mcp.client.ServerRegistry;
import scouter.mcp.client.TcpProxy;
import scouter.mcp.config.Config;
import scouter.mcp.error.McpError;
import scouter.mcp.scouter.dto.CounterMetaDto;
import scouter.mcp.scouter.dto.CounterSeriesDto;
import scouter.mcp.scouter.dto.SObjectDto;
import scouter.mcp.scouter.dto.SearchXlogParams;
import scouter.mcp.scouter.dto.XLogDetailDto;
import scouter.mcp.scouter.dto.XLogRowDto;
import scouter.mcp.scouter.dto.XlogSearchResult;
import scouter.mcp.policy.Limits;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public final class TcpScouterClient implements ScouterClient {

    private static final int MAX_PROFILE_BLOCK = 10;

    private final Config config;
    private final Masker masker = new Masker();
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
        // webapp CounterConsumer.retrieveCounterByObjHashes 충실 이식: COUNTER_PAST_TIME_ALL
        MapPack param = new MapPack();
        ListValue objHashLv = param.newList(ParamConstant.OBJ_HASH);
        if (objHashes != null) {
            for (Integer objHash : objHashes) {
                objHashLv.add((long) objHash);
            }
        }
        param.put(ParamConstant.COUNTER, counter);
        param.put(ParamConstant.STIME, fromMillis);
        param.put(ParamConstant.ETIME, toMillis);

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
        // 서버에서 GET_XML_COUNTER로 카운터 정의 XML(default + custom)을 받아 CounterEngine에 적재한다.
        // 응답: MapPack { "default": BlobValue, ("custom": BlobValue)? }  (ConfigureService.getCounterXml)
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

    /** 스트리밍 조기 중단용 sentinel(정상 흐름 제어, 오류 아님). */
    private static final class StopStreaming extends RuntimeException {
        StopStreaming() {
            super(null, null, false, false);
        }
    }

    @Override
    public XlogSearchResult searchXlog(SearchXlogParams params) {
        // webapp XLogConsumer.searchXLogList 이식: SEARCH_XLOG_LIST.
        // 정책(Limits): 운영 firehose 방어를 위해 기간/필터를 검증하고, 스트리밍 중
        // limit 또는 스캔 상한에 도달하면 소켓을 끊어 컬렉터 스캔/전송과 MCP 힙을 함께 멈춘다.
        long windowMs = params.toMillis() - params.fromMillis();
        if (windowMs <= 0) {
            throw McpError.of(McpError.Code.INVALID_INPUT, "from 은 to 보다 앞서야 합니다");
        }
        if (windowMs > Limits.ABS_MAX_WINDOW_MS) {
            throw McpError.of(McpError.Code.INVALID_INPUT, "조회 기간이 너무 깁니다(최대 24시간)")
                    .withHint("windowSec", String.valueOf(windowMs / 1000))
                    .withHint("maxSec", String.valueOf(Limits.ABS_MAX_WINDOW_MS / 1000));
        }
        boolean serverFilter = (params.objHash() != null && params.objHash() != 0L)
                || (params.service() != null && !params.service().isBlank());
        if (!serverFilter && windowMs > Limits.UNFILTERED_MAX_WINDOW_MS) {
            throw McpError.of(McpError.Code.INVALID_INPUT,
                            "service 또는 objHash 필터 없이는 5분 이내만 조회할 수 있습니다. 필터를 추가하거나 기간을 줄이세요")
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
            // 컬렉터는 service 를 StrMatch 로 필터한다(scouter.util.StrMatch). '*' 없는 평문은 정확일치라
            // 짧은 토큰으로는 매칭되지 않는다. 사용자가 '*' 를 쓰지 않았으면 부분일치(*term*)로 감싸,
            // 풀 서비스명을 몰라도 서버 측에서 매칭되게 한다. 이미 '*' 가 있으면 그대로 존중한다.
            String svc = params.service().trim();
            String pattern = svc.indexOf('*') >= 0 ? svc : "*" + svc + "*";
            param.put(ParamConstant.XLOG_SERVICE, pattern);
        }

        final int limit = params.limit() <= 0
                ? Limits.SEARCH_DEFAULT_LIMIT
                : Math.min(params.limit(), Limits.SEARCH_MAX_LIMIT);
        final Integer minElapsed = params.minElapsedMs();
        final boolean onlyError = params.onlyError();

        // 스트리밍: 매칭분만 limit 까지 모으고, 검사한 Pack 수가 스캔 상한에 닿으면 중단한다.
        // 텍스트 사전 round-trip 은 소켓을 닫은 뒤(보관분 ≤ limit)에 수행한다.
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
            // 의도된 조기 중단: process() 내부 catch 가 이미 소켓을 close 했으므로 아래 finally 의
            // TcpProxy.close 는 isValid()=false 로 realClose 되어 풀 오염이 없다.
        } finally {
            TcpProxy.close(tcp);
        }

        boolean scanCapReached = examined[0] >= Limits.SEARCH_SCAN_CAP && !stoppedByLimit[0];
        boolean truncated = stoppedByLimit[0] || scanCapReached;

        TextDictionary dict = new TextDictionary(server, buildObjNameMap());
        List<XLogRowDto> out = new ArrayList<>(kept.size());
        for (XLogPack xp : kept) {
            out.add(PackMapper.toXLogRow(xp, config.zone(), dict));
        }
        return new XlogSearchResult(out, truncated, scanCapReached, examined[0]);
    }

    @Override
    public XLogDetailDto getXlogDetail(long txid, String yyyymmdd, boolean includeBindParams, boolean maskSensitive) {
        // webapp XLogConsumer.retrieveByTxid + ProfileConsumer.retrieveProfile 이식.
        // 1) XLOG_READ_BY_TXID(getSingle) → XLogPack 요약. 2) TRANX_PROFILE(getSingle) → XLogProfilePack.profile → Step[].
        if (!maskSensitive) {
            // 민감정보 비마스킹 조회는 감사 로그를 한 줄 남긴다(바인드 값은 절대 로깅하지 않는다).
            log.warn("code=AUDIT action=unmask txid={}", txid);
        }

        TextDictionary dict = new TextDictionary(server, buildObjNameMap());
        long ymd = Long.parseLong(yyyymmdd);

        XLogRowDto summary = null;
        Step[] steps = null;

        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            // 요약 조회: 해당 txid 가 보관돼 있지 않으면 컬렉터가 빈 응답으로 끝맺어 EOF 로 나타난다.
            // 이는 "없음" 신호이므로 summary=null 로 두고 진행한다(EOF 가 아닌 오류만 표면화).
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

            // 프로파일 조회: trivial/미보관 트랜잭션은 프로파일이 없어 EOF 로 나타난다.
            // steps=null 로 두면 빈 상세가 반환된다(EOF 가 아닌 오류만 표면화).
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

        return PackMapper.toDetail(summary, steps, ymd, includeBindParams, maskSensitive, masker, dict);
    }

    @Override
    public List<XLogRowDto> getXlogByGxid(long gxid, String yyyymmdd) {
        // webapp XLogConsumer.retrieveXLogPacksByGxid 이식: XLOG_READ_BY_GXID(process) → List<XLogPack>.
        MapPack param = new MapPack();
        param.put(ParamConstant.DATE, yyyymmdd);
        param.put(ParamConstant.XLOG_GXID, gxid);

        TextDictionary dict = new TextDictionary(server, buildObjNameMap());

        List<XLogRowDto> out = new ArrayList<>();
        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            List<Pack> resp = tcp.process(RequestCmd.XLOG_READ_BY_GXID, param);
            if (resp == null) {
                return out;
            }
            for (Pack p : resp) {
                if (p instanceof XLogPack xp) {
                    out.add(PackMapper.toXLogRow(xp, config.zone(), dict));
                }
            }
            return out;
        } finally {
            TcpProxy.close(tcp);
        }
    }

    // process()/getSingle() 가 reader 의 EOFException 을 RuntimeException 으로 감쌀 수 있어 원인 체인을 확인한다.
    // EOF 는 컬렉터에 해당 데이터가 없을 때의 정상적인 "없음" 신호다(오류가 아님).
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

    private Map<Integer, String> buildObjNameMap() {
        Map<Integer, String> map = new HashMap<>();
        for (SObjectDto o : listObjects()) {
            map.put(o.objHash(), o.objName());
        }
        return map;
    }

    private CounterEngine loadCounterEngine() {
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
        // 커넥션은 풀이 관리한다. 현재 단계에서는 추가 정리할 것이 없다.
    }
}
