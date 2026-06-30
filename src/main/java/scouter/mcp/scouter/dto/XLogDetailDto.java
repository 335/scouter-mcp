package scouter.mcp.scouter.dto;

import java.util.List;

public record XLogDetailDto(
        XLogRowDto summary,
        List<StepDto> steps,
        List<SqlStepDto> sqls,
        List<String> errors) {
}
