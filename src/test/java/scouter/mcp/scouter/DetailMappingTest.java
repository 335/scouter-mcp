package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;
import scouter.lang.step.MethodStep;
import scouter.lang.step.Step;
import scouter.mcp.scouter.dto.XLogDetailDto;

import static org.assertj.core.api.Assertions.assertThat;

class DetailMappingTest {

    /** Resolver that only knows a single method hash, to prove MethodStep names are decoded (not left as "#hash"). */
    private static PackMapper.TextResolver methodResolver(int knownHash, String name) {
        return new PackMapper.TextResolver() {
            public String service(long yyyymmdd, int hash) {
                return null;
            }

            public String error(long yyyymmdd, int hash) {
                return null;
            }

            public String objName(int objHash) {
                return null;
            }

            public String method(long yyyymmdd, int hash) {
                return hash == knownHash ? name : null;
            }
        };
    }

    @Test
    void resolvesMethodStepNameViaDictionary() {
        MethodStep m = new MethodStep();
        m.hash = 42;
        m.elapsed = 7;

        XLogDetailDto detail = PackMapper.toDetail(null, new Step[]{m}, 20260701L, false,
                methodResolver(42, "com.foo.Bar.baz()"));

        assertThat(detail.steps()).hasSize(1);
        assertThat(detail.steps().get(0).name()).isEqualTo("com.foo.Bar.baz()");
        assertThat(detail.steps().get(0).elapsedMs()).isEqualTo(7);
    }

    @Test
    void fallsBackToHashWhenMethodUnknown() {
        MethodStep m = new MethodStep();
        m.hash = 99;

        XLogDetailDto detail = PackMapper.toDetail(null, new Step[]{m}, 20260701L, false,
                methodResolver(42, "known"));

        assertThat(detail.steps().get(0).name()).isEqualTo("#99");
    }
}
