package scouter.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import scouter.mcp.scouter.ScouterClient;
import scouter.mcp.scouter.dto.CounterMetaDto;
import scouter.mcp.scouter.dto.CounterSeriesDto;
import scouter.mcp.scouter.dto.SObjectDto;
import scouter.mcp.scouter.dto.SearchXlogParams;
import scouter.mcp.scouter.dto.XLogDetailDto;
import scouter.mcp.scouter.dto.XLogRowDto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class Tools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Tools() {
    }

    public static String renderListObjects(ScouterClient client, String objType, String nameLike) {
        List<SObjectDto> objects = client.listObjects().stream()
                .filter(o -> objType == null || objType.equalsIgnoreCase(o.objType()))
                .filter(o -> nameLike == null || o.objName().contains(nameLike))
                .collect(Collectors.toList());
        try {
            return MAPPER.writeValueAsString(Map.of("count", objects.size(), "objects", objects));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String renderGetCounter(ScouterClient client, List<Integer> objHashes, String counter,
                                          long fromMillis, long toMillis) {
        List<CounterSeriesDto> series = client.getCounter(objHashes, counter, fromMillis, toMillis);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counter", counter);
        result.put("count", series.size());
        result.put("series", series);
        if (series.isEmpty()) {
            result.put("hint", "결과가 없다. 조회 구간(from/to)을 넓히거나 counter 이름/objType을 확인하라");
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String renderSearchXlog(ScouterClient client, SearchXlogParams params) {
        List<XLogRowDto> rows = client.searchXlog(params);
        boolean truncated = rows.size() >= params.limit();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", rows.size());
        result.put("truncated", truncated);
        result.put("rows", rows);
        if (rows.isEmpty()) {
            result.put("hint", "결과가 없다. 기간/필터를 넓혀보세요");
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String renderXlogDetail(ScouterClient client, long txid, String yyyymmdd,
                                          boolean includeBindParams, boolean maskSensitive) {
        XLogDetailDto detail = client.getXlogDetail(txid, yyyymmdd, includeBindParams, maskSensitive);
        try {
            return MAPPER.writeValueAsString(detail);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String renderXlogByGxid(ScouterClient client, long gxid, String yyyymmdd) {
        List<XLogRowDto> rows = client.getXlogByGxid(gxid, yyyymmdd);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", rows.size());
        result.put("rows", rows);
        if (rows.isEmpty()) {
            result.put("hint", "결과가 없다. gxid/date를 확인하라");
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String renderListCounters(ScouterClient client, String objType) {
        List<CounterMetaDto> counters = client.listCounters(objType);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objType", objType);
        result.put("counters", counters);
        if (counters.isEmpty()) {
            result.put("hint", "해당 objType의 카운터 정의를 찾지 못했다. objType 이름을 확인하라");
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
