package scouter.mcp.scouter.dto;

/**
 * objNameLike is a fuzzy target: an app-name fragment as users speak it ("shop-order-api").
 * The client resolves it to concrete objHashes (all matching instances) before querying, because
 * objHash embeds the k8s pod name and changes on every deploy.
 */
public record SearchXlogParams(
        long fromMillis, long toMillis, Long objHash, String objNameLike, String service,
        String login, String ip, String desc,
        Integer minElapsedMs, boolean onlyError, int limit) {
}
