package scouter.mcp.tools;

import org.junit.jupiter.api.Test;
import scouter.mcp.scouter.PackMapper;
import scouter.mcp.scouter.ScouterClient;
import scouter.mcp.scouter.dto.CounterSeriesDto;
import scouter.mcp.scouter.dto.SObjectDto;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
    void getCounterToolEmptyAddsHint() {
        ScouterClient client = mock(ScouterClient.class);
        when(client.getCounter(any(), eq("Cpu"), anyLong(), anyLong())).thenReturn(List.of());

        String json = Tools.renderGetCounter(Locale.ENGLISH, client, List.of(101), "Cpu", 1000L, 2000L);

        assertThat(json).contains("\"series\":[]");
        assertThat(json).contains("hint");
    }
}
