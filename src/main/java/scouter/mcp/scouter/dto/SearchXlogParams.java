package scouter.mcp.scouter.dto;

public record SearchXlogParams(
        long fromMillis, long toMillis, Long objHash, String service,
        Integer minElapsedMs, boolean onlyError, int limit) {
}
