package scouter.mcp.scouter.dto;

import java.util.List;

/**
 * Result of get_service_summary: aggregated services plus scan-coverage signals.
 * serviceLooksLikeApp / serviceCandidates / timedOut: see {@link XlogSearchResult}.
 */
public record XlogSummaryResult(
        List<ServiceSummaryDto> services, int totalCount, boolean scanCapReached, int examined,
        boolean serviceLooksLikeApp, List<String> serviceCandidates, boolean timedOut) {
}
