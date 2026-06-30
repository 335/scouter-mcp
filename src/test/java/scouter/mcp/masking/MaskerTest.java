package scouter.mcp.masking;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MaskerTest {

    private final Masker masker = new Masker();

    @Test
    void masksLongDigitSequences() {
        assertThat(masker.mask("card=1234567812345678")).isEqualTo("card=****************");
        assertThat(masker.mask("jumin=9001011234567")).isEqualTo("jumin=*************");
    }

    @Test
    void masksEmail() {
        assertThat(masker.mask("to=hong@example.com")).isEqualTo("to=****@****");
    }

    @Test
    void masksPhone() {
        assertThat(masker.mask("phone=010-1234-5678")).isEqualTo("phone=***-****-****");
    }

    @Test
    void masksSecretKeyedValues() {
        assertThat(masker.mask("password=abcd1234")).contains("password=").doesNotContain("abcd1234");
        assertThat(masker.mask("token : xyz.JWT.tok")).doesNotContain("xyz.JWT.tok");
    }

    @Test
    void keepsShortNumbersAndPlainText() {
        assertThat(masker.mask("rows=42 status=OK")).isEqualTo("rows=42 status=OK");
    }
}
