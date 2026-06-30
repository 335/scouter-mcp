package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;
import scouter.mcp.masking.Masker;
import scouter.mcp.scouter.dto.SqlStepDto;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileMaskingTest {

    private static final Masker MASKER = new Masker();

    // SQL/바인드 추출 결과(네트워크와 무관한 중간 표현)에 대해 마스킹 변환만 순수하게 검증한다.
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
        // 응답시간 등 비민감 정보는 보존
        assertThat(masked.get(0).elapsedMs()).isEqualTo(42);
        assertThat(masked.get(1).elapsedMs()).isEqualTo(13);
    }

    @Test
    void masksSqlTextWhenMaskSensitiveTrue() {
        List<SqlStepDto> withInlineSecret = List.of(
                new SqlStepDto(
                        "SELECT * FROM member WHERE card_no = '1234567812345678'",
                        List.of(),
                        5));

        List<SqlStepDto> masked = PackMapper.maskSqls(withInlineSecret, true, MASKER);

        assertThat(masked.get(0).sql()).doesNotContain("1234567812345678");
    }

    @Test
    void keepsRawWhenMaskSensitiveFalse() {
        List<SqlStepDto> raw = PackMapper.maskSqls(RAW, false, MASKER);

        assertThat(String.join(",", raw.get(0).bindParams())).contains("1234567812345678");
        assertThat(String.join(",", raw.get(0).bindParams())).contains("hong@example.com");
        assertThat(raw.get(0).sql()).isEqualTo("SELECT * FROM member WHERE card_no = ? AND email = ?");
    }
}
