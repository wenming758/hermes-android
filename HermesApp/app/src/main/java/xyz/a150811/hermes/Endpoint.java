package xyz.a150811.hermes;

import org.json.JSONException;
import org.json.JSONObject;

public class Endpoint {
    public static final int KIND_DIRECT = 0;
    public static final int KIND_GATEWAY = 1;

    public static final int STATE_IDLE = 0;
    public static final int STATE_PROBING = 1;
    public static final int STATE_READY = 2;
    public static final int STATE_NEEDS_LOGIN = 3;
    public static final int STATE_FAIL = 4;

    public String id;
    public String name;
    public String url;
    public int kind;
    /** fnOS gateway sub path, e.g. /app/hermes/ ; persisted without tokens. */
    public String path;

    public transient int state = STATE_IDLE;
    public transient long latency = -1;
    public transient String detail = "";
    public transient String resolvedUrl;
    /** Last reachable base, may be an IPv6 literal when DNS is unusable. */
    public String resolvedBase;

    public Endpoint(String id, String name, String url, int kind) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.kind = kind;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("url", url);
        o.put("kind", kind);
        o.put("path", path == null ? "" : path);
        o.put("rb", resolvedBase == null ? "" : resolvedBase);
        return o;
    }

    public static Endpoint fromJson(JSONObject o) {
        Endpoint e = new Endpoint(
                o.optString("id", "e-" + System.currentTimeMillis()),
                o.optString("name", "Hermes"),
                o.optString("url", ""),
                o.optInt("kind", KIND_DIRECT));
        e.path = o.optString("path", "");
        e.resolvedBase = o.optString("rb", "");
        return e;
    }

    public String baseUrl() {
        String s = url == null ? "" : url.trim();
        int scheme = s.indexOf("://");
        int start = scheme >= 0 ? scheme + 3 : 0;
        int slash = s.indexOf('/', start);
        String base = slash > 0 ? s.substring(0, slash) : s;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    public String connectBase() {
        String s = resolvedBase == null ? "" : resolvedBase.trim();
        if (!s.isEmpty()) {
            while (s.endsWith("/")) {
                s = s.substring(0, s.length() - 1);
            }
            return s;
        }
        return baseUrl();
    }

    public String loginUrl() {
        return connectBase() + "/";
    }

    public String launchUrl() {
        if (kind == KIND_GATEWAY) {
            String p = path == null ? "" : path.trim();
            if (p.isEmpty()) return loginUrl();
            if (!p.startsWith("/")) p = "/" + p;
            return connectBase() + p;
        }
        return resolvedUrl != null ? resolvedUrl : url;
    }

    public boolean sameUrl(String other) {
        if (other == null) return false;
        return trimSlash(url).equalsIgnoreCase(trimSlash(other));
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
