package scouter.mcp.scouter.dto;

import java.util.List;

public record SqlStepDto(String sql, List<String> bindParams, int elapsedMs) {
}
