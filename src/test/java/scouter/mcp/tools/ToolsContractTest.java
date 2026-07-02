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
    void serviceSummaryToolRendersAggregatesAndScanSignal() {
        ScouterClient client = mock(ScouterClient.class);
        XlogSummaryResult res = new XlogSummaryResult(
                List.of(new ServiceSummaryDto("/api/order", 120, 3, 0.025d, 42.5d, 900, 780)),
                120, true, 200_000);
        when(client.getServiceSummary(any())).thenReturn(res);

        String json = Tools.renderServiceSummary(Locale.ENGLISH, client,
                new scouter.mcp.scouter.dto.SearchXlogParams(1000L, 2000L, null, null, null, null, null, null, null, false, 0));

        assertThat(json).contains("/api/order");
        assertThat(json).contains("\"p95Ms\":780");
        assertThat(json).contains("\"scanCapReached\":true");
        assertThat(json).contains("hint"); // scan cap reached -> narrowing hint
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
}
