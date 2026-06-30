package scouter.mcp.error;

import java.util.LinkedHashMap;
import java.util.Map;

public final class McpError extends RuntimeException {

    public enum Code {
        SCOUTER_CONNECT_FAILED,
        SCOUTER_AUTH_FAILED,
        SCOUTER_PROTOCOL_MISMATCH,
        INVALID_INPUT,
        NOT_FOUND,
        INTERNAL
    }

    private final Code code;
    private final Map<String, String> hints = new LinkedHashMap<>();

    private McpError(Code code, String message) {
        super(message);
        this.code = code;
    }

    public static McpError of(Code code, String message) {
        return new McpError(code, message);
    }

    public McpError withHint(String key, String value) {
        hints.put(key, value);
        return this;
    }

    public Code code() {
        return code;
    }

    public Map<String, String> hints() {
        return hints;
    }

    public String toLogLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("code=").append(code.name());
        sb.append(" msg=\"").append(getMessage() == null ? "" : getMessage()).append('"');
        for (Map.Entry<String, String> e : hints.entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
