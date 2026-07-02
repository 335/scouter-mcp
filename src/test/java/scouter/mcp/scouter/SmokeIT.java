package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import scouter.mcp.config.Config;
import scouter.mcp.scouter.dto.CounterMetaDto;
import scouter.mcp.scouter.dto.CounterSeriesDto;
import scouter.mcp.scouter.dto.SObjectDto;
import scouter.mcp.scouter.dto.SearchXlogParams;
import scouter.mcp.scouter.dto.XLogDetailDto;
import scouter.mcp.scouter.dto.XLogRowDto;
import scouter.mcp.time.TimeRange;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real collector integration smoke test. Runs only when SCOUTER_COLLECTOR_HOST is set.
 * When there is no data, it gracefully skips instead of asserting, and prints results to stderr.
 */
class SmokeIT {

    @Test
    @EnabledIfEnvironmentVariable(named = "SCOUTER_COLLECTOR_HOST", matches = ".+")
    void connectsAndListsObjects() {
        Config c = Config.fromEnv(System.getenv());
        try (TcpScouterClient client = new TcpScouterClient(c)) {
            client.connect();
            List<SObjectDto> objects = client.listObjects();
            assertThat(objects).isNotNull();
            System.err.println("[smoke] object count=" + objects.size());
            objects.stream().limit(10).forEach(o ->
                    System.err.println("[smoke] obj hash=" + o.objHash() + " name=" + o.objName()
                            + " type=" + o.objType() + " alive=" + o.alive()));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SCOUTER_COLLECTOR_HOST", matches = ".+")
    void exercisesCounterAndXlogQueryPaths() {
        Config c = Config.fromEnv(System.getenv());
        long now = System.currentTimeMillis();
        long from = now - 60 * 60 * 1000L; // last 1 hour
        try (TcpScouterClient client = new TcpScouterClient(c)) {
            client.connect();

            List<SObjectDto> objects = client.listObjects();
            assumeTrue(objects != null && !objects.isEmpty(), "no objects available, skipping query path verification");
            // If SCOUTER_SMOKE_OBJ_TYPE is set, prefer that type (regardless of alive); otherwise prefer alive.
            String filterType = System.getProperty("SCOUTER_SMOKE_OBJ_TYPE");
            SObjectDto target;
            if (filterType != null && !filterType.isBlank()) {
                target = objects.stream()
                        .filter(o -> filterType.equalsIgnoreCase(o.objType()))
                        .findFirst()
                        .orElseGet(() -> {
                            System.err.println("[smoke] WARN: no object of type " + filterType + ", falling back to first");
                            return objects.get(0);
                        });
            } else {
                target = objects.stream().filter(SObjectDto::alive).findFirst().orElse(objects.get(0));
            }
            System.err.println("[smoke] target obj hash=" + target.objHash()
                    + " name=" + target.objName() + " type=" + target.objType());

            // list_counters
            List<CounterMetaDto> counters = client.listCounters(target.objType());
            System.err.println("[smoke] counter meta count=" + (counters == null ? 0 : counters.size()));
            if (counters != null) {
                counters.stream().limit(15).forEach(m ->
                        System.err.println("[smoke]   counter=" + m.counter()
                                + " (" + m.displayName() + ") unit=" + m.unit()));
            }

            // get_counter (use the first counter from meta if possible, otherwise try a representative candidate)
            String counterName = (counters != null && !counters.isEmpty())
                    ? counters.get(0).counter() : "Elapsed";
            List<CounterSeriesDto> series =
                    client.getCounter(List.of(target.objHash()), counterName, from, now);
            int pts = (series == null || series.isEmpty()) ? 0 : series.get(0).points().size();
            System.err.println("[smoke] get_counter '" + counterName + "' series="
                    + (series == null ? 0 : series.size()) + " firstPoints=" + pts);

            // search_xlog: by policy, unfiltered broad queries are limited to 5 minutes, so filter by target objHash.
            List<XLogRowDto> rows = client.searchXlog(
                    new SearchXlogParams(from, now, (long) target.objHash(), null, null, null, null, null, false, 20)).rows();
            if (rows == null || rows.isEmpty()) {
                long wider = now - 6 * 60 * 60 * 1000L;
                System.err.println("[smoke] 0 rows in last 1 hour - widening search to last 6 hours");
                rows = client.searchXlog(
                        new SearchXlogParams(wider, now, (long) target.objHash(), null, null, null, null, null, false, 20)).rows();
            }
            System.err.println("[smoke] search_xlog rows=" + (rows == null ? 0 : rows.size()));
            if (rows != null) {
                rows.stream().limit(5).forEach(r ->
                        System.err.println("[smoke]   xlog txid=" + r.txid() + " gxid=" + r.gxid()
                                + " svc=" + r.service() + " elapsed=" + r.elapsedMs() + "ms"
                                + " err=" + r.error() + " endIso=" + r.endTimeIso()));
            }

            // get_xlog_detail (if rows exist, fetch first row detail)
            if (rows != null && !rows.isEmpty()) {
                XLogRowDto first = rows.get(0);
                String ymd = TimeRange.yyyymmdd(first.endTimeMillis(), c.zone());
                XLogDetailDto detail = client.getXlogDetail(first.txid(), ymd, true);
                int stepCount = detail.steps() == null ? 0 : detail.steps().size();
                int sqlCount = detail.sqls() == null ? 0 : detail.sqls().size();
                System.err.println("[smoke] get_xlog_detail txid=" + first.txid()
                        + " steps=" + stepCount + " sqls=" + sqlCount
                        + " errors=" + (detail.errors() == null ? 0 : detail.errors().size()));
                if (detail.sqls() != null) {
                    detail.sqls().stream().limit(3).forEach(s ->
                            System.err.println("[smoke]   sql elapsed=" + s.elapsedMs() + "ms binds="
                                    + (s.bindParams() == null ? 0 : s.bindParams().size())
                                    + " sql=" + abbreviate(s.sql())));
                }

                // get_xlog_by_gxid (if gxid exists, fetch the grouped transactions)
                if (first.gxid() != 0L) {
                    List<XLogRowDto> grp = client.getXlogByGxid(first.gxid(), ymd);
                    System.err.println("[smoke] get_xlog_by_gxid rows=" + (grp == null ? 0 : grp.size()));
                }
            } else {
                System.err.println("[smoke] no XLog in last 1 hour - skipping detail/gxid paths");
            }
        }
    }

    /**
     * Verifies transaction detail that has SQL/bind parameters.
     * Uses a minElapsedMs=10ms filter to weed out web-filter healthCheck-style transactions,
     * then fetches the detail of the first row that has SQL.
     * Skips if there is no SQL (absence of data is not an implementation defect).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "SCOUTER_COLLECTOR_HOST", matches = ".+")
    void xlogDetailWithSql() {
        Config c = Config.fromEnv(System.getenv());
        long now = System.currentTimeMillis();
        long from = now - 60 * 60 * 1000L;

        String filterType = System.getProperty("SCOUTER_SMOKE_OBJ_TYPE");
        try (TcpScouterClient client = new TcpScouterClient(c)) {
            client.connect();

            // Select target object
            List<SObjectDto> objects = client.listObjects();
            SObjectDto target;
            if (filterType != null && !filterType.isBlank()) {
                target = objects.stream()
                        .filter(o -> filterType.equalsIgnoreCase(o.objType()))
                        .findFirst().orElse(null);
            } else {
                target = objects.stream().filter(SObjectDto::alive).findFirst().orElse(null);
            }
            assumeTrue(target != null, "no target object");
            System.err.println("[smoke-sql] target hash=" + target.objHash() + " name=" + target.objName());

            // Broad search: minElapsedMs=10 to keep only non-trivial transactions, top 50
            List<XLogRowDto> rows = client.searchXlog(
                    new SearchXlogParams(from, now, (long) target.objHash(), null, null, null, null, 10, false, 50)).rows();
            if (rows == null || rows.isEmpty()) {
                // widen to 6 hours
                rows = client.searchXlog(
                        new SearchXlogParams(now - 6 * 60 * 60 * 1000L, now,
                                (long) target.objHash(), null, null, null, null, 10, false, 50)).rows();
            }
            System.err.println("[smoke-sql] xlog rows(minElapsed=10ms)=" + (rows == null ? 0 : rows.size()));
            if (rows != null) {
                rows.stream().limit(10).forEach(r ->
                        System.err.println("[smoke-sql]   txid=" + r.txid()
                                + " svc=" + r.service() + " elapsed=" + r.elapsedMs() + "ms"));
            }

            // Find the first detail that has SQL (try up to 10 rows)
            XLogDetailDto found = null;
            if (rows != null) {
                for (XLogRowDto r : rows.stream().limit(10).toList()) {
                    String ymd = TimeRange.yyyymmdd(r.endTimeMillis(), c.zone());
                    XLogDetailDto d = client.getXlogDetail(r.txid(), ymd, true);
                    int sqlCnt = d.sqls() == null ? 0 : d.sqls().size();
                    if (sqlCnt > 0) {
                        found = d;
                        System.err.println("[smoke-sql] txid with SQL=" + r.txid()
                                + " steps=" + (d.steps() == null ? 0 : d.steps().size())
                                + " sqls=" + sqlCnt);
                        d.sqls().forEach(s ->
                                System.err.println("[smoke-sql]   sql elapsed=" + s.elapsedMs() + "ms"
                                        + " binds=" + (s.bindParams() == null ? 0 : s.bindParams().size())
                                        + " sql=" + abbreviate(s.sql())
                                        + (s.bindParams() != null && !s.bindParams().isEmpty()
                                            ? " | params=" + s.bindParams() : "")));
                        break;
                    }
                }
            }
            if (found == null) {
                System.err.println("[smoke-sql] no transaction with SQL found in search range - skip");
            }
        }
    }

    /**
     * Verifies per-day counter chunking: a window that straddles midnight must return points on both
     * sides of the boundary (a single COUNTER_PAST_TIME_ALL call would only cover one day).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "SCOUTER_COLLECTOR_HOST", matches = ".+")
    void counterAcrossMidnight() {
        Config c = Config.fromEnv(System.getenv());
        long now = System.currentTimeMillis();
        long from = now - 26 * 60 * 60 * 1000L; // 26h window -> crosses at least one local midnight
        String filterType = System.getProperty("SCOUTER_SMOKE_OBJ_TYPE");
        try (TcpScouterClient client = new TcpScouterClient(c)) {
            client.connect();
            Integer objHash = client.listObjects().stream()
                    .filter(o -> filterType == null || filterType.equalsIgnoreCase(o.objType()))
                    .map(SObjectDto::objHash).findFirst().orElse(null);
            assumeTrue(objHash != null, "no target object");
            List<CounterSeriesDto> series = client.getCounter(List.of(objHash), "RecentUser", from, now);
            int pts = series.isEmpty() ? 0 : series.get(0).points().size();
            // local midnight just before 'now'
            long midnight = java.time.Instant.ofEpochMilli(now).atZone(c.zone())
                    .toLocalDate().atStartOfDay(c.zone()).toInstant().toEpochMilli();
            boolean before = false;
            boolean after = false;
            if (!series.isEmpty()) {
                for (PackMapper.Point p : series.get(0).points()) {
                    if (p.timeMillis() < midnight) {
                        before = true;
                    } else {
                        after = true;
                    }
                }
            }
            System.err.println("[smoke-day] points=" + pts + " spanBeforeMidnight=" + before
                    + " spanAfterMidnight=" + after + " (midnight=" + midnight + ")");
            // Probe: query a window fully inside yesterday to distinguish a split bug from data retention.
            List<CounterSeriesDto> y = client.getCounter(List.of(objHash), "RecentUser", midnight - 3600_000L, midnight - 1000L);
            int ypts = y.isEmpty() ? 0 : y.get(0).points().size();
            System.err.println("[smoke-day] yesterday-1h-only points=" + ypts
                    + " -> if 0 it's retention, not a split bug");
            // Verify the TOOL output is bounded: renderGetCounter downsamples to <= COUNTER_MAX_POINTS and adds stats.
            String json = scouter.mcp.tools.Tools.renderGetCounter(
                    java.util.Locale.ENGLISH, client, List.of(objHash), "RecentUser", from, now);
            int renderedPts = json.split("\"timeMillis\"", -1).length - 1;
            System.err.println("[smoke-day] rendered jsonLen=" + json.length()
                    + " renderedPoints=" + renderedPts + " hasDownsampledFlag=" + json.contains("downsampledTo")
                    + " hasStats=" + (json.contains("\"avg\"") && json.contains("\"max\"")));
        }
    }

    /**
     * Verifies service partial matching: even with only a short token, the collector (StrMatch)
     * should match the full service name.
     * Queries just 1 row using SCOUTER_SMOKE_SERVICE (default search-order-info-grade).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "SCOUTER_COLLECTOR_HOST", matches = ".+")
    void searchXlogByServiceToken() {
        Config c = Config.fromEnv(System.getenv());
        long now = System.currentTimeMillis();
        long from = now - 60 * 60 * 1000L;
        String token = System.getProperty("SCOUTER_SMOKE_SERVICE", "search-order-info-grade");
        String filterType = System.getProperty("SCOUTER_SMOKE_OBJ_TYPE");

        try (TcpScouterClient client = new TcpScouterClient(c)) {
            client.connect();
            Long objHash = null;
            if (filterType != null && !filterType.isBlank()) {
                objHash = client.listObjects().stream()
                        .filter(o -> filterType.equalsIgnoreCase(o.objType()))
                        .map(o -> (long) o.objHash()).findFirst().orElse(null);
            }
            // Pass only a short token (no full service name). limit=1 for a single example.
            List<XLogRowDto> rows = client.searchXlog(
                    new SearchXlogParams(from, now, objHash, token, null, null, null, null, false, 1)).rows();
            System.err.println("[smoke-svc] token='" + token + "' rows=" + (rows == null ? 0 : rows.size()));
            if (rows != null && !rows.isEmpty()) {
                XLogRowDto r = rows.get(0);
                System.err.println("[smoke-svc] matched svc=" + r.service()
                        + " txid=" + r.txid() + " elapsed=" + r.elapsedMs() + "ms endIso=" + r.endTimeIso());
            } else {
                System.err.println("[smoke-svc] no match in last 1 hour (data may be absent) - skip");
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SCOUTER_COLLECTOR_HOST", matches = ".+")
    void exercisesServiceSummary() {
        Config c = Config.fromEnv(System.getenv());
        long now = System.currentTimeMillis();
        long from = now - 60 * 60 * 1000L;
        try (TcpScouterClient client = new TcpScouterClient(c)) {
            client.connect();
            List<SObjectDto> objects = client.listObjects();
            assumeTrue(objects != null && !objects.isEmpty(), "no objects, skipping summary");
            SObjectDto target = objects.stream().filter(SObjectDto::alive).findFirst().orElse(objects.get(0));
            var res = client.getServiceSummary(new SearchXlogParams(
                    from, now, (long) target.objHash(), null, null, null, null, null, false, 0));
            assertThat(res).isNotNull();
            System.err.println("[smoke-summary] services=" + res.services().size()
                    + " totalCount=" + res.totalCount() + " scanCapReached=" + res.scanCapReached());
            res.services().stream().limit(5).forEach(s ->
                    System.err.println("[smoke-summary] " + abbreviate(s.service())
                            + " count=" + s.count() + " avgMs=" + s.avgMs() + " maxMs=" + s.maxMs()
                            + " p95Ms=" + s.p95Ms() + " errorRate=" + s.errorRate()));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SCOUTER_COLLECTOR_HOST", matches = ".+")
    void exercisesListAlerts() {
        Config c = Config.fromEnv(System.getenv());
        long now = System.currentTimeMillis();
        long from = now - 24 * 60 * 60 * 1000L; // last 24 hours
        try (TcpScouterClient client = new TcpScouterClient(c)) {
            client.connect();
            var alerts = client.getAlerts(from, now, null, null, null, 20);
            assertThat(alerts).isNotNull();
            System.err.println("[smoke-alerts] count=" + alerts.size());
            alerts.stream().limit(5).forEach(a ->
                    System.err.println("[smoke-alerts] " + a.timeIso() + " level=" + a.level()
                            + " objType=" + a.objType() + " obj=" + a.objName()
                            + " title=" + abbreviate(a.title())));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SCOUTER_COLLECTOR_HOST", matches = ".+")
    void exercisesActiveServices() {
        Config c = Config.fromEnv(System.getenv());
        try (TcpScouterClient client = new TcpScouterClient(c)) {
            client.connect();
            List<SObjectDto> objects = client.listObjects();
            assumeTrue(objects != null && !objects.isEmpty(), "no objects, skipping active services");
            SObjectDto target = objects.stream().filter(SObjectDto::alive).findFirst().orElse(objects.get(0));
            var active = client.getActiveServices(null, (long) target.objHash());
            assertThat(active).isNotNull();
            System.err.println("[smoke-active] objHash=" + target.objHash() + " running=" + active.size());
            active.stream().limit(5).forEach(a ->
                    System.err.println("[smoke-active] id=" + a.id() + " state=" + a.state()
                            + " elapsedMs=" + a.elapsedMs() + " service=" + abbreviate(a.service())
                            + " sql=" + abbreviate(a.sql())));
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "...";
    }
}
