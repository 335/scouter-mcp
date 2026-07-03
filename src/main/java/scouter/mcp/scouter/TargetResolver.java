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
        for (SObjectDto o : objects) {
            String name = o.objName() == null ? "" : o.objName().toLowerCase();
            String type = o.objType() == null ? "" : o.objType().toLowerCase();
            if (name.contains(q) || type.equals(q)) {
                direct.add(o);
            }
        }
        List<SObjectDto> found = direct;
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
