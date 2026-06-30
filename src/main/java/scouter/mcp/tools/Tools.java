package scouter.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import scouter.mcp.i18n.Messages;
import scouter.mcp.policy.Limits;
import scouter.mcp.scouter.PackMapper;
import scouter.mcp.scouter.ScouterClient;
import scouter.mcp.scouter.dto.CounterMetaDto;
import scouter.mcp.scouter.dto.CounterSeriesDto;
import scouter.mcp.scouter.dto.SObjectDto;
import scouter.mcp.scouter.dto.SearchXlogParams;
import scouter.mcp.scouter.dto.XLogDetailDto;
import scouter.mcp.scouter.dto.XLogRowDto;
import scouter.mcp.scouter.dto.XlogSearchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class Tools {

    // Omit null fields to save tokens (e.g. error=null on the vast majority of XLog rows).
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

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

    public static String renderGetCounter(Locale locale, ScouterClient client, List<Integer> objHashes,
                                          String counter, long fromMillis, long toMillis) {
        List<CounterSeriesDto> series = client.getCounter(objHashes, counter, fromMillis, toMillis);
        // Downsample each series to bound tokens; high-resolution counters can have tens of thousands of points.
        // Summary stats (count/min/max/avg) are computed from the FULL series before downsampling.
        List<Map<String, Object>> rendered = new ArrayList<>(series.size());
        for (CounterSeriesDto s : series) {
            PackMapper.SeriesStats st = PackMapper.stats(s.points());
            List<PackMapper.Point> ds = PackMapper.downsample(s.points(), Limits.COUNTER_MAX_POINTS);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("objHash", s.objHash());
            m.put("count", st.count());
            m.put("min", st.min());
            m.put("max", st.max());
            m.put("avg", st.avg());
            if (ds != null && ds.size() < st.count()) {
                m.put("downsampledTo", ds.size());
            }
            m.put("points", ds);
            rendered.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counter", counter);
        result.put("count", series.size());
        result.put("series", rendered);
        if (series.isEmpty()) {
            result.put("hint", Messages.get(locale, "hint.counter_empty"));
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String renderSearchXlog(Locale locale, ScouterClient client, SearchXlogParams params) {
        XlogSearchResult res = client.searchXlog(params);
        List<XLogRowDto> rows = res.rows();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", rows.size());
        result.put("truncated", res.truncated());
        result.put("rows", rows);
        if (res.scanCapReached()) {
            // Scan cap reached: results may be partial, so strongly steer toward narrowing the filter (saves tokens/resources).
            result.put("hint", Messages.get(locale, "hint.search_scan_cap", res.examined()));
        } else if (res.truncated()) {
            result.put("hint", Messages.get(locale, "hint.search_limit"));
        } else if (rows.isEmpty()) {
            result.put("hint", Messages.get(locale, "hint.search_empty"));
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

    public static String renderXlogByGxid(Locale locale, ScouterClient client, long gxid, String yyyymmdd) {
        List<XLogRowDto> rows = client.getXlogByGxid(gxid, yyyymmdd);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", rows.size());
        result.put("rows", rows);
        if (rows.isEmpty()) {
            result.put("hint", Messages.get(locale, "hint.gxid_empty"));
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String renderListCounters(Locale locale, ScouterClient client, String objType) {
        List<CounterMetaDto> counters = client.listCounters(objType);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objType", objType);
        result.put("counters", counters);
        if (counters.isEmpty()) {
            result.put("hint", Messages.get(locale, "hint.list_counters_empty"));
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
