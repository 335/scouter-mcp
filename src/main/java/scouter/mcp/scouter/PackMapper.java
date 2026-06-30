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
import scouter.mcp.scouter.Hexa32;
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

    /** Summary statistics for a counter series, computed from the full (pre-downsample) points. */
    public record SeriesStats(int count, double min, double max, double avg, Double first, Double last) {
    }

    public static SeriesStats stats(List<Point> points) {
        if (points == null || points.isEmpty()) {
            return new SeriesStats(0, 0d, 0d, 0d, null, null);
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0d;
        for (Point p : points) {
            min = Math.min(min, p.value());
            max = Math.max(max, p.value());
            sum += p.value();
        }
        return new SeriesStats(points.size(), min, max, sum / points.size(),
                points.get(0).value(), points.get(points.size() - 1).value());
    }

    /**
     * Bucket-average a series down to at most maxPoints. Returns the input unchanged when already small.
     * Each output point uses the bucket's first timestamp and the mean of its values, preserving shape
     * while bounding token usage for high-resolution counters.
     */
    public static List<Point> downsample(List<Point> points, int maxPoints) {
        if (points == null || maxPoints <= 0 || points.size() <= maxPoints) {
            return points;
        }
        List<Point> out = new ArrayList<>(maxPoints);
        double bucket = (double) points.size() / maxPoints;
        for (int i = 0; i < maxPoints; i++) {
            int start = (int) Math.floor(i * bucket);
            int end = (int) Math.floor((i + 1) * bucket);
            if (start >= points.size()) {
                break;
            }
            if (end <= start) {
                end = start + 1;
            }
            end = Math.min(end, points.size());
            double sum = 0d;
            for (int j = start; j < end; j++) {
                sum += points.get(j).value();
            }
            out.add(new Point(points.get(start).timeMillis(), sum / (end - start)));
        }
        return out;
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

    // Hash -> text resolver. Allows injecting the text dictionary (service/error) and object name resolution.
    // Returns null for unknown values (the caller renders them as "#hash"). Never fabricates arbitrary text.
    public interface TextResolver {
        String service(long yyyymmdd, int hash); // null if unknown

        String error(long yyyymmdd, int hash);

        String objName(int objHash);

        // Resolve SQL text (TextTypes.SQL). null if unimplemented/unresolved. (Used only by profiling tools.)
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

        String txidStr = Hexa32.toString32(p.txid);
        // gxidStr is null when gxid==0 (no distributed context); matches what the client shows.
        String gxidStr = p.gxid != 0 ? Hexa32.toString32(p.gxid) : null;

        return new XLogRowDto(
                p.txid, txidStr,
                p.gxid, gxidStr,
                p.objHash, objName,
                service, p.elapsed, error,
                p.cpu, p.sqlCount, endTimeMillis, endTimeIso);
    }

    // Applies masking to SQL text and bind parameters (pure transformation, network-independent -> unit testable).
    // If mask=false, returns the original unchanged.
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

    // Assembles XLog detail (summary + step list + SQL list + errors).
    // summary: result of mapping the XLOG_READ_BY_TXID response via toXLogRow.
    // steps: flattens the entire profile Step[] into type/name/elapsed.
    // sqls: extracts only SQL-family steps (text resolved via dict.sql, binds wrap the single param string in a list).
    //   If includeBindParams=false, binds is an empty list. If maskSensitive=true, masking is applied to SQL/binds.
    // errors: collected from the summary error + APICALL error steps (if any).
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
                    // Unclassified step: use the simple class name as type, name is best-effort (null)
                    stepDtos.add(new StepDto(step.getClass().getSimpleName(), null, 0));
                }
            }
        }

        List<SqlStepDto> sqls = maskSqls(rawSqls, maskSensitive, masker);
        return new XLogDetailDto(summary, stepDtos, sqls, errors);
    }

    // Step type name. Prefers getStepTypeName based on scouter StepEnum, falling back to the class name on failure.
    private static String stepType(Step step) {
        try {
            String name = step.getStepTypeName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (RuntimeException ignore) {
            // Unknown type -> fall back to the simple class name
        }
        return step.getClass().getSimpleName();
    }
}
