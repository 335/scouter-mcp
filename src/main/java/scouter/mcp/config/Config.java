package scouter.mcp.config;

import java.time.ZoneId;
import java.util.Map;

public final class Config {

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final ZoneId zone;

    private Config(String host, int port, String user, String password, ZoneId zone) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.zone = zone;
    }

    public static Config fromEnv(Map<String, String> env) {
        String host = require(env, "SCOUTER_COLLECTOR_HOST");
        int port = Integer.parseInt(require(env, "SCOUTER_COLLECTOR_PORT"));
        String user = require(env, "SCOUTER_USER");
        String password = require(env, "SCOUTER_PASSWORD");
        String tz = env.getOrDefault("SCOUTER_TZ", "Asia/Seoul");
        return new Config(host, port, user, password, ZoneId.of(tz));
    }

    private static String require(Map<String, String> env, String key) {
        String v = env.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing required env: " + key);
        }
        return v;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public ZoneId zone() {
        return zone;
    }

    @Override
    public String toString() {
        return "Config{host=" + host + ", port=" + port + ", user=" + user + ", password=***, zone=" + zone + "}";
    }
}
