package scouter.mcp.scouter;

import scouter.lang.pack.XLogPack;
import scouter.lang.value.ListValue;
import scouter.mcp.scouter.dto.XLogRowDto;
import scouter.mcp.time.TimeRange;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public final class PackMapper {

    private PackMapper() {
    }

    public record Point(long timeMillis, double value) {
    }

    public static List<Point> toPoints(ListValue time, ListValue value) {
        List<Point> out = new ArrayList<>();
        if (time == null || value == null) {
            return out;
        }
        int n = Math.min(time.size(), value.size());
        for (int i = 0; i < n; i++) {
            out.add(new Point(time.getLong(i), value.getDouble(i)));
        }
        return out;
    }

    // 해시 -> 텍스트 해석기. 텍스트 사전(서비스/에러)과 오브젝트 이름 해석을 주입 가능하게 한다.
    // 알 수 없는 값은 null 반환(호출 측이 "#hash"로 렌더링). 절대 임의 텍스트를 만들지 않는다.
    public interface TextResolver {
        String service(long yyyymmdd, int hash); // unknown 이면 null

        String error(long yyyymmdd, int hash);

        String objName(int objHash);
    }

    public static XLogRowDto toXLogRow(XLogPack p, ZoneId zone, TextResolver dict) {
        long endTimeMillis = p.endTime;
        String endTimeIso = TimeRange.toIso(endTimeMillis, zone);
        long yyyymmdd = Long.parseLong(TimeRange.yyyymmdd(endTimeMillis, zone));

        String serviceText = dict.service(yyyymmdd, p.service);
        String service = serviceText != null ? serviceText : "#" + p.service;

        String error = null;
        if (p.error != 0) {
            String errText = dict.error(yyyymmdd, p.error);
            error = errText != null ? errText : "#" + p.error;
        }

        String objNameText = dict.objName(p.objHash);
        String objName = objNameText != null ? objNameText : "#" + p.objHash;

        return new XLogRowDto(
                p.txid, p.gxid, p.objHash, objName,
                service, p.elapsed, error,
                p.cpu, p.sqlCount, endTimeMillis, endTimeIso);
    }
}
