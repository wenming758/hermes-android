package xyz.a150811.hermes;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class EndpointStore {
    private static final String PREF = "hermes";
    private static final String KEY = "endpoints";

    private EndpointStore() {
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static List<Endpoint> load(Context c) {
        String raw = prefs(c).getString(KEY, null);
        if (raw == null) {
            return new ArrayList<>();
        }
        List<Endpoint> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                list.add(Endpoint.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        if (!list.isEmpty() && dropLegacy(list)) {
            save(c, list);
        }
        return list;
    }

    private static boolean dropLegacy(List<Endpoint> list) {
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            String id = list.get(i).id;
            if ("lan-gw".equals(id) || "wan-gw".equals(id)
                    || "lan-6060".equals(id) || "wan-6060".equals(id)
                    || "lan-9119".equals(id) || "wan-9119".equals(id)) {
                list.remove(i);
                changed = true;
            }
        }
        return changed;
    }

    /** Adds or refreshes the auto-discovered LAN gateway. Returns true if stored data changed. */
    public static boolean mergeLan(Context c, String base) {
        if (base == null || base.trim().isEmpty()) return false;
        String target = trimSlash(base.trim());
        List<Endpoint> list = load(c);
        for (Endpoint e : list) {
            if (target.equalsIgnoreCase(trimSlash(e.baseUrl()))) return false;
        }
        Endpoint auto = null;
        for (Endpoint e : list) {
            if ("lan-auto".equals(e.id)) {
                auto = e;
                break;
            }
        }
        if (auto != null) {
            auto.url = target + "/";
            auto.kind = Endpoint.KIND_GATEWAY;
            auto.resolvedBase = "";
        } else {
            list.add(new Endpoint("lan-auto", c.getString(R.string.lan_line_name),
                    target + "/", Endpoint.KIND_GATEWAY));
        }
        save(c, list);
        return true;
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static void save(Context c, List<Endpoint> list) {
        JSONArray arr = new JSONArray();
        for (Endpoint e : list) {
            try {
                arr.put(e.toJson());
            } catch (Exception ignored) {
            }
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply();
    }

    public static void upsert(Context c, Endpoint ep) {
        List<Endpoint> list = load(c);
        for (int i = 0; i < list.size(); i++) {
            Endpoint e = list.get(i);
            if (e.id.equals(ep.id) || e.sameUrl(ep.url)) {
                ep.id = e.id;
                list.set(i, ep);
                save(c, list);
                return;
            }
        }
        list.add(ep);
        save(c, list);
    }

    public static void delete(Context c, Endpoint ep) {
        List<Endpoint> list = load(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(ep.id)) {
                list.remove(i);
                break;
            }
        }
        save(c, list);
    }

    public static Endpoint byId(Context c, String id) {
        if (id == null) return null;
        for (Endpoint e : load(c)) {
            if (e.id.equals(id)) return e;
        }
        return null;
    }
}
