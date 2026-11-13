package org.example.tokenclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** A numeric input is the public live.douyin.com web_rid, not a user ID. */
final class DouyinStreamResolver {
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.97 Safari/537.36 Core/1.116.567.400 QQBrowser/19.7.6764.400";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private final HttpClient http;
    private final URI base;

    record Source(String url, Map<String, String> headers) {
        Source { headers = Map.copyOf(headers); }
        @Override public String toString() { return "Source[URL redacted]"; }
    }
    DouyinStreamResolver() {
        this(HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build(),
                URI.create("https://live.douyin.com/"));
    }
    DouyinStreamResolver(HttpClient http, URI base) { this.http = http; this.base = base; }

    static String roomId(String input) {
        String value = input.strip();
        if (value.matches("[0-9]{1,30}")) return value;
        try {
            URI uri = URI.create(value);
            if ("live.douyin.com".equalsIgnoreCase(uri.getHost()) && uri.getRawUserInfo() == null
                    && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                String path = uri.getPath().replaceAll("^/|/$", "");
                if (path.matches("[0-9]{1,30}")) return path;
            }
        } catch (IllegalArgumentException ignored) { }
        throw new IllegalArgumentException("请输入抖音直播房间号，或 https://live.douyin.com/房间号；不支持个人抖音号和短链接。");
    }

    Source resolve(String input) throws IOException, InterruptedException {
        try { return resolveRoom(input); }
        catch (Offline e) { throw e; }
        catch (IOException e) { throw new Failure(e.getMessage()); }
    }

    private Source resolveRoom(String input) throws IOException, InterruptedException {
        String id = roomId(input);
        URI page = base.resolve(id);
        Map<String, String> headers = Map.of("User-Agent", UA, "Referer", "https://live.douyin.com/" + id);
        String pageError;
        try {
            String html = get(page, headers);
            String stream = fromHtml(html, id);
            if (!stream.isEmpty()) return new Source(stream, headers);
            pageError = "页面未包含可用直播流";
        } catch (Offline e) { throw e;
        } catch (IOException e) { pageError = e.getMessage(); }
        String query = "aid=6383&app_name=douyin_web&live_id=1&device_platform=web&language=zh-CN"
                + "&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome&browser_version=116.0.0.0"
                + "&web_rid=" + id + "&msToken=";
        String signature = URLEncoder.encode(AbogusSigner.sign(query, UA, System.currentTimeMillis()), StandardCharsets.UTF_8);
        try {
            String body = get(base.resolve("webcast/room/web/enter/?" + query + "&a_bogus=" + signature), headers);
            JsonNode response;
            try { response = JSON.readTree(body); }
            catch (IOException e) { throw new IOException("抖音接口返回非 JSON，可能需要在浏览器完成验证"); }
            if (response == null || response.path("status_code").asInt(-1) != 0)
                throw new IOException("抖音接口拒绝请求，请检查房间号或稍后重试");
            JsonNode rooms = response.path("data").path("data");
            if (!rooms.isArray() || rooms.isEmpty()) throw new IOException("未找到直播间，请检查直播网页房间号");
            JsonNode room = rooms.get(0);
            if (room.path("status").asInt() == 4) throw new Offline();
            if (room.path("status").asInt() != 2) throw new IOException("直播间状态未知，无法开始扫描");
            String stream = streamUrl(room.path("stream_url"));
            if (stream.isEmpty()) throw new IOException("抖音接口未提供可用视频流");
            return new Source(stream, headers);
        } catch (Offline e) { throw e;
        } catch (IOException e) { throw new IOException(pageError + "；" + e.getMessage()); }
    }

    private String get(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).GET();
        headers.forEach(request::header);
        HttpResponse<String> response;
        try { response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch (IOException e) { throw new IOException("抖音请求失败或超时，请检查网络"); }
        if (response.statusCode() != 200) throw new IOException("抖音请求 HTTP " + response.statusCode());
        String body = response.body();
        if (body.isBlank()) throw new IOException("抖音返回空响应，可能需要验证或接口签名已变化");
        if (body.length() > 8_000_000) throw new IOException("抖音页面内容过大");
        return body;
    }

    static String fromHtml(String html, String id) throws IOException {
        if (html.length() > 8_000_000) throw new IOException("抖音页面内容过大");
        List<String> candidates = new ArrayList<>(); candidates.add(html);
        var render = Pattern.compile("(?is)<script[^>]*id=[\"']RENDER_DATA[\"'][^>]*>(.*?)</script>").matcher(html);
        while (render.find()) {
            try { candidates.add(URLDecoder.decode(render.group(1), StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException ignored) { }
        }
        // Decode JS/JSON string literals structurally, retaining escaped URL query characters.
        for (int depth = 0, start = 0; depth < 4; depth++) {
            int end = candidates.size();
            for (int n = start; n < end; n++) {
                String text = candidates.get(n);
                boolean scopedRoom = false;
                var rooms = Pattern.compile("\"(?:room|roomInfo)\"\\s*:\\s*(\\{)").matcher(text);
                while (rooms.find()) {
                    JsonNode room = objectAt(text, rooms.start(1));
                    if (room == null || !room.has("stream_url")) continue;
                    scopedRoom = true;
                    String rid = room.path("web_rid").asText("");
                    if (!rid.isEmpty() && !rid.equals(id)) continue;
                    if (room.path("status").asInt() == 4) throw new Offline();
                    String url = streamUrl(room.path("stream_url"));
                    if (!url.isEmpty()) return url;
                }
                var streams = Pattern.compile("\"stream_url\"\\s*:\\s*(\\{)").matcher(text);
                // Only accept an unscoped stream when there is a single stream object on the page.
                if (!scopedRoom && streams.find()) {
                    int at = streams.start(1);
                    if (!streams.find()) {
                        String url = streamUrl(objectAt(text, at));
                        if (!url.isEmpty()) return url;
                    }
                }
                var strings = STRING.matcher(text);
                while (strings.find() && candidates.size() < 64) {
                    String literal = strings.group();
                    if (!literal.contains("stream_url")) continue;
                    try {
                        JsonNode node = JSON.readTree(literal);
                        if (node.isTextual() && !node.asText().equals(text)) candidates.add(node.asText());
                    } catch (IOException ignored) { }
                }
            }
            start = end;
        }
        return "";
    }
    private static JsonNode objectAt(String text, int at) {
        try (var parser = JSON.createParser(text.substring(at))) { return JSON.readTree(parser); }
        catch (IOException e) { return null; }
    }
    static String streamUrl(JsonNode stream) {
        if (stream == null || !stream.isObject()) return "";
        List<String> flv = new ArrayList<>(), hls = new ArrayList<>();
        collect(stream, flv, hls, 0);
        return !flv.isEmpty() ? flv.getFirst() : hls.isEmpty() ? "" : hls.getFirst();
    }
    private static void collect(JsonNode node, List<String> flv, List<String> hls, int depth) {
        if (node == null || depth > 16) return;
        if (node.isTextual()) {
            String s = node.asText();
            if (s.startsWith("{")) try { collect(JSON.readTree(s), flv, hls, depth + 1); } catch (IOException ignored) { }
        } else if (node.isObject()) {
            // Visit quality variants deterministically, with original quality first.
            Set<String> keys = new LinkedHashSet<>(List.of("origin", "uhd", "hd", "sd", "main"));
            node.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode v = node.get(key); if (v == null) continue;
                if (v.isTextual() && validUrl(v.asText())) {
                    if (key.equals("flv") || key.equals("flv_pull_url") || v.asText().contains(".flv")) flv.add(v.asText());
                    else if (key.equals("hls") || key.equals("hls_pull_url") || v.asText().contains(".m3u8")) hls.add(v.asText());
                } else collect(v, flv, hls, depth + 1);
            }
        } else if (node.isArray()) for (JsonNode v : node) collect(v, flv, hls, depth + 1);
    }
    private static boolean validUrl(String value) {
        try { URI u = URI.create(value); return ("https".equals(u.getScheme()) || "http".equals(u.getScheme()))
                && u.getHost() != null && u.getRawUserInfo() == null; }
        catch (IllegalArgumentException e) { return false; }
    }
    static final class Offline extends IOException { Offline() { super("主播未开播，请开播后重试"); } }
    static final class Failure extends IOException { Failure(String message) { super(message); } }
}
