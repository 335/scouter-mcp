package scouter.mcp.scouter.dto;

import java.util.List;

/** get_summary result: exactly one of rows/errorRows/alertRows is non-null depending on the category. */
public record SummaryResult(String category, int totalRows, boolean truncated,
                            List<SummaryRowDto> rows,
                            List<ErrorSummaryRowDto> errorRows,
                            List<AlertSummaryRowDto> alertRows) {
}
