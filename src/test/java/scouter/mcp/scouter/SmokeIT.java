package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import scouter.mcp.config.Config;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeIT {

    @Test
    @EnabledIfEnvironmentVariable(named = "SCOUTER_COLLECTOR_HOST", matches = ".+")
    void connectsAndListsObjects() {
        Config c = Config.fromEnv(System.getenv());
        try (TcpScouterClient client = new TcpScouterClient(c)) {
            client.connect();
            var objects = client.listObjects();
            assertThat(objects).isNotNull();
            System.err.println("object count=" + objects.size());
        }
    }
}
