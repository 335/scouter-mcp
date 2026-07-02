package scouter.mcp.scouter.dto;

/** One row of the alert daily summary. */
public record AlertSummaryRowDto(String title, String level, long count) {
}
