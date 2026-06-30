package scouter.mcp.scouter;

import scouter.lang.pack.XLogPack;
import scouter.lang.step.ApiCallStep;
import scouter.lang.step.HashedMessageStep;
import scouter.lang.step.MessageStep;
import scouter.lang.step.MethodStep;
import scouter.lang.step.SqlStep;
import scouter.lang.step.Step;
import scouter.lang.value.ListValue;
import scouter.mcp.masking.Masker;
import scouter.mcp.scouter.dto.SqlStepDto;
import scouter.mcp.scouter.dto.StepDto;
import scouter.mcp.scouter.dto.XLogDetailDto;
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

        // SQL 텍스트(TextTypes.SQL) 해석. 미구현/미해석이면 null. (프로파일 도구에서만 사용)
        default String sql(long yyyymmdd, int hash) {
            return null;
        }
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

    // SQL 텍스트와 바인드 파라미터에 마스킹을 적용한다(순수 변환, 네트워크 무관 → 단위 테스트 대상).
    // mask=false면 원본 그대로 반환한다.
    public static List<SqlStepDto> maskSqls(List<SqlStepDto> raw, boolean mask, Masker masker) {
        if (raw == null) {
            return new ArrayList<>();
        }
        if (!mask) {
            return new ArrayList<>(raw);
        }
        List<SqlStepDto> out = new ArrayList<>(raw.size());
        for (SqlStepDto s : raw) {
            String maskedSql = masker.mask(s.sql());
            List<String> maskedBinds = new ArrayList<>(s.bindParams().size());
            for (String b : s.bindParams()) {
                maskedBinds.add(masker.mask(b));
            }
            out.add(new SqlStepDto(maskedSql, maskedBinds, s.elapsedMs()));
        }
        return out;
    }

    // XLog 상세(요약 + 스텝 목록 + SQL 목록 + 에러)를 조립한다.
    // summary: XLOG_READ_BY_TXID 응답을 toXLogRow로 매핑한 결과.
    // steps: 프로파일 Step[] 전체를 type/name/elapsed로 평탄화.
    // sqls: SQL 계열 스텝만 추출(텍스트는 dict.sql로 해석, 바인드는 param 문자열 1건을 리스트로 래핑).
    //   includeBindParams=false면 바인드는 빈 리스트. maskSensitive=true면 SQL/바인드에 마스킹 적용.
    // errors: summary 에러 + APICALL 에러 스텝(있으면)에서 수집.
    public static XLogDetailDto toDetail(XLogRowDto summary, Step[] steps, long yyyymmdd,
                                         boolean includeBindParams, boolean maskSensitive,
                                         Masker masker, TextResolver dict) {
        List<StepDto> stepDtos = new ArrayList<>();
        List<SqlStepDto> rawSqls = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (summary != null && summary.error() != null) {
            errors.add(summary.error());
        }

        if (steps != null) {
            for (Step step : steps) {
                if (step == null) {
                    continue;
                }
                if (step instanceof SqlStep sql) {
                    String text = dict.sql(yyyymmdd, sql.hash);
                    String sqlText = text != null ? text : "#" + sql.hash;
                    List<String> binds = new ArrayList<>();
                    if (includeBindParams && sql.param != null && !sql.param.isEmpty()) {
                        binds.add(sql.param);
                    }
                    rawSqls.add(new SqlStepDto(sqlText, binds, sql.elapsed));
                    stepDtos.add(new StepDto(stepType(step), sqlText, sql.elapsed));
                } else if (step instanceof ApiCallStep api) {
                    if (api.error != 0) {
                        String e = dict.error(yyyymmdd, api.error);
                        errors.add(e != null ? e : "#" + api.error);
                    }
                    String name = api.address != null ? api.address : "#" + api.hash;
                    stepDtos.add(new StepDto(stepType(step), name, api.elapsed));
                } else if (step instanceof MethodStep method) {
                    stepDtos.add(new StepDto(stepType(step), "#" + method.hash, method.elapsed));
                } else if (step instanceof MessageStep msg) {
                    stepDtos.add(new StepDto(stepType(step), msg.message, 0));
                } else if (step instanceof HashedMessageStep hmsg) {
                    stepDtos.add(new StepDto(stepType(step), "#" + hmsg.hash, hmsg.time));
                } else {
                    // 미분류 스텝: 클래스 단순명을 type으로, name은 best-effort(null)
                    stepDtos.add(new StepDto(step.getClass().getSimpleName(), null, 0));
                }
            }
        }

        List<SqlStepDto> sqls = maskSqls(rawSqls, maskSensitive, masker);
        return new XLogDetailDto(summary, stepDtos, sqls, errors);
    }

    // 스텝 타입 이름. scouter StepEnum 기반의 getStepTypeName을 우선 사용하고, 실패 시 클래스명으로 폴백한다.
    private static String stepType(Step step) {
        try {
            String name = step.getStepTypeName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (RuntimeException ignore) {
            // 알 수 없는 타입 → 클래스 단순명 폴백
        }
        return step.getClass().getSimpleName();
    }
}
