package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;
import scouter.lang.step.MethodStep;
import scouter.lang.step.SqlStep;
import scouter.lang.step.Step;
import scouter.mcp.policy.Limits;
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
        assertThat(detail.stepsTruncated()).isNull(); // not truncated -> omitted from JSON
        assertThat(detail.totalSteps()).isNull();
    }

    @Test
    void truncatesOversizedSqlTextAndCapsSteps() {
        // Oversized SQL text gets a truncation marker; steps beyond DETAIL_MAX_STEPS are dropped
        // with stepsTruncated=true and totalSteps carrying the original count.
        SqlStep sql = new SqlStep();
        sql.hash = 7;
        sql.elapsed = 5;
        Step[] steps = new Step[Limits.DETAIL_MAX_STEPS + 10];
        for (int i = 0; i < steps.length; i++) {
            MethodStep m = new MethodStep();
            m.hash = 1;
            steps[i] = m;
        }
        steps[0] = sql;
        String longSql = "SELECT ".repeat(1000); // 7000 chars
        PackMapper.TextResolver dict = new PackMapper.TextResolver() {
            public String service(long yyyymmdd, int hash) {
                return null;
            }

            public String error(long yyyymmdd, int hash) {
                return null;
            }

            public String objName(int objHash) {
                return null;
            }

            public String sql(long yyyymmdd, int hash) {
                return longSql;
            }

            public String method(long yyyymmdd, int hash) {
                return "m";
            }
        };

        XLogDetailDto d = PackMapper.toDetail(null, steps, 20260703L, true, dict);

        assertThat(d.steps()).hasSize(Limits.DETAIL_MAX_STEPS);
        assertThat(d.stepsTruncated()).isTrue();
        assertThat(d.totalSteps()).isEqualTo(steps.length);
        assertThat(d.sqls().get(0).sql()).contains("truncated");
        assertThat(d.sqls().get(0).sql().length()).isLessThan(longSql.length());
    }
}
