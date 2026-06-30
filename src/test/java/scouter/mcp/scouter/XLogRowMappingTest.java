package scouter.mcp.scouter;

import org.junit.jupiter.api.Test;
import scouter.lang.pack.XLogPack;
import scouter.mcp.scouter.dto.XLogRowDto;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class XLogRowMappingTest {

    @Test
    void mapsXLogPackToDtoWithDecodedTextAndIso() {
        XLogPack p = new XLogPack();
        p.txid = 1L;
        p.gxid = 2L;
        p.objHash = 555;
        p.service = 111;       // int hash
        p.elapsed = 1234;      // int ms
        p.error = 0;           // no error
        p.cpu = 56;
        p.sqlCount = 7;
        p.endTime = 1782658800000L; // 2026-06-29T08:00:00+09:00 (Asia/Seoul)

        PackMapper.TextResolver dict = new PackMapper.TextResolver() {
            public String service(long ymd, int hash) {
                return hash == 111 ? "/api/v1/login" : null;
            }

            public String error(long ymd, int hash) {
                return null;
            }

            public String objName(int objHash) {
                return "app-1";
            }
        };

        XLogRowDto row = PackMapper.toXLogRow(p, ZoneId.of("Asia/Seoul"), dict);

        assertThat(row.txid()).isEqualTo(1L);
        assertThat(row.gxid()).isEqualTo(2L);
        assertThat(row.service()).isEqualTo("/api/v1/login");
        assertThat(row.objName()).isEqualTo("app-1");
        assertThat(row.endTimeIso()).startsWith("2026-06-29T");
        assertThat(row.elapsedMs()).isEqualTo(1234);
        assertThat(row.cpuMs()).isEqualTo(56);
        assertThat(row.sqlCount()).isEqualTo(7);
        assertThat(row.error()).isNull();
    }

    @Test
    void unknownHashesRenderAsHashPrefixAndErrorDecoded() {
        XLogPack p = new XLogPack();
        p.txid = 9L;
        p.gxid = 0L;
        p.objHash = 999;
        p.service = 222;
        p.elapsed = 10;
        p.error = 333;          // has error
        p.endTime = 1782658800000L;

        PackMapper.TextResolver dict = new PackMapper.TextResolver() {
            public String service(long ymd, int hash) {
                return null;    // unknown -> "#222"
            }

            public String error(long ymd, int hash) {
                return hash == 333 ? "NullPointerException" : null;
            }

            public String objName(int objHash) {
                return null;    // unknown -> "#999"
            }
        };

        XLogRowDto row = PackMapper.toXLogRow(p, ZoneId.of("Asia/Seoul"), dict);

        assertThat(row.service()).isEqualTo("#222");
        assertThat(row.objName()).isEqualTo("#999");
        assertThat(row.error()).isEqualTo("NullPointerException");
    }
}
