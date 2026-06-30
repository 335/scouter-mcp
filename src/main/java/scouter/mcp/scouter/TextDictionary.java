package scouter.mcp.scouter;

import lombok.extern.slf4j.Slf4j;
import scouter.lang.TextTypes;
import scouter.lang.constants.ParamConstant;
import scouter.lang.pack.MapPack;
import scouter.lang.pack.Pack;
import scouter.lang.pack.TextPack;
import scouter.lang.value.ListValue;
import scouter.mcp.client.Server;
import scouter.mcp.client.TcpProxy;
import scouter.net.RequestCmd;

import java.util.HashMap;
import java.util.Map;

/**
 * 해시 -> 텍스트 해석기의 실 구현.
 * 서비스/에러 텍스트는 컬렉터에 GET_TEXT_PACK(date + type + hash 리스트)으로 질의해 TextPack으로 받아 해석한다.
 * objName 은 listObjects() 결과로 만든 objHash -> objName 맵으로 해석한다.
 * 해석 결과는 (type,yyyymmdd,hash) 키로 캐시해 중복 왕복을 피한다. 해석 실패 시 null 을 반환한다(절대 임의 생성 금지).
 *
 * GET_TEXT_PACK 프로토콜 (webapp DictionaryConsumer 이식):
 *   MapPack { ParamConstant.DATE="date":yyyymmdd, ParamConstant.TEXT_TYPE="type":TextTypes.*, ListValue("hash"): [hash...] }
 *   응답: TextPack { xtype, hash, text } 다건
 */
@Slf4j
public final class TextDictionary implements PackMapper.TextResolver {

    private final Server server;
    private final Map<Integer, String> objNameByHash;
    private final Map<String, String> cache = new HashMap<>();

    public TextDictionary(Server server, Map<Integer, String> objNameByHash) {
        this.server = server;
        this.objNameByHash = objNameByHash != null ? objNameByHash : new HashMap<>();
    }

    @Override
    public String service(long yyyymmdd, int hash) {
        return resolve(TextTypes.SERVICE, yyyymmdd, hash);
    }

    @Override
    public String error(long yyyymmdd, int hash) {
        return resolve(TextTypes.ERROR, yyyymmdd, hash);
    }

    @Override
    public String objName(int objHash) {
        return objNameByHash.get(objHash);
    }

    @Override
    public String sql(long yyyymmdd, int hash) {
        return resolve(TextTypes.SQL, yyyymmdd, hash);
    }

    private String resolve(String type, long yyyymmdd, int hash) {
        if (hash == 0) {
            return null;
        }
        String key = type + ":" + yyyymmdd + ":" + hash;
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        String text = loadText(type, String.valueOf(yyyymmdd), hash);
        cache.put(key, text); // null 도 캐시(재질의 방지)
        return text;
    }

    private String loadText(String type, String yyyymmdd, int hash) {
        MapPack param = new MapPack();
        param.put(ParamConstant.DATE, yyyymmdd);
        param.put(ParamConstant.TEXT_TYPE, type);
        ListValue dictKeys = param.newList(ParamConstant.TEXT_DICTKEY);
        dictKeys.add((long) hash);

        final String[] holder = new String[1];
        TcpProxy tcp = TcpProxy.getTcpProxy(server);
        try {
            tcp.process(RequestCmd.GET_TEXT_PACK, param, in -> {
                Pack p = in.readPack();
                if (p instanceof TextPack tp && tp.hash == hash) {
                    holder[0] = tp.text;
                }
            });
        } catch (Exception e) {
            if (isEof(e)) {
                // 해당 날짜에 텍스트가 없는 해시는 컬렉터가 빈 응답으로 끝맺어 EOF 로 나타난다.
                // 정상적인 미발견 신호이므로 debug 로만 남기고 null 을 반환한다(상위에서 #hash 로 표기).
                log.debug("text not found: type={}, yyyymmdd={}, hash={}", type, yyyymmdd, hash);
            } else {
                log.warn("text decode failed: type={}, yyyymmdd={}, hash={}, cause={}",
                        type, yyyymmdd, hash, String.valueOf(e.getMessage()));
            }
            return null;
        } finally {
            TcpProxy.close(tcp);
        }
        return holder[0];
    }

    // process() 가 reader 의 EOFException 을 RuntimeException 으로 감쌀 수 있어 원인 체인을 확인한다.
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
}
