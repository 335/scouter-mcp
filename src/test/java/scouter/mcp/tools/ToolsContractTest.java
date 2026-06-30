package scouter.mcp.tools;

import org.junit.jupiter.api.Test;
import scouter.mcp.scouter.ScouterClient;
import scouter.mcp.scouter.dto.SObjectDto;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
}
