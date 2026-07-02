package scouter.mcp.scouter;

import scouter.lang.pack.XLogPack;
import scouter.lang.step.ApiCallStep;
import scouter.lang.step.HashedMessageStep;
import scouter.lang.step.MessageStep;
import scouter.lang.step.MethodStep;
import scouter.lang.step.SqlStep;
import scouter.lang.step.Step;
import scouter.lang.value.ListValue;
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
     * Min/max downsample a series to at most maxPoints. Returns the input unchanged when already small.
     * The series is divided into maxPoints/2 buckets; each bucket contributes its minimum and maximum
     * point, emitted in chronological order. Unlike bucket averaging this preserves sharp spikes and
     * dips (the most important signal in APM counters) while keeping the point budget bounded.
     */
    public static List<Point> downsample(List<Point> points, int maxPoints) {
        if (points == null || maxPoints <= 0 || points.size() <= maxPoints) {
            return points;
        }
        int buckets = Math.max(1, maxPoints / 2);
        double bucket = (double) points.size() / buckets;
        List<Point> out = new ArrayList<>(maxPoints);
        for (int i = 0; i < buckets; i++) {
            int start = (int) Math.floor(i * bucket);
            int end = (int) Math.floor((i + 1) * bucket);
            if (start >= points.size()) {
                break;
            }
            if (end <= start) {
                end = start + 1;
            }
            end = Math.min(end, points.size());
            Point minP = points.get(start);
            Point maxP = points.get(start);
            for (int j = start + 1; j < end; j++) {
                Point p = points.get(j);
                if (p.value() < minP.value()) {
                    minP = p;
                }
                if (p.value() > maxP.value()) {
                    maxP = p;
                }
            }
            // Emit the two extremes in chronological order to preserve the visual shape.
            Point first = minP.timeMillis() <= maxP.timeMillis() ? minP : maxP;
            Point second = first == minP ? maxP : minP;
            out.add(first);
            if (second != first) {
                out.add(second);
            }
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

        // Resolve method name (TextTypes.METHOD). null if unresolved. (Used only by profiling tools.)
        default String method(long yyyymmdd, int hash) {
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

    // Assembles XLog detail (summary + step list + SQL list + errors).
    // summary: result of mapping the XLOG_READ_BY_TXID response via toXLogRow.
    // steps: flattens the entire profile Step[] into type/name/elapsed.
    // sqls: extracts only SQL-family steps (text resolved via dict.sql, binds wrap the single param string in a list).
    //   If includeBindParams=false, binds is an empty list.
    // errors: collected from the summary error + APICALL error steps (if any).
    public static XLogDetailDto toDetail(XLogRowDto summary, Step[] steps, long yyyymmdd,
                                         boolean includeBindParams, TextResolver dict) {
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
                    String mName = dict.method(yyyymmdd, method.hash);
                    stepDtos.add(new StepDto(stepType(step), mName != null ? mName : "#" + method.hash, method.elapsed));
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

        return new XLogDetailDto(summary, stepDtos, rawSqls, errors);
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
