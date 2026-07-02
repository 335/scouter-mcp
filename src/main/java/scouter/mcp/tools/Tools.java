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
import scouter.mcp.scouter.dto.EnvDto;
import scouter.mcp.scouter.dto.SObjectDto;
import scouter.mcp.scouter.dto.SearchXlogParams;
import scouter.mcp.scouter.dto.SummaryResult;
import scouter.mcp.scouter.dto.ThreadDetailDto;
import scouter.mcp.scouter.dto.ThreadListDto;
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
            // Each object carries pre-computed app/instance labels so the model uses them verbatim
            // instead of truncating the long objName from the wrong end (hash kept, identity lost).
            List<Map<String, Object>> entries = new ArrayList<>(list.size());
            for (SObjectDto o : list) {
                scouter.mcp.scouter.AppLabel.Label label = scouter.mcp.scouter.AppLabel.of(o.objName());
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("objHash", o.objHash());
                e.put("objName", o.objName());
                e.put("app", label.app());
                if (label.instance() != null) {
                    e.put("instance", label.instance());
                }
                e.put("address", o.address());
                e.put("alive", o.alive());
                entries.add(e);
            }
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("objType", type);
            group.put("total", list.size());
            group.put("alive", (int) alive);
            group.put("objects", entries);
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
        return renderSeriesResult(locale, client, counter, series, null, "hint.counter_empty");
    }

    public static String renderCounterStat(Locale locale, ScouterClient client, List<Integer> objHashes,
                                           String counter, String sDateYmd, String eDateYmd) {
        List<CounterSeriesDto> series = client.getCounterStat(objHashes, counter, sDateYmd, eDateYmd);
        // Fixed 5-min resolution is a property of the collector's daily-stat DB - surface it so the
        // model does not mistake the coarse series for missing data.
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("resolution", "5m");
        extra.put("sDate", sDateYmd);
        extra.put("eDate", eDateYmd);
        return renderSeriesResult(locale, client, counter, series, extra, "hint.counter_stat_empty");
    }

    private static String renderSeriesResult(Locale locale, ScouterClient client, String counter,
                                             List<CounterSeriesDto> series, Map<String, Object> extra,
                                             String emptyHintKey) {
        // objHash alone is unusable as a label: resolve names once so each series carries objName plus
        // the short app/instance labels (models otherwise truncate long objNames from the wrong end).
        Map<Integer, String> names = new LinkedHashMap<>();
        try {
            client.listObjects().forEach(o -> names.put(o.objHash(), o.objName()));
        } catch (RuntimeException e) {
            // Name resolution is cosmetic; series remain usable by objHash.
        }
        // Downsample each series to bound tokens; high-resolution counters can have tens of thousands of points.
        // Summary stats (count/min/max/avg) are computed from the FULL series before downsampling.
        List<Map<String, Object>> rendered = new ArrayList<>(series.size());
        for (CounterSeriesDto s : series) {
            PackMapper.SeriesStats st = PackMapper.stats(s.points());
            List<PackMapper.Point> ds = PackMapper.downsample(s.points(), Limits.COUNTER_MAX_POINTS);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("objHash", s.objHash());
            String objName = names.get(s.objHash());
            if (objName != null) {
                scouter.mcp.scouter.AppLabel.Label label = scouter.mcp.scouter.AppLabel.of(objName);
                m.put("objName", objName);
                m.put("app", label.app());
                if (label.instance() != null) {
                    m.put("instance", label.instance());
                }
            }
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
        if (extra != null) {
            result.putAll(extra);
        }
        result.put("count", series.size());
        result.put("series", rendered);
        if (series.isEmpty()) {
            result.put("hint", Messages.get(locale, emptyHintKey));
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
        // Client-side filters (minElapsedMs/onlyError) drop rows only after the collector streamed them.
        // A sub-1% keep rate over a big scan means the query shape itself is wasteful — say so explicitly
        // instead of letting the model retry the same shape with a wider window.
        boolean clientFilter = params.minElapsedMs() != null || params.onlyError();
        boolean lowSelectivity = clientFilter && res.examined() >= 1000
                && (long) rows.size() * 100 < res.examined();
        if (lowSelectivity) {
            result.put("hint", Messages.get(locale, "hint.low_selectivity", rows.size(), res.examined()));
        } else if (res.scanCapReached()) {
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

    public static String renderListThreads(Locale locale, ScouterClient client, String objNameLike, Long objHash) {
        List<ThreadListDto> lists = client.listThreads(objNameLike, objHash);
        List<Map<String, Object>> instances = new ArrayList<>(lists.size());
        for (ThreadListDto t : lists) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("objHash", t.objHash());
            m.put("objName", t.objName());
            scouter.mcp.scouter.AppLabel.Label label = scouter.mcp.scouter.AppLabel.of(t.objName());
            m.put("app", label.app());
            if (label.instance() != null) {
                m.put("instance", label.instance());
            }
            m.put("totalThreads", t.totalThreads());
            m.put("stateCounts", t.stateCounts());
            if (t.truncated()) {
                m.put("truncated", true);
            }
            m.put("threads", t.threads());
            instances.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", lists.size());
        result.put("instances", instances);
        if (lists.isEmpty()) {
            result.put("hint", Messages.get(locale, "hint.threads_empty"));
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String renderThreadDetail(Locale locale, ScouterClient client, String objNameLike,
                                            Long objHash, Long threadId, long txid, boolean includeBindParams) {
        ThreadDetailDto d = client.getThreadDetail(objNameLike, objHash, threadId, txid, includeBindParams);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("detail", d);
        if ("end".equalsIgnoreCase(String.valueOf(d.state()))
                || (d.threadName() != null && d.threadName().startsWith("[No Thread]"))) {
            // The txid finished between the listing call and this drill-down: it is a live snapshot only.
            result.put("hint", Messages.get(locale, "hint.thread_detail_stale"));
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Env values routinely carry credentials (-Ddb.password=...). Masking is unconditional server-side
    // policy, mirroring the bind-param kill-switch philosophy: the LLM cannot opt back in.
    private static final java.util.regex.Pattern SECRET_KEY =
            java.util.regex.Pattern.compile("(?i).*(password|passwd|secret|token|credential|private).*");

    public static String renderObjectEnv(Locale locale, ScouterClient client, String objNameLike,
                                         Long objHash, String keyLike) {
        EnvDto env = client.getObjectEnv(objNameLike, objHash);
        String needle = keyLike == null ? null : keyLike.toLowerCase();
        Map<String, String> rendered = new LinkedHashMap<>();
        env.properties().forEach((k, v) -> {
            if (needle != null && !k.toLowerCase().contains(needle)) {
                return;
            }
            rendered.put(k, SECRET_KEY.matcher(k).matches() ? "***"
                    : scouter.mcp.policy.Truncate.text(v, Limits.ENV_VALUE_MAX_CHARS));
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objHash", env.objHash());
        result.put("objName", env.objName());
        result.put("count", rendered.size());
        result.put("properties", rendered);
        result.put("note", Messages.get(locale, "note.env_masked"));
        if (rendered.isEmpty()) {
            result.put("hint", Messages.get(locale, "hint.env_empty"));
        }
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String renderSummary(Locale locale, ScouterClient client, String category,
                                       long fromMillis, long toMillis, String objType, Long objHash,
                                       String objNameLike) {
        SummaryResult res = client.getSummary(category, fromMillis, toMillis, objType, objHash, objNameLike);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("category", res.category());
        result.put("rowCount", res.totalRows());
        if (res.truncated()) {
            result.put("truncated", true);
        }
        if (res.rows() != null) {
            result.put("rows", res.rows());
        }
        if (res.errorRows() != null) {
            result.put("errorRows", res.errorRows());
        }
        if (res.alertRows() != null) {
            result.put("alertRows", res.alertRows());
        }
        boolean empty = (res.rows() == null || res.rows().isEmpty())
                && (res.errorRows() == null || res.errorRows().isEmpty())
                && (res.alertRows() == null || res.alertRows().isEmpty());
        if (empty) {
            result.put("hint", Messages.get(locale, "hint.summary_empty"));
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
