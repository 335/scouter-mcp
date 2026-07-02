package scouter.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import scouter.mcp.i18n.Messages;
import scouter.mcp.policy.Limits;
import scouter.mcp.scouter.PackMapper;
import scouter.mcp.scouter.ScouterClient;
import scouter.mcp.scouter.dto.ActiveServiceDto;
import scouter.mcp.scouter.dto.AlertDto;
import scouter.mcp.scouter.dto.CounterMetaDto;
import scouter.mcp.scouter.dto.CounterSeriesDto;
import scouter.mcp.scouter.dto.SObjectDto;
import scouter.mcp.scouter.dto.SearchXlogParams;
import scouter.mcp.scouter.dto.XLogDetailDto;
import scouter.mcp.scouter.dto.XLogRowDto;
import scouter.mcp.scouter.dto.XlogSearchResult;
import scouter.mcp.scouter.dto.XlogSummaryResult;

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
        // nameLike matches case-insensitively: users quote app-name fragments, not exact pod names.
        String needle = nameLike == null ? null : nameLike.toLowerCase();
        List<SObjectDto> objects = client.listObjects().stream()
                .filter(o -> objType == null || objType.equalsIgnoreCase(o.objType()))
                .filter(o -> needle == null || (o.objName() != null && o.objName().toLowerCase().contains(needle)))
                .collect(Collectors.toList());

        // Group by objType (largest first, alive pods first within a group): a flat 50+ object dump
        // makes the model relay a flat list to the user, while grouped output begets a grouped answer.
        Map<String, List<SObjectDto>> byType = new LinkedHashMap<>();
        for (SObjectDto o : objects) {
            byType.computeIfAbsent(String.valueOf(o.objType()), k -> new ArrayList<>()).add(o);
        }
        List<Map<String, Object>> types = new ArrayList<>(byType.size());
        byType.forEach((type, list) -> {
            list.sort((a, b) -> Boolean.compare(b.alive(), a.alive()));
            long alive = list.stream().filter(SObjectDto::alive).count();
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("objType", type);
            group.put("total", list.size());
            group.put("alive", (int) alive);
            group.put("objects", list);
            types.add(group);
        });
        types.sort((a, b) -> Integer.compare((int) b.get("total"), (int) a.get("total")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", objects.size());
        result.put("types", types);
        try {
            return MAPPER.writeValueAsString(result);
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
        } else if (rows.isEmpty() && res.serviceLooksLikeApp()) {
            // The service token matched an application objName: steer to objNameLike, not to a wider window.
            result.put("hint", Messages.get(locale, "hint.search_service_is_app", params.service().trim()));
        } else if (rows.isEmpty() && res.serviceCandidates() != null && !res.serviceCandidates().isEmpty()) {
            // Real service names similar to the sloppy query were discovered in the same window.
            result.put("serviceCandidates", res.serviceCandidates());
            result.put("hint", Messages.get(locale, "hint.search_service_candidates"));
        } else if (rows.isEmpty()) {
            result.put("hint", Messages.get(locale, "hint.search_empty"));
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String renderServiceSummary(Locale locale, ScouterClient client, SearchXlogParams params) {
        XlogSummaryResult res = client.getServiceSummary(params);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serviceCount", res.services().size());
        result.put("totalCount", res.totalCount());
        result.put("scanCapReached", res.scanCapReached());
        result.put("examined", res.examined());
        result.put("services", res.services());
        if (res.scanCapReached()) {
            result.put("hint", Messages.get(locale, "hint.summary_scan_cap", res.examined()));
        } else if (res.services().isEmpty() && res.serviceLooksLikeApp()) {
            result.put("hint", Messages.get(locale, "hint.search_service_is_app", params.service().trim()));
        } else if (res.services().isEmpty() && res.serviceCandidates() != null && !res.serviceCandidates().isEmpty()) {
            result.put("serviceCandidates", res.serviceCandidates());
            result.put("hint", Messages.get(locale, "hint.search_service_candidates"));
        } else if (res.services().isEmpty()) {
            result.put("hint", Messages.get(locale, "hint.search_empty"));
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String renderXlogDetail(ScouterClient client, long txid, String yyyymmdd,
                                          boolean includeBindParams) {
        XLogDetailDto detail = client.getXlogDetail(txid, yyyymmdd, includeBindParams);
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

    public static String renderListAlerts(Locale locale, ScouterClient client,
                                          long fromMillis, long toMillis, String level, String object,
                                          String key, int limit) {
        List<AlertDto> alerts = client.getAlerts(fromMillis, toMillis, level, object, key, limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", alerts.size());
        result.put("alerts", alerts);
        if (alerts.isEmpty()) {
            result.put("hint", Messages.get(locale, "hint.alerts_empty"));
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String renderActiveServices(Locale locale, ScouterClient client, String objType, Long objHash,
                                              String objNameLike) {
        List<ActiveServiceDto> active = client.getActiveServices(objType, objHash, objNameLike);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", active.size());
        result.put("services", active);
        if (active.isEmpty()) {
            result.put("hint", Messages.get(locale, "hint.active_empty"));
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
