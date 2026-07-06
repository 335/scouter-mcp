package scouter.mcp.scouter;

import scouter.mcp.scouter.dto.SObjectDto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves a fuzzy, user-spoken target ("shop-order-api") to concrete objects. Real objNames embed
 * k8s pod suffixes (e.g. /shop-order-api-deployment-5f4b8c7d9-abcde/shop-order-api1), so objHash
 * changes on every deploy and users can never quote it — they say an app-name fragment instead.
 * Matching: case-insensitive substring on objName, or exact objType; falls back to requiring every
 * whitespace-separated token as a substring. Alive instances are ordered first (dead ones still
 * matter for historical windows, so they are kept, just after).
 */
public final class TargetResolver {

    private TargetResolver() {
    }

    public static List<SObjectDto> match(List<SObjectDto> objects, String query) {
        if (objects == null || objects.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim().toLowerCase();
        List<SObjectDto> direct = new ArrayList<>();
        List<SObjectDto> exactApp = new ArrayList<>();
        for (SObjectDto o : objects) {
            String name = o.objName() == null ? "" : o.objName().toLowerCase();
            String type = o.objType() == null ? "" : o.objType().toLowerCase();
            if (name.contains(q) || type.equals(q)) {
                direct.add(o);
                // Exact-app precedence: when the query is a full app name, a shorter name that is a
                // substring of a longer sibling ("grm-biz-member" vs "grm-biz-membership") would
                // otherwise pull in the sibling's pods and inflate the fan-out. Prefer instances whose
                // derived app label equals the query exactly.
                String app = AppLabel.of(o.objName()).app();
                if (app != null && app.equalsIgnoreCase(q)) {
                    exactApp.add(o);
                }
            }
        }
        // Only narrow to exact-app matches when they don't cover the whole substring set anyway; a
        // partial query ("grm-biz") matches nobody's exact app and must stay broad.
        List<SObjectDto> found = !exactApp.isEmpty() && exactApp.size() < direct.size() ? exactApp : direct;
        if (found.isEmpty()) {
            // Token fallback: every token must appear somewhere in the objName ("order shop" etc.).
            String[] tokens = q.split("\\s+");
            if (tokens.length > 1) {
                for (SObjectDto o : objects) {
                    String name = o.objName() == null ? "" : o.objName().toLowerCase();
                    boolean all = true;
                    for (String t : tokens) {
                        if (!name.contains(t)) {
                            all = false;
                            break;
                        }
                    }
                    if (all) {
                        found.add(o);
                    }
                }
            }
        }
        found.sort((a, b) -> Boolean.compare(b.alive(), a.alive())); // alive first, stable otherwise
        return found;
    }

    /**
     * Merges the real-time object list with historical (daily object DB) lists, deduped by objHash.
     * Real-time entries win on conflict: they carry live alive/address values, while daily entries
     * only exist to keep deploy-replaced pods resolvable for past windows.
     */
    public static List<SObjectDto> unionByHash(List<SObjectDto> realtime, List<SObjectDto> historical) {
        List<SObjectDto> out = new ArrayList<>();
        Set<Integer> seen = new java.util.HashSet<>();
        if (realtime != null) {
            for (SObjectDto o : realtime) {
                if (seen.add(o.objHash())) {
                    out.add(o);
                }
            }
        }
        if (historical != null) {
            for (SObjectDto o : historical) {
                if (seen.add(o.objHash())) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    /**
     * When a fuzzy target resolves to instances that ALL share one objType AND no object of that type
     * lies outside the match, returns that objType; otherwise null. In this deployment each app has its
     * own objType, so this lets a daily-summary query aggregate the whole app in ONE server-side pass
     * (objType) instead of fanning out per instance — far faster and complete (covers rotated pods),
     * which matters for wide or past-date windows. Returns null when the objType is shared across apps
     * (e.g. a generic "tomcat"), where collapsing would over-aggregate, so the caller must fan out.
     */
    public static String soleExclusiveObjType(List<SObjectDto> all, List<SObjectDto> matched) {
        if (matched == null || matched.isEmpty()) {
            return null;
        }
        String type = null;
        Set<Integer> matchedHashes = new java.util.HashSet<>();
        for (SObjectDto o : matched) {
            String ot = o.objType();
            if (ot == null || ot.isBlank()) {
                return null;
            }
            if (type == null) {
                type = ot;
            } else if (!type.equals(ot)) {
                return null; // spans multiple objTypes
            }
            matchedHashes.add(o.objHash());
        }
        if (all != null) {
            for (SObjectDto o : all) {
                if (type.equals(o.objType()) && !matchedHashes.contains(o.objHash())) {
                    return null; // objType has members outside the match -> shared type, not app-exclusive
                }
            }
        }
        return type;
    }

    /** Distinct objNames (bounded) for a "did you mean" hint when nothing matched. */
    public static List<String> suggest(List<SObjectDto> objects, int max) {
        Set<String> names = new LinkedHashSet<>();
        if (objects != null) {
            for (SObjectDto o : objects) {
                if (o.objName() != null) {
                    names.add(o.objName());
                }
                if (names.size() >= max) {
                    break;
                }
            }
        }
        return new ArrayList<>(names);
    }
}
