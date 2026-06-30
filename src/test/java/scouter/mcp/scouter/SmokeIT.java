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
 * 실 collector 통합 스모크. SCOUTER_COLLECTOR_HOST 가 있을 때만 동작한다.
 * 데이터가 없으면 단언 대신 graceful skip 하며, 결과는 stderr 로 출력한다.
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
        long from = now - 60 * 60 * 1000L; // 최근 1시간
        try (TcpScouterClient client = new TcpScouterClient(c)) {
            client.connect();

            List<SObjectDto> objects = client.listObjects();
            assumeTrue(objects != null && !objects.isEmpty(), "오브젝트가 없어 쿼리 경로 검증을 건너뜀");
            SObjectDto target = objects.stream().filter(SObjectDto::alive).findFirst().orElse(objects.get(0));
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

            // get_counter (가능하면 메타의 첫 카운터, 없으면 대표 후보로 시도)
            String counterName = (counters != null && !counters.isEmpty())
                    ? counters.get(0).counter() : "Elapsed";
            List<CounterSeriesDto> series =
                    client.getCounter(List.of(target.objHash()), counterName, from, now);
            int pts = (series == null || series.isEmpty()) ? 0 : series.get(0).points().size();
            System.err.println("[smoke] get_counter '" + counterName + "' series="
                    + (series == null ? 0 : series.size()) + " firstPoints=" + pts);

            // search_xlog: 트랜잭션을 잡기 위해 광역(objHash=null)으로 검색하고,
            // 비면 윈도우를 6시간까지 넓혀 재시도한다.
            List<XLogRowDto> rows = client.searchXlog(
                    new SearchXlogParams(from, now, null, null, null, false, 20));
            if (rows == null || rows.isEmpty()) {
                long wider = now - 6 * 60 * 60 * 1000L;
                System.err.println("[smoke] 최근 1시간 0건 - 최근 6시간으로 확대 재검색");
                rows = client.searchXlog(new SearchXlogParams(wider, now, null, null, null, false, 20));
            }
            System.err.println("[smoke] search_xlog rows=" + (rows == null ? 0 : rows.size()));
            if (rows != null) {
                rows.stream().limit(5).forEach(r ->
                        System.err.println("[smoke]   xlog txid=" + r.txid() + " gxid=" + r.gxid()
                                + " svc=" + r.service() + " elapsed=" + r.elapsedMs() + "ms"
                                + " err=" + r.error() + " endIso=" + r.endTimeIso()));
            }

            // get_xlog_detail (행이 있으면 첫 행 상세 + 바인드 파라미터 마스킹 확인)
            if (rows != null && !rows.isEmpty()) {
                XLogRowDto first = rows.get(0);
                String ymd = TimeRange.yyyymmdd(first.endTimeMillis(), c.zone());
                XLogDetailDto detail = client.getXlogDetail(first.txid(), ymd, true, true);
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

                // get_xlog_by_gxid (gxid 가 있으면 묶음 조회)
                if (first.gxid() != 0L) {
                    List<XLogRowDto> grp = client.getXlogByGxid(first.gxid(), ymd);
                    System.err.println("[smoke] get_xlog_by_gxid rows=" + (grp == null ? 0 : grp.size()));
                }
            } else {
                System.err.println("[smoke] 최근 1시간 XLog 없음 - 상세/gxid 경로 건너뜀");
            }
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
