package xyz.a150811.hermes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * DNS fallback resolver for phones whose system DNS hides answers or serves
 * stale records. Tries system DNS, then DNS-over-HTTPS, then plain UDP DNS,
 * for both AAAA (IPv6) and A (IPv4) lookups.
 */
public final class Doh {
    private static final long TTL_OK = 10 * 60 * 1000L;
    private static final long TTL_FAIL = 60 * 1000L;
    private static final String[] DOH_AAAA = {
            "https://dns.alidns.com/resolve?name=%s&type=AAAA",
            "https://1.1.1.1/dns-query?name=%s&type=AAAA",
            "https://1.0.0.1/dns-query?name=%s&type=AAAA"
    };
    private static final String[] DOH_A = {
            "https://dns.alidns.com/resolve?name=%s&type=A",
            "https://1.1.1.1/dns-query?name=%s&type=A",
            "https://1.0.0.1/dns-query?name=%s&type=A"
    };
    private static final String[] UDP_DNS = {"223.5.5.5", "119.29.29.29", "8.8.8.8"};
    private static final Map<String, Entry> CACHE = new HashMap<>();

    private Doh() {
    }

    private static class Entry {
        String addr;
        long at;
        long ttl;
    }

    public static synchronized String resolveIpv6(String host) {
        return resolve(host, false, true);
    }

    /** Re-resolves via public DNS, ignoring both system DNS and the local cache. */
    public static synchronized String resolveIpv6Fresh(String host) {
        return resolve(host, true, true);
    }

    public static synchronized String resolveIpv4(String host) {
        return resolve(host, false, false);
    }

    /** Re-resolves an A record via public DNS, ignoring system DNS and the cache. */
    public static synchronized String resolveIpv4Fresh(String host) {
        return resolve(host, true, false);
    }

    private static String resolve(String host, boolean preferPublic, boolean v6) {
        if (host == null) return null;
        host = host.trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty()) return null;
        if (host.indexOf(':') >= 0) return v6 ? host : null;

        String key = v6 ? host : host + "|A";
        long now = System.currentTimeMillis();
        if (!preferPublic) {
            Entry c = CACHE.get(key);
            if (c != null && now - c.at < c.ttl) return c.addr;
        }

        String addr;
        if (preferPublic) {
            addr = udp(host, v6);
            if (addr == null) addr = doh(host, v6);
            if (addr == null) addr = system(host, v6);
        } else {
            addr = system(host, v6);
            if (addr == null) addr = udp(host, v6);
            if (addr == null) addr = doh(host, v6);
        }

        Entry e = new Entry();
        e.addr = addr;
        e.at = now;
        e.ttl = addr == null ? TTL_FAIL : TTL_OK;
        CACHE.put(key, e);
        return addr;
    }

    private static String system(String host, boolean v6) {
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            for (InetAddress a : all) {
                String s = usable(a, v6);
                if (s != null) return s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String usable(InetAddress a, boolean v6) {
        if (a == null || a.isLoopbackAddress() || a.isLinkLocalAddress()) return null;
        if (v6) {
            if (!(a instanceof Inet6Address)) return null;
            String s = a.getHostAddress();
            int cut = s.indexOf('%');
            return cut > 0 ? s.substring(0, cut) : s;
        }
        if (!(a instanceof Inet4Address)) return null;
        return a.getHostAddress();
    }

    private static String doh(String host, boolean v6) {
        String q;
        try {
            q = URLEncoder.encode(host, "UTF-8");
        } catch (Exception e) {
            return null;
        }
        String[] templates = v6 ? DOH_AAAA : DOH_A;
        for (String tpl : templates) {
            HttpURLConnection c = null;
            try {
                URL u = new URL(String.format(tpl, q));
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(1800);
                c.setReadTimeout(1800);
                c.setRequestProperty("Accept", "application/dns-json");
                c.setRequestProperty("User-Agent", "HermesApp/1.0");
                if (c.getResponseCode() != 200) continue;
                String body = read(c.getInputStream(), 32 * 1024);
                JSONObject o = new JSONObject(body);
                if (o.optInt("Status", 0) != 0) continue;
                JSONArray ans = o.optJSONArray("Answer");
                if (ans == null) continue;
                for (int i = 0; i < ans.length(); i++) {
                    JSONObject a = ans.optJSONObject(i);
                    if (a == null) continue;
                    String data = a.optString("data", "");
                    if (v6) {
                        if (a.optInt("type") != 28) continue;
                        if (data.contains(":") && data.contains(".")) {
                            data = data.substring(0, data.indexOf('.'));
                        }
                        if (data.contains(":")) return data;
                    } else {
                        if (a.optInt("type") != 1) continue;
                        if (isDottedQuad(data)) return data;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

    private static boolean isDottedQuad(String s) {
        if (s == null) return false;
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            for (int i = 0; i < p.length(); i++) {
                char ch = p.charAt(i);
                if (ch < '0' || ch > '9') return false;
            }
            if (Integer.parseInt(p) > 255) return false;
        }
        return true;
    }

    private static String udp(String host, boolean v6) {
        byte[] query = buildQuery(host, v6 ? 28 : 1);
        if (query == null) return null;
        for (String server : UDP_DNS) {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket();
                sock.setSoTimeout(1800);
                InetAddress to = InetAddress.getByName(server);
                sock.send(new DatagramPacket(query, query.length, to, 53));
                byte[] buf = new byte[1500];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                String addr = parseAnswer(p.getData(), p.getLength(), v6);
                if (addr != null) return addr;
            } catch (Exception ignored) {
            } finally {
                if (sock != null) sock.close();
            }
        }
        return null;
    }

    private static byte[] buildQuery(String host, int qtype) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int id = new Random().nextInt(0xFFFF);
            writeShort(out, id);
            writeShort(out, 0x0100);
            writeShort(out, 1);
            writeShort(out, 0);
            writeShort(out, 0);
            writeShort(out, 0);
            for (String label : host.split("\\.")) {
                byte[] b = label.getBytes(StandardCharsets.US_ASCII);
                if (b.length == 0 || b.length > 63) return null;
                out.write(b.length);
                out.write(b);
            }
            out.write(0);
            writeShort(out, qtype);
            writeShort(out, 1);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static String parseAnswer(byte[] b, int len, boolean v6) {
        if (len < 12) return null;
        int flags = ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        if ((flags & 0x000F) != 0) return null;
        int qd = u16(b, 4);
        int an = u16(b, 6);
        int pos = 12;
        for (int i = 0; i < qd; i++) {
            pos = skipName(b, pos);
            pos += 4;
        }
        for (int i = 0; i < an; i++) {
            pos = skipName(b, pos);
            if (pos + 10 > len) return null;
            int type = u16(b, pos);
            int rdlen = u16(b, pos + 8);
            pos += 10;
            if (pos + rdlen > len) return null;
            int wantType = v6 ? 28 : 1;
            int wantLen = v6 ? 16 : 4;
            if (type == wantType && rdlen == wantLen) {
                byte[] raw = new byte[rdlen];
                System.arraycopy(b, pos, raw, 0, rdlen);
                try {
                    InetAddress a = InetAddress.getByAddress(raw);
                    String s = usable(a, v6);
                    if (s != null) return s;
                } catch (Exception ignored) {
                }
            }
            pos += rdlen;
        }
        return null;
    }

    private static int skipName(byte[] b, int pos) {
        while (pos < b.length) {
            int l = b[pos] & 0xFF;
            if (l == 0) return pos + 1;
            if ((l & 0xC0) == 0xC0) return pos + 2;
            pos += l + 1;
        }
        return pos;
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static String read(InputStream in, int limit) {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            int n;
            int total = 0;
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
