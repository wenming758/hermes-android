package xyz.a150811.hermes;

import android.net.Uri;
import android.os.SystemClock;
import android.webkit.CookieManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Probes saved endpoints and discovers the fnOS gateway path of Hermes. */
public final class Probe {

    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 HermesApp/1.0";

    private static final String[] GATEWAY_PATHS = {
            "/app/hermes/",
            "/app/hermes-agent/",
            "/app/hermesagent/",
            "/app/hermes_agent/",
            "/app/hermes-webui/",
            "/app/hermes-agent-webui/",
            "/app/Hermes/",
            "/app/HermesAgent/"
    };

    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private Probe() {
    }

    public static class Result {
        public int state = Endpoint.STATE_FAIL;
        public long ms = -1;
        public String url;
        public String base;
        public String detail = "";
    }

    public static Result run(Endpoint ep) {
        if (ep.kind == Endpoint.KIND_GATEWAY) {
            return probeGateway(ep);
        }
        return probeDirect(ep);
    }

    private static Result probeDirect(Endpoint ep) {
        Result r = new Result();
        String[] baseOut = new String[1];
        // Mobile IPv6 blips (idle radio, ND not settled, resolver still serving the
        // previous AAAA) look exactly like a closed port for one attempt, so retry:
        // one immediate round, then one after a short pause, within a hard deadline.
        long deadline = SystemClock.elapsedRealtime() + 9000;
        Http h = fetchSmart(ep.url, 2500, 3000, baseOut);
        for (int round = 1; h.error != null && round < 3; round++) {
            if (SystemClock.elapsedRealtime() >= deadline) break;
            if (round == 2) {
                try {
                    Thread.sleep(900);
                } catch (InterruptedException ie) {
                    break;
                }
            }
            h = fetchSmart(ep.url, 2500, 3000, baseOut);
        }
        r.base = baseOut[0];
        if (h.error != null) {
            r.detail = h.error;
            return r;
        }
        r.ms = h.ms;
        r.url = h.url;
        if (isHermes(h)) {
            r.state = Endpoint.STATE_READY;
        } else if (isInvalidToken(h) || h.code == 401 || h.code == 403) {
            r.state = Endpoint.STATE_NEEDS_LOGIN;
            r.detail = "需要登录";
        } else {
            r.detail = "不是 Hermes 页面";
        }
        return r;
    }

    private static Result probeGateway(Endpoint ep) {
        Result r = new Result();
        String[] baseOut = new String[1];
        Http root = fetchSmart(ep.url, 2500, 3000, baseOut);
        String connectBase = baseOut[0] != null ? baseOut[0] : ep.baseUrl();
        r.base = connectBase;
        if (root.error != null) {
            r.detail = root.error;
            return r;
        }
        if (isHermes(root)) {
            r.state = Endpoint.STATE_READY;
            r.url = root.url;
            r.ms = root.ms;
            return r;
        }

        Result login = null;
        String saved = ep.path == null ? "" : ep.path.trim();
        if (!saved.isEmpty() && !saved.equals("/")) {
            if (!saved.startsWith("/")) saved = "/" + saved;
            String u = connectBase + saved;
            Http h = fetch(u, 2500, 3000);
            if (h.error == null) {
                if (isHermes(h)) {
                    r.state = Endpoint.STATE_READY;
                    r.url = u;
                    r.ms = h.ms;
                    return r;
                }
                if (isInvalidToken(h) || h.code == 401 || h.code == 403) {
                    login = new Result();
                    login.ms = h.ms;
                    login.url = u;
                    login.detail = "登录飞牛后可直达";
                }
            }
        }

        int timeouts = 0;
        for (String p : GATEWAY_PATHS) {
            if (p.equals(saved)) continue;
            String u = connectBase + p;
            Http h = fetch(u, 2000, 2500);
            if (h.error != null) {
                if (++timeouts >= 2) break;
                continue;
            }
            if (isHermes(h)) {
                r.state = Endpoint.STATE_READY;
                r.url = u;
                r.ms = h.ms;
                return r;
            }
            if (isInvalidToken(h) || isFnos(h) || h.code == 401 || h.code == 403) {
                if (login == null) {
                    login = new Result();
                    login.ms = h.ms;
                    login.url = u;
                    login.detail = "登录飞牛后可直达";
                }
            }
        }
        if (login != null) {
            login.state = Endpoint.STATE_NEEDS_LOGIN;
            return login;
        }
        if (isFnos(root)) {
            r.state = Endpoint.STATE_NEEDS_LOGIN;
            r.url = connectBase + "/";
            r.ms = root.ms;
            r.detail = "登录飞牛后捕获 Hermes 地址";
            return r;
        }
        r.detail = "没有找到 Hermes 入口";
        return r;
    }

    /** Path portion (with query) of an absolute URL, e.g. "/app/hermes/". */
    public static String pathOf(String url) {
        if (url == null) return null;
        try {
            Uri u = Uri.parse(url);
            String p = u.getEncodedPath();
            if (p == null || p.isEmpty()) p = "/";
            String q = u.getEncodedQuery();
            if (q != null && !q.isEmpty()) p = p + "?" + q;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /** Scans the local /24 subnets for fnOS gateways on port 5666. */
    public static List<String> scanLan() {
        List<String> found = new ArrayList<>();
        Set<String> prefixes = localPrefixes();
        if (prefixes.isEmpty()) return found;
        Set<String> own = localIpv4();

        ExecutorService pool = Executors.newFixedThreadPool(48);
        List<Future<String>> tasks = new ArrayList<>();
        for (String prefix : prefixes) {
            for (int i = 1; i <= 254; i++) {
                final String ip = prefix + "." + i;
                if (own.contains(ip)) continue;
                tasks.add(pool.submit(() -> {
                    if (!portOpen(ip, 5666, 420)) return null;
                    Http h = fetch("http://" + ip + ":5666/", 1200, 2000);
                    if (h.error == null && (isFnos(h) || isHermes(h))) {
                        return "http://" + ip + ":5666";
                    }
                    return null;
                }));
            }
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(14, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        for (Future<String> f : tasks) {
            try {
                String base = f.get(0, TimeUnit.MILLISECONDS);
                if (base != null && !found.contains(base)) found.add(base);
            } catch (Exception ignored) {
            }
        }
        Collections.sort(found);
        return found;
    }

    private static boolean portOpen(String ip, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static Set<String> localPrefixes() {
        Set<String> out = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("tun") || name.startsWith("ppp")
                        || name.startsWith("rmnet") || name.startsWith("dummy")
                        || name.startsWith("ap") || name.startsWith("swlan")
                        || name.startsWith("p2p") || name.startsWith("clat")
                        || name.startsWith("v4-")) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()
                            || a.isLinkLocalAddress()) {
                        continue;
                    }
                    byte[] b = a.getAddress();
                    out.add((b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF));
                    if (out.size() >= 2) return out;
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static Set<String> localIpv4() {
        Set<String> out = new HashSet<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Scheme + host + port of a URL, e.g. "http://[240e::1]:5666". */
    private static String baseOf(String raw) {
        if (raw == null) return null;
        try {
            Uri u = Uri.parse(raw);
            String scheme = u.getScheme();
            String host = u.getHost();
            if (scheme == null || host == null) return null;
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            if (host.indexOf(':') >= 0) host = "[" + host + "]";
            int port = u.getPort();
            return scheme + "://" + host + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fetches a URL. When the hostname cannot be resolved, or the returned
     * address no longer answers (stale record after a prefix change),
     * re-resolves via public DNS and retries against the literal — AAAA
     * first, then A — so IPv6-only and IPv4-only domains both recover.
     * The literal base is returned through baseOut[0].
     */
    private static Http fetchSmart(String url, int connectMs, int readMs, String[] baseOut) {
        if (baseOut != null) baseOut[0] = baseOf(url);
        Http h = fetch(url, connectMs, readMs);
        if (h.error == null || baseOut == null) return h;

        Uri u = Uri.parse(url);
        String host = u.getHost();
        String scheme = u.getScheme();
        if (host == null || scheme == null) return h;
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.indexOf(':') >= 0) {
            if (!hasGlobalIpv6()) h.error = "当前网络无 IPv6";
            return h;
        }
        if (host.matches("(\\d{1,3}\\.){3}\\d{1,3}")) {
            return h;
        }

        String path = u.getEncodedPath();
        if (path == null || path.isEmpty()) path = "/";
        String query = u.getEncodedQuery();
        if (query != null && !query.isEmpty()) path += "?" + query;
        int port = u.getPort();

        boolean dnsFailed = "域名解析失败".equals(h.error);
        String a6 = dnsFailed ? Doh.resolveIpv6(host) : Doh.resolveIpv6Fresh(host);
        if (a6 != null) {
            Http h6 = fetchLiteral(scheme, a6, port, path, connectMs, readMs, baseOut);
            if (h6.error == null) return h6;
            h = h6;
        }
        String a4 = dnsFailed ? Doh.resolveIpv4(host) : Doh.resolveIpv4Fresh(host);
        if (a4 != null) {
            Http h4 = fetchLiteral(scheme, a4, port, path, connectMs, readMs, baseOut);
            if (h4.error == null) return h4;
            if (a6 == null) h = h4;
        }
        if (dnsFailed && a6 == null && a4 == null) {
            h.error = "解析不到可用地址";
        }
        return h;
    }

    private static Http fetchLiteral(String scheme, String addr, int port, String path,
                                     int connectMs, int readMs, String[] baseOut) {
        boolean v6 = addr.indexOf(':') >= 0;
        String base = scheme + "://" + (v6 ? "[" + addr + "]" : addr)
                + (port > 0 ? ":" + port : "");
        Http r = fetch(base + path, connectMs, readMs);
        if (r.error == null && baseOut != null) {
            baseOut[0] = base;
        }
        return r;
    }

    private static boolean isHermes(Http h) {
        if (h == null || h.code < 200 || h.code >= 400) return false;
        String body = lower(h.body);
        if (body.contains("invalid token")) return false;
        if (isFnos(h)) return false;
        return body.contains("hermes");
    }

    private static boolean isInvalidToken(Http h) {
        return h != null && lower(h.body).contains("invalid token");
    }

    private static boolean isFnos(Http h) {
        if (h == null || h.body == null) return false;
        Matcher m = TITLE.matcher(h.body);
        if (!m.find()) return false;
        String t = m.group(1).toLowerCase(Locale.ROOT);
        return t.contains("飞牛") || t.contains("fnos");
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static class Http {
        int code;
        String body = "";
        long ms;
        String url = "";
        String error;
    }

    public static boolean hasGlobalIpv6() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet6Address
                            && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean onlyIpv6(String url) {
        try {
            URL u = new URL(url);
            InetAddress[] all = InetAddress.getAllByName(u.getHost());
            if (all.length == 0) return false;
            for (InetAddress a : all) {
                if (!(a instanceof Inet6Address)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Http fetch(String url, int connectMs, int readMs) {
        Http h = new Http();
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            long t0 = SystemClock.elapsedRealtime();
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(false);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6");
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = read(in, 24 * 1024);
            h.ms = SystemClock.elapsedRealtime() - t0;
            h.code = code;
            h.body = body;
            h.url = conn.getURL().toString();
            return h;
        } catch (UnknownHostException e) {
            h.error = "域名解析失败";
        } catch (SocketTimeoutException e) {
            h.error = "连接超时";
        } catch (ConnectException e) {
            h.error = "端口未开放";
        } catch (Exception e) {
            h.error = "网络不可达";
        } finally {
            if (conn != null) conn.disconnect();
        }
        if (onlyIpv6(url) && !hasGlobalIpv6()) {
            h.error = "当前网络无 IPv6";
        }
        return h;
    }

    private static String read(InputStream in, int limit) {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                int keep = Math.min(n, limit - total);
                if (keep > 0) out.write(buf, 0, keep);
                total += n;
                if (total >= limit) break;
            }
        } catch (Exception ignored) {
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
