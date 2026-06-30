package scouter.mcp.error;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class McpErrorTest {

    @Test
    void rendersCodeAndHintsAsSingleLineKeyValue() {
        McpError e = McpError.of(McpError.Code.SCOUTER_CONNECT_FAILED, "collector unreachable")
                .withHint("host", "scouter-collector")
                .withHint("port", "6100");
        String s = e.toLogLine();
        assertThat(s).isEqualTo(
                "code=SCOUTER_CONNECT_FAILED msg=\"collector unreachable\" host=scouter-collector port=6100");
        assertThat(s).doesNotContain("\n");
    }

    @Test
    void neverEchoesCredentialsHelper() {
        McpError e = McpError.of(McpError.Code.SCOUTER_AUTH_FAILED, "login rejected");
        assertThat(e.toLogLine()).doesNotContain("pass");
    }
}
