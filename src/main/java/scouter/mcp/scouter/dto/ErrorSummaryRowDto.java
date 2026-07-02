package scouter.mcp.scouter.dto;

/** One row of the service-error daily summary. sampleTxid feeds get_xlog_detail for drill-down. */
public record ErrorSummaryRowDto(String error, String service, String message, long count, String sampleTxid) {
}
