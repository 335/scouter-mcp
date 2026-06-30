package scouter.mcp.scouter;

import lombok.extern.slf4j.Slf4j;
import scouter.lang.Counter;
import scouter.lang.ObjectType;
import scouter.lang.constants.ParamConstant;
import scouter.lang.counters.CounterEngine;
import scouter.lang.pack.MapPack;
import scouter.lang.pack.ObjectPack;
import scouter.lang.pack.Pack;
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
import scouter.mcp.scouter.dto.CounterMetaDto;
import scouter.mcp.scouter.dto.CounterSeriesDto;
import scouter.mcp.scouter.dto.SObjectDto;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public final class TcpScouterClient implements ScouterClient {

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
