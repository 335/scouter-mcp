package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceQueryNormalizerTest {

    @Test
    void bareTokenBecomesContainsPattern() {
        var q = ServiceQueryNormalizer.normalize("orderDetail");
        assertThat(q.serverPattern()).isEqualTo("*orderDetail*");
        assertThat(q.method()).isNull();
        assertThat(q.tokens()).containsExactly("orderdetail");
    }

    @Test
    void leadingHttpMethodIsExtracted() {
        // Users say it the way they'd write an HTTP request: "GET /api/order/order-detail"
        var q = ServiceQueryNormalizer.normalize("GET /api/order/order-detail");
        assertThat(q.method()).isEqualTo("GET");
        assertThat(q.serverPattern()).isEqualTo("*/api/order/order-detail*<GET>");
    }

    @Test
    void trailingHttpMethodIsExtracted() {
        var q = ServiceQueryNormalizer.normalize("/api/order/order-detail POST");
        assertThat(q.method()).isEqualTo("POST");
        assertThat(q.serverPattern()).isEqualTo("*/api/order/order-detail*<POST>");
    }

    @Test
    void scouterAngleBracketMethodIsHonored() {
        var q = ServiceQueryNormalizer.normalize("search-order-info-grade<POST>");
        assertThat(q.method()).isEqualTo("POST");
        assertThat(q.serverPattern()).isEqualTo("*search-order-info-grade*<POST>");
    }

    @Test
    void multiWordInputUsesLongestTokenServerSideAndKeepsAllTokens() {
        // Whitespace never appears in a service URL, so the old '*whole input*' pattern matched nothing.
        var q = ServiceQueryNormalizer.normalize("order info grade");
        assertThat(q.serverPattern()).isEqualTo("*order*");
        assertThat(q.tokens()).containsExactly("order", "info", "grade");
    }

    @Test
    void explicitStarPatternIsUsedAsIs() {
        var q = ServiceQueryNormalizer.normalize("*search-*-grade*");
        assertThat(q.serverPattern()).isEqualTo("*search-*-grade*");
        assertThat(q.method()).isNull();
    }

    @Test
    void lowercaseMethodWorks() {
        var q = ServiceQueryNormalizer.normalize("post orderDetail");
        assertThat(q.method()).isEqualTo("POST");
        assertThat(q.serverPattern()).isEqualTo("*orderDetail*<POST>");
    }

    @Test
    void tokensAreLowercasedAlnumFragmentsOfPath() {
        var q = ServiceQueryNormalizer.normalize("GET /api/order/search-order-info-grade");
        assertThat(q.tokens()).contains("api", "order", "search", "info", "grade");
        assertThat(q.tokens()).doesNotContain("get"); // the method is not a path token
    }
}
