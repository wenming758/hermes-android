package xyz.a150811.hermes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * AAAA resolver for phones whose system DNS hides IPv6 answers.
 * Tries system DNS, then DNS-over-HTTPS, then plain UDP DNS.
 */
public final class Doh {
    private static final long TTL_OK = 10 * 60 * 1000L;
    private static final long TTL_FAIL = 60 * 1000L;
    private static final String[] DOH = {
            "https://dns.alidns.com/resolve?name=%s&type=AAAA",
            "https://1.1.1.1/dns-query?name=%s&type=AAAA",
            "https://1.0.0.1/dns-query?name=%s&type=AAAA"
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
        if (host == null) return null;
        host = host.trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty()) return null;
        if (host.indexOf(':') >= 0) return host;

        long now = System.currentTimeMillis();
        Entry c = CACHE.get(host);
        if (c != null && now - c.at < c.ttl) return c.addr;

        String addr = system(host);
        if (addr == null) addr = udp(host);
        if (addr == null) addr = doh(host);

        Entry e = new Entry();
        e.addr = addr;
        e.at = now;
        e.ttl = addr == null ? TTL_FAIL : TTL_OK;
        CACHE.put(host, e);
        return addr;
    }

    private static String system(String host) {
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            for (InetAddress a : all) {
                String s = usable(a);
                if (s != null) return s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String usable(InetAddress a) {
        if (!(a instanceof Inet6Address)) return null;
        if (a.isLoopbackAddress() || a.isLinkLocalAddress()) return null;
        String s = a.getHostAddress();
        int cut = s.indexOf('%');
        return cut > 0 ? s.substring(0, cut) : s;
    }

    private static String doh(String host) {
        String q;
        try {
            q = URLEncoder.encode(host, "UTF-8");
        } catch (Exception e) {
            return null;
        }
        for (String tpl : DOH) {
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
                    if (a == null || a.optInt("type") != 28) continue;
                    String data = a.optString("data", "");
                    if (data.contains(":") && data.contains(".")) {
                        data = data.substring(0, data.indexOf('.'));
                    }
                    if (data.contains(":")) return data;
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

    private static String udp(String host) {
        byte[] query = buildQuery(host);
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
                String addr = parseAnswer(p.getData(), p.getLength());
                if (addr != null) return addr;
            } catch (Exception ignored) {
            } finally {
                if (sock != null) sock.close();
            }
        }
        return null;
    }

    private static byte[] buildQuery(String host) {
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
            writeShort(out, 28);
            writeShort(out, 1);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static String parseAnswer(byte[] b, int len) {
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
            if (type == 28 && rdlen == 16) {
                byte[] raw = new byte[16];
                System.arraycopy(b, pos, raw, 0, 16);
                try {
                    InetAddress a = InetAddress.getByAddress(raw);
                    String s = usable(a);
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
