package scouter.mcp.tools;

import org.junit.jupiter.api.Test;
import scouter.mcp.scouter.PackMapper;
import scouter.mcp.scouter.ScouterClient;
import scouter.mcp.scouter.dto.ActiveServiceDto;
import scouter.mcp.scouter.dto.AlertDto;
import scouter.mcp.scouter.dto.CounterSeriesDto;
import scouter.mcp.scouter.dto.SObjectDto;
import scouter.mcp.scouter.dto.ServiceSummaryDto;
import scouter.mcp.scouter.dto.XlogSummaryResult;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolsContractTest {

    @Test
    void listObjectsToolReturnsJsonWithFilters() {
        ScouterClient client = mock(ScouterClient.class);
        when(client.listObjects()).thenReturn(List.of(
                new SObjectDto(1, "app-1", "tomcat", "10.0.0.1", true),
                new SObjectDto(2, "redis-1", "redis", "10.0.0.2", true)));

        String json = Tools.renderListObjects(client, "tomcat", null);

        assertThat(json).contains("app-1").contains("tomcat");
        assertThat(json).doesNotContain("redis-1");
    }

    @Test
    void listObjectsGroupsByObjTypeWithAliveCounts() {
        // A flat 50+ object dump makes the model relay a flat list; grouped output begets a grouped answer.
        ScouterClient client = mock(ScouterClient.class);
        when(client.listObjects()).thenReturn(List.of(
                new SObjectDto(1, "/podA/app-a1", "app-a", "10.0.0.1", true),
                new SObjectDto(2, "/podB/app-a1", "app-a", "10.0.0.2", false),
                new SObjectDto(3, "/podC/app-b1", "app-b", "10.0.0.3", true)));

        String json = Tools.renderListObjects(client, null, null);

        assertThat(json).contains("\"types\"");
        assertThat(json).contains("\"objType\":\"app-a\"");
        assertThat(json).contains("\"total\":2").contains("\"alive\":1"); // app-a: 2 pods, 1 alive
        assertThat(json).contains("\"count\":3");
    }

    @Test
    void getCounterToolRendersSeriesWithPoints() {
        ScouterClient client = mock(ScouterClient.class);
        CounterSeriesDto series = new CounterSeriesDto(101, "Cpu", List.of(
                new PackMapper.Point(1000L, 1.5d),
                new PackMapper.Point(2000L, 2.5d)));
        when(client.getCounter(any(), eq("Cpu"), anyLong(), anyLong())).thenReturn(List.of(series));

        String json = Tools.renderGetCounter(Locale.ENGLISH, client, List.of(101), "Cpu", 1000L, 2000L);

        assertThat(json).contains("Cpu");
        assertThat(json).contains("\"count\":1");
        assertThat(json).contains("101");
        assertThat(json).contains("1000").contains("1.5");
        assertThat(json).contains("2000").contains("2.5");
        assertThat(json).doesNotContain("hint");
    }

    @Test
    void searchEmptyWithAppNameInServiceFilterSuggestsObjNameLike() {
        // The classic wandering case: the model put an app name ("shop-order-api") into the service
        // (=request URL) filter -> 0 rows. The result flags it and the hint must steer to objNameLike.
        ScouterClient client = mock(ScouterClient.class);
        when(client.searchXlog(any())).thenReturn(
                new scouter.mcp.scouter.dto.XlogSearchResult(List.of(), false, false, 0, true, List.of(), false));

        String json = Tools.renderSearchXlog(Locale.ENGLISH, client,
                new scouter.mcp.scouter.dto.SearchXlogParams(1000L, 2000L, null, null, "shop-order-api",
                        null, null, null, null, false, 20));

        assertThat(json).contains("objNameLike");
        assertThat(json).contains("shop-order-api");
    }

    @Test
    void searchEmptyWithSloppyServiceReturnsRealCandidates() {
        // "orderdetail" (wrong case) matched nothing server-side; discovery found the real names.
        ScouterClient client = mock(ScouterClient.class);
        when(client.searchXlog(any())).thenReturn(
                new scouter.mcp.scouter.dto.XlogSearchResult(List.of(), false, false, 0, false,
                        List.of("/api/order/order-detail<GET>", "/api/order/order-detail<POST>"), false));

        String json = Tools.renderSearchXlog(Locale.ENGLISH, client,
                new scouter.mcp.scouter.dto.SearchXlogParams(1000L, 2000L, null, null, "orderdetail",
                        null, null, null, null, false, 20));

        assertThat(json).contains("serviceCandidates");
        assertThat(json).contains("/api/order/order-detail<GET>");
        assertThat(json).contains("hint");
    }

    @Test
    void serviceSummaryToolRendersAggregatesAndScanSignal() {
        ScouterClient client = mock(ScouterClient.class);
        XlogSummaryResult res = new XlogSummaryResult(
                List.of(new ServiceSummaryDto("/api/order", 120, 3, 0.025d, 42.5d, 900, 780)),
                120, true, 200_000, false, List.of(), false);
        when(client.getServiceSummary(any())).thenReturn(res);

        String json = Tools.renderServiceSummary(Locale.ENGLISH, client,
                new scouter.mcp.scouter.dto.SearchXlogParams(1000L, 2000L, null, null, null, null, null, null, null, false, 0));

        assertThat(json).contains("/api/order");
        assertThat(json).contains("\"p95Ms\":780");
        assertThat(json).contains("\"scanCapReached\":true");
        assertThat(json).contains("hint"); // scan cap reached -> narrowing hint
    }

    @Test
    void serviceSummaryTimedOutWithServiceFilterSteersToGetSummaryFirst() {
        ScouterClient client = mock(ScouterClient.class);
        XlogSummaryResult res = new XlogSummaryResult(
                List.of(), 0, false, 3018, false, List.of(), true); // timedOut
        when(client.getServiceSummary(any())).thenReturn(res);

        String json = Tools.renderServiceSummary(Locale.ENGLISH, client,
                new scouter.mcp.scouter.dto.SearchXlogParams(1000L, 2000L, null, "grm-biz-member",
                        "checkOrdCnfCustEastnAddr", null, null, null, null, false, 0));

        assertThat(json).contains("\"timedOut\":true");
        // service filter + timeout -> steer to the cheap get_summary-first procedure
        assertThat(json).contains("get_summary");
    }

    @Test
    void searchXlogEmptyWithServiceFilterAndNoCandidatesSteersToGetSummaryOrConfirm() {
        ScouterClient client = mock(ScouterClient.class);
        // empty, not-an-app, no candidates found -> likely not a real service name
        when(client.searchXlog(any())).thenReturn(
                new scouter.mcp.scouter.dto.XlogSearchResult(List.of(), false, false, 0, false, List.of(), false));

        String json = Tools.renderSearchXlog(Locale.ENGLISH, client,
                new scouter.mcp.scouter.dto.SearchXlogParams(1000L, 2000L, null, "grm-biz-member",
                        "checkOrdCnfCustEastnAddr", null, null, null, null, false, 20));

        // should steer to get_summary validation / user confirmation, not a blind widen
        assertThat(json).contains("get_summary");
    }

    @Test
    void searchXlogTimedOutWithoutServiceFilterUsesGenericDeadlineHint() {
        ScouterClient client = mock(ScouterClient.class);
        when(client.searchXlog(any())).thenReturn(
                new scouter.mcp.scouter.dto.XlogSearchResult(List.of(), true, false, 100, false, List.of(), true));

        String json = Tools.renderSearchXlog(Locale.ENGLISH, client,
                new scouter.mcp.scouter.dto.SearchXlogParams(1000L, 2000L, null, "grm-biz-member",
                        null, null, null, null, null, false, 20));

        assertThat(json).contains("\"timedOut\":true");
        // no service filter -> generic deadline hint, not the service-scan procedure
        assertThat(json).doesNotContain("get_summary");
    }

    @Test
    void listAlertsToolRendersAlerts() {
        ScouterClient client = mock(ScouterClient.class);
        when(client.getAlerts(anyLong(), anyLong(), isNull(), isNull(), isNull(), eq(20))).thenReturn(List.of(
                new AlertDto(1000L, "2026-07-01T10:00:00+09:00", "ERROR", "tomcat", 7, "app-1",
                        "GC overhead", "long gc pause")));

        String json = Tools.renderListAlerts(Locale.ENGLISH, client, 1000L, 2000L, null, null, null, 20);

        assertThat(json).contains("ERROR").contains("GC overhead").contains("app-1");
        assertThat(json).doesNotContain("hint");
    }

    @Test
    void activeServicesToolEmptyAddsHint() {
        ScouterClient client = mock(ScouterClient.class);
        when(client.getActiveServices(isNull(), eq(7L), isNull())).thenReturn(List.of());

        String json = Tools.renderActiveServices(Locale.ENGLISH, client, null, 7L, null);

        assertThat(json).contains("\"count\":0");
        assertThat(json).contains("hint");
    }

    @Test
    void getCounterToolEmptyAddsHint() {
        ScouterClient client = mock(ScouterClient.class);
        when(client.getCounter(any(), eq("Cpu"), anyLong(), anyLong())).thenReturn(List.of());

        String json = Tools.renderGetCounter(Locale.ENGLISH, client, List.of(101), "Cpu", 1000L, 2000L);

        assertThat(json).contains("\"series\":[]");
        assertThat(json).contains("hint");
    }

    @Test
    void listThreadsRendersStateCountsAndRows() {
        ScouterClient client = mock(ScouterClient.class);
        when(client.listThreads(eq("app"), isNull())).thenReturn(List.of(
                new scouter.mcp.scouter.dto.ThreadListDto(1, "/pod/app1", 3,
                        java.util.Map.of("RUNNABLE", 2, "BLOCKED", 1),
                        List.of(new scouter.mcp.scouter.dto.ThreadRowDto(
                                11, "http-1", "RUNNABLE", 120, "ztx", 500L, "/api/x")),
                        true)));

        String json = Tools.renderListThreads(Locale.ENGLISH, client, "app", null);

        assertThat(json).contains("RUNNABLE").contains("http-1").contains("\"totalThreads\":3");
        assertThat(json).contains("\"truncated\":true");
    }

    @Test
    void threadDetailRendersStackAndHintsWhenStale() {
        ScouterClient client = mock(ScouterClient.class);
        when(client.getThreadDetail(isNull(), eq(1L), eq(11L), eq(77L), eq(true))).thenReturn(
                new scouter.mcp.scouter.dto.ThreadDetailDto(1, "/pod/app1", "[No Thread] End", null, "end",
                        null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null));

        String json = Tools.renderThreadDetail(Locale.ENGLISH, client, null, 1L, 11L, 77L, true);

        assertThat(json).contains("\"state\":\"end\"");
        assertThat(json).contains("hint"); // stale txid -> refresh guidance
    }

    @Test
    void objectEnvMasksSecretsAndFiltersKeys() {
        ScouterClient client = mock(ScouterClient.class);
        java.util.Map<String, String> props = new java.util.LinkedHashMap<>();
        props.put("java.version", "17.0.9");
        props.put("db.password", "supersecret");
        props.put("api.token", "abc123secret");
        props.put("java.class.path", "x".repeat(2000));
        when(client.getObjectEnv(eq("app"), isNull()))
                .thenReturn(new scouter.mcp.scouter.dto.EnvDto(1, "/pod/app1", props));

        String json = Tools.renderObjectEnv(Locale.ENGLISH, client, "app", null, null);
        assertThat(json).contains("17.0.9");
        assertThat(json).doesNotContain("supersecret").doesNotContain("abc123secret");
        assertThat(json).contains("***");
        assertThat(json).contains("truncated"); // class.path cut to ENV_VALUE_MAX_CHARS

        String filtered = Tools.renderObjectEnv(Locale.ENGLISH, client, "app", null, "version");
        assertThat(filtered).contains("java.version").doesNotContain("class.path");
    }

    @Test
    void summaryRendersSqlCategoryTopRows() {
        ScouterClient client = mock(ScouterClient.class);
        when(client.getSummary(eq("sql"), anyLong(), anyLong(), isNull(), isNull(), eq("app"))).thenReturn(
                new scouter.mcp.scouter.dto.SummaryResult("sql", 2, false,
                        List.of(new scouter.mcp.scouter.dto.SummaryRowDto("SELECT * FROM t", 100, 1L, 5000L, 50.0),
                                new scouter.mcp.scouter.dto.SummaryRowDto("UPDATE t SET x=1", 10, 0L, 100L, 10.0)),
                        null, null, false));

        String json = Tools.renderSummary(Locale.ENGLISH, client, "sql", 0, 1000, null, null, "app");

        assertThat(json).contains("SELECT * FROM t").contains("\"category\":\"sql\"");
        assertThat(json).doesNotContain("errorRows"); // NON_NULL omission
    }

    @Test
    void summaryEmptyGetsHint() {
        ScouterClient client = mock(ScouterClient.class);
        when(client.getSummary(eq("error"), anyLong(), anyLong(), isNull(), isNull(), isNull())).thenReturn(
                new scouter.mcp.scouter.dto.SummaryResult("error", 0, false, null, List.of(), null, false));

        String json = Tools.renderSummary(Locale.ENGLISH, client, "error", 0, 1000, null, null, null);

        assertThat(json).contains("hint");
    }

    @Test
    void counterStatRendersSeriesWithResolutionMarker() {
        ScouterClient client = mock(ScouterClient.class);
        when(client.getCounterStat(any(), eq("TPS"), eq("20260601"), eq("20260630"))).thenReturn(List.of(
                new CounterSeriesDto(101, "TPS", List.of(
                        new PackMapper.Point(1000L, 10d), new PackMapper.Point(2000L, 20d)))));
        when(client.listObjects()).thenReturn(List.of());

        String json = Tools.renderCounterStat(Locale.ENGLISH, client, List.of(101), "TPS",
                "20260601", "20260630");

        assertThat(json).contains("\"counter\":\"TPS\"").contains("\"resolution\":\"5m\"");
        assertThat(json).contains("101").contains("\"avg\":15.0");
    }

    @Test
    void searchXlogWarnsWhenClientSideFilterDiscardsAlmostEverything() {
        // minElapsedMs/onlyError drop rows only AFTER the collector streamed them; when nearly all
        // scanned rows are discarded the hint must steer to server-side filters / summary tools.
        ScouterClient client = mock(ScouterClient.class);
        when(client.searchXlog(any())).thenReturn(
                new scouter.mcp.scouter.dto.XlogSearchResult(List.of(), false, false, 5000, false, List.of(), false));

        String json = Tools.renderSearchXlog(Locale.ENGLISH, client,
                new scouter.mcp.scouter.dto.SearchXlogParams(1000L, 2000L, null, null, null,
                        null, null, null, 10_000, false, 20));

        assertThat(json).contains("client-side");
    }
}
