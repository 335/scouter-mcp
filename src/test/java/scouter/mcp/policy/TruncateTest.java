package scouter.mcp.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TruncateTest {

    @Test
    void returnsShortTextUnchanged() {
        assertThat(Truncate.text("abc", 10)).isEqualTo("abc");
        assertThat(Truncate.text(null, 10)).isNull();
    }

    @Test
    void cutsLongTextWithMarker() {
        String s = "x".repeat(100);
        String out = Truncate.text(s, 10);
        assertThat(out).startsWith("xxxxxxxxxx");
        assertThat(out).contains("truncated").contains("100");
        assertThat(out.length()).isLessThan(60);
    }
}
