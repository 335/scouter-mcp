package scouter.mcp.scouter.dto;

import java.util.List;

/** totalSteps/stepsTruncated are set only when the profile was cut at Limits.DETAIL_MAX_STEPS (null otherwise). */
public record XLogDetailDto(
        XLogRowDto summary,
        List<StepDto> steps,
        List<SqlStepDto> sqls,
        List<String> errors,
        Integer totalSteps,
        Boolean stepsTruncated) {
}
