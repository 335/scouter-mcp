package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;
import scouter.mcp.masking.Masker;
import scouter.mcp.scouter.dto.SqlStepDto;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileMaskingTest {

    private static final Masker MASKER = new Masker();

    // Purely verifies the masking transformation over extracted SQL/bind results (a network-independent intermediate representation).
    private static final List<SqlStepDto> RAW = List.of(
            new SqlStepDto(
                    "SELECT * FROM member WHERE card_no = ? AND email = ?",
                    List.of("1234567812345678", "hong@example.com"),
                    42),
            new SqlStepDto(
                    "UPDATE member SET phone = ? WHERE id = ?",
                    List.of("01012345678", "7"),
                    13));

    @Test
    void masksBindParamsWhenMaskSensitiveTrue() {
        List<SqlStepDto> masked = PackMapper.maskSqls(RAW, true, MASKER);

        assertThat(masked).allSatisfy(s -> {
            assertThat(String.join(",", s.bindParams())).doesNotContain("1234567812345678");
            assertThat(String.join(",", s.bindParams())).doesNotContain("hong@example.com");
        });
        // Non-sensitive information such as elapsed time is preserved
        assertThat(masked.get(0).elapsedMs()).isEqualTo(42);
        assertThat(masked.get(1).elapsedMs()).isEqualTo(13);
    }

    // A single SQL containing an inline secret (a literal inside the SQL text, not a bind).
    private static final String INLINE_SECRET_SQL =
            "SELECT * FROM member WHERE card_no = '1234567812345678' AND email = 'hong@example.com'";

    @Test
    void masksSqlTextWhenMaskSensitiveTrue() {
        List<SqlStepDto> withInlineSecret = List.of(new SqlStepDto(INLINE_SECRET_SQL, List.of(), 5));

        List<SqlStepDto> masked = PackMapper.maskSqls(withInlineSecret, true, MASKER);

        assertThat(masked.get(0).sql()).doesNotContain("1234567812345678");
        assertThat(masked.get(0).sql()).doesNotContain("hong@example.com");
    }

    @Test
    void keepsRawWhenMaskSensitiveFalse() {
        List<SqlStepDto> raw = PackMapper.maskSqls(RAW, false, MASKER);

        assertThat(String.join(",", raw.get(0).bindParams())).contains("1234567812345678");
        assertThat(String.join(",", raw.get(0).bindParams())).contains("hong@example.com");
        assertThat(raw.get(0).sql()).isEqualTo("SELECT * FROM member WHERE card_no = ? AND email = ?");
    }

    @Test
    void keepsInlineSqlSecretsRawWhenMaskSensitiveFalse() {
        List<SqlStepDto> withInlineSecret = List.of(new SqlStepDto(INLINE_SECRET_SQL, List.of(), 5));

        List<SqlStepDto> raw = PackMapper.maskSqls(withInlineSecret, false, MASKER);

        assertThat(raw.get(0).sql()).contains("1234567812345678");
        assertThat(raw.get(0).sql()).contains("hong@example.com");
    }
}
