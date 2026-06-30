package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hexa32Test {

    @Test
    void roundTripNegative() {
        long txid = -4926643480458686894L;
        String s = Hexa32.toString32(txid);
        assertThat(s).startsWith("z");
        assertThat(Hexa32.toLong(s)).isEqualTo(txid);
    }

    @Test
    void roundTripPositive() {
        long txid = 7283809205160911224L;
        String s = Hexa32.toString32(txid);
        assertThat(s).startsWith("+");
        assertThat(Hexa32.toLong(s)).isEqualTo(txid);
    }

    @Test
    void smallPositiveIsPlainDecimal() {
        assertThat(Hexa32.toString32(7)).isEqualTo("7");
        assertThat(Hexa32.toLong("7")).isEqualTo(7L);
    }

    @Test
    void minValueSpecialCase() {
        assertThat(Hexa32.toString32(Long.MIN_VALUE)).isEqualTo("z8000000000000");
        assertThat(Hexa32.toLong("z8000000000000")).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void acceptsRawDecimalString() {
        // Plain decimal long strings (as stored by the MCP) also parse correctly.
        assertThat(Hexa32.toLong("-4926643480458686894")).isEqualTo(-4926643480458686894L);
        assertThat(Hexa32.toLong("7283809205160911224")).isEqualTo(7283809205160911224L);
    }

    @Test
    void isHexa32DetectsPrefix() {
        assertThat(Hexa32.isHexa32("z3st744n3d2p6q")).isTrue();
        assertThat(Hexa32.isHexa32("+6a5a9s5ulc8bo")).isTrue();
        assertThat(Hexa32.isHexa32("12345")).isFalse();
        assertThat(Hexa32.isHexa32(null)).isFalse();
    }

    @Test
    void emptyStringThrows() {
        assertThatThrownBy(() -> Hexa32.toLong("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Hexa32.toLong(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
