package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppLabelTest {

    @Test
    void stripsK8sReplicaSetAndPodHashes() {
        // /<deployment>-deployment-<rs-hash>-<pod-suffix>/<agent> : the hashes identify nothing to a
        // human and change every deploy — the app identity is the leading words.
        var l = AppLabel.of("/shop-login-blue-deployment-5f4b8c7d9-abcde/shop-login1");
        assertThat(l.app()).isEqualTo("shop-login-blue");
        assertThat(l.instance()).isEqualTo("5f4b8c7d9-abcde");
    }

    @Test
    void stripsSingleTrailingHashToken() {
        var l = AppLabel.of("/shop-cart-api-deployment-55bb4c7d9/shop-cart-api1");
        assertThat(l.app()).isEqualTo("shop-cart-api");
        assertThat(l.instance()).isEqualTo("55bb4c7d9");
    }

    @Test
    void stripsStatefulSetOrdinal() {
        var l = AppLabel.of("/redis-main-0/redis-main");
        assertThat(l.app()).isEqualTo("redis-main");
        assertThat(l.instance()).isEqualTo("0");
    }

    @Test
    void plainHostNameIsKeptWhole() {
        var l = AppLabel.of("/devwas01/app1");
        assertThat(l.app()).isEqualTo("devwas01");
        assertThat(l.instance()).isNull();
    }

    @Test
    void nullAndBlankAreSafe() {
        assertThat(AppLabel.of(null).app()).isNull();
        assertThat(AppLabel.of("  ").app()).isNull();
    }

    @Test
    void wordsWithoutDigitsAreNeverStripped() {
        // An app whose name ends in a short word must not lose it ("-super" is identity, not a hash).
        var l = AppLabel.of("/shop-point-super/shop-point-super1");
        assertThat(l.app()).isEqualTo("shop-point-super");
    }
}
