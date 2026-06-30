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
 * Concrete implementation of the hash -> text resolver.
 * Service/error text is resolved by querying the collector with GET_TEXT_PACK (date + type + hash list) and reading the TextPack response.
 * objName is resolved via the objHash -> objName map built from listObjects() results.
 * Resolution results are cached by (type,yyyymmdd,hash) key to avoid redundant round trips. Returns null on resolution failure (never fabricates).
 *
 * GET_TEXT_PACK protocol (ported from webapp DictionaryConsumer):
 *   MapPack { ParamConstant.DATE="date":yyyymmdd, ParamConstant.TEXT_TYPE="type":TextTypes.*, ListValue("hash"): [hash...] }
 *   response: multiple TextPack { xtype, hash, text }
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
        cache.put(key, text); // cache null too (prevents re-querying)
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
                // For a hash with no text on the given date, the collector ends with an empty response, surfacing as EOF.
                // This is a normal not-found signal, so we only log at debug level and return null (the caller renders #hash).
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

    // process() may wrap the reader's EOFException in a RuntimeException, so we walk the cause chain.
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
