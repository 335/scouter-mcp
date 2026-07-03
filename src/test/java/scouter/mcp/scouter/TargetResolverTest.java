package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;
import scouter.mcp.scouter.dto.SObjectDto;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TargetResolverTest {

    // Realistic k8s-style objNames: pod suffix changes every deploy, multiple replicas.
    private final List<SObjectDto> objects = List.of(
            new SObjectDto(11, "/shop-order-api-deployment-5f4b8c7d9-abcde/shop-order-api1", "tomcat", "10.0.0.1", true),
            new SObjectDto(12, "/shop-order-api-deployment-5f4b8c7d9-fghij/shop-order-api1", "tomcat", "10.0.0.2", true),
            new SObjectDto(13, "/shop-order-api-deployment-77aaa11-dead1/shop-order-api1", "tomcat", "10.0.0.3", false),
            new SObjectDto(21, "/shop-cart-api-deployment-55bb/shop-cart-api1", "tomcat", "10.0.1.1", true),
            new SObjectDto(31, "/redis-main-0/redis-main", "redis", "10.0.2.1", true));

    @Test
    void matchesCaseInsensitiveSubstringOnObjName() {
        List<SObjectDto> got = TargetResolver.match(objects, "SHOP-ORDER-API");
        assertThat(got).extracting(SObjectDto::objHash).containsExactlyInAnyOrder(11, 12, 13);
    }

    @Test
    void aliveInstancesComeFirst() {
        List<SObjectDto> got = TargetResolver.match(objects, "shop-order-api");
        assertThat(got.get(0).alive()).isTrue();
        assertThat(got.get(1).alive()).isTrue();
        assertThat(got.get(2).alive()).isFalse();
    }

    @Test
    void matchesByObjTypeToo() {
        List<SObjectDto> got = TargetResolver.match(objects, "redis");
        assertThat(got).extracting(SObjectDto::objHash).contains(31);
    }

    @Test
    void tokenFallbackWhenNoDirectSubstring() {
        // "order shop" is not a substring, but both tokens appear in the objName.
        List<SObjectDto> got = TargetResolver.match(objects, "order shop");
        assertThat(got).extracting(SObjectDto::objHash).containsExactlyInAnyOrder(11, 12, 13);
    }

    @Test
    void emptyWhenNothingMatches() {
        assertThat(TargetResolver.match(objects, "payment")).isEmpty();
    }

    @Test
    void suggestReturnsDistinctNamesBounded() {
        List<String> s = TargetResolver.suggest(objects, 3);
        assertThat(s).hasSizeLessThanOrEqualTo(3);
        assertThat(s).allMatch(n -> n.startsWith("/"));
    }

    // unionByHash: merges the real-time object list with historical (daily DB) lists so that pods
    // replaced by a deploy — absent from OBJECT_LIST_REAL_TIME — remain resolvable for past windows.

    @Test
    void unionAppendsHistoricalOnlyEntries() {
        List<SObjectDto> realtime = List.of(
                new SObjectDto(11, "/shop-order-api-deployment-new-aaa/shop-order-api1", "tomcat", "10.0.0.1", true));
        List<SObjectDto> daily = List.of(
                new SObjectDto(99, "/shop-order-api-deployment-old-zzz/shop-order-api1", "tomcat", "", false));
        List<SObjectDto> got = TargetResolver.unionByHash(realtime, daily);
        assertThat(got).extracting(SObjectDto::objHash).containsExactly(11, 99);
    }

    @Test
    void unionPrefersRealtimeEntryOnDuplicateHash() {
        List<SObjectDto> realtime = List.of(
                new SObjectDto(11, "/shop-order-api-deployment-aaa/shop-order-api1", "tomcat", "10.0.0.1", true));
        List<SObjectDto> daily = List.of(
                new SObjectDto(11, "/shop-order-api-deployment-aaa/shop-order-api1", "tomcat", "", false));
        List<SObjectDto> got = TargetResolver.unionByHash(realtime, daily);
        assertThat(got).hasSize(1);
        assertThat(got.get(0).alive()).isTrue();
        assertThat(got.get(0).address()).isEqualTo("10.0.0.1");
    }

    @Test
    void unionToleratesNullAndEmptyInputs() {
        List<SObjectDto> only = List.of(
                new SObjectDto(11, "/a/app1", "tomcat", "10.0.0.1", true));
        assertThat(TargetResolver.unionByHash(only, null)).containsExactlyElementsOf(only);
        assertThat(TargetResolver.unionByHash(null, only)).containsExactlyElementsOf(only);
        assertThat(TargetResolver.unionByHash(null, null)).isEmpty();
        assertThat(TargetResolver.unionByHash(only, List.of())).containsExactlyElementsOf(only);
    }

    @Test
    void matchOverUnionFindsReplacedPodAndOrdersAliveFirst() {
        List<SObjectDto> realtime = List.of(
                new SObjectDto(11, "/shop-order-api-deployment-new-aaa/shop-order-api1", "tomcat", "10.0.0.1", true));
        List<SObjectDto> daily = List.of(
                new SObjectDto(99, "/shop-order-api-deployment-old-zzz/shop-order-api1", "tomcat", "", false),
                new SObjectDto(11, "/shop-order-api-deployment-new-aaa/shop-order-api1", "tomcat", "", false));
        List<SObjectDto> got = TargetResolver.match(TargetResolver.unionByHash(realtime, daily), "shop-order-api");
        assertThat(got).extracting(SObjectDto::objHash).containsExactly(11, 99);
        assertThat(got.get(0).alive()).isTrue();
        assertThat(got.get(1).alive()).isFalse();
    }
}
