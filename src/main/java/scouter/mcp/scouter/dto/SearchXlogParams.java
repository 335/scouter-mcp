package scouter.mcp.scouter.dto;

public record SearchXlogParams(
        long fromMillis, long toMillis, Long objHash, String service,
        String login, String ip, String desc,
        Integer minElapsedMs, boolean onlyError, int limit) {
}
