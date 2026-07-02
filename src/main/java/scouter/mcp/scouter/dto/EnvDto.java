package scouter.mcp.scouter.dto;

import java.util.Map;

/** JVM system properties of one agent (OBJECT_ENV). Values are raw; masking/truncation is a render concern. */
public record EnvDto(int objHash, String objName, Map<String, String> properties) {
}
