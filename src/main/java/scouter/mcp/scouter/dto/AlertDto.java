package scouter.mcp.scouter.dto;

/** A single collector alert (from ALERT_LOAD_TIME). objName is resolved from objHash. */
public record AlertDto(
        long time, String timeIso, String level, String objType,
        int objHash, String objName, String title, String message) {
}
