package org.example.tokenclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DouyinStreamResolverTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String URL = "https://cdn.example/live.flv?expire=123&sign=a%2Bb";
    private static String stream(String quality) throws Exception {
        return JSON.writeValueAsString(Map.of("live_core_sdk_data", Map.of("pull_data", Map.of("stream_data",
                JSON.writeValueAsString(Map.of("data", Map.of(quality, Map.of("main", Map.of("flv",URL)))))))));
    }
    @Test void supportsRoomNumberAndCanonicalLinkButRejectsUserAndShortLinks() {
        assertEquals("123456", DouyinStreamResolver.roomId(" 123456 "));
        assertEquals("123456", DouyinStreamResolver.roomId("https://live.douyin.com/123456?from=share"));
        for (String v : new String[]{"abc", "https://v.douyin.com/abc/", "https://www.douyin.com/user/123", "https://live.douyin.com.evil/123"})
            assertThrows(IllegalArgumentException.class, () -> DouyinStreamResolver.roomId(v));
    }
    @Test void readsNestedEscapedFlightAndRenderDataWithoutDamagingSignedUrl() throws Exception {
        String room = "{\"roomInfo\":{\"web_rid\":\"123\",\"status\":2,\"stream_url\":" + stream("origin") + "}}";
        String flight = "<script>self.__next_f.push([1," + JSON.writeValueAsString(room) + "])</script>";
        assertEquals(URL, DouyinStreamResolver.fromHtml(flight, "123"));
        String doubleEscaped = JSON.writeValueAsString(JSON.writeValueAsString(room));
        assertEquals(URL, DouyinStreamResolver.fromHtml(doubleEscaped,"123"));
        assertEquals(URL, DouyinStreamResolver.fromHtml("<script id=\"RENDER_DATA\">" + URLEncoder.encode(room,StandardCharsets.UTF_8) + "</script>","123"));
        assertEquals("", DouyinStreamResolver.fromHtml(room,"999"));
        assertThrows(DouyinStreamResolver.Offline.class, () -> DouyinStreamResolver.fromHtml(room.replace("\"status\":2","\"status\":4"),"123"));
        assertEquals("", DouyinStreamResolver.fromHtml("<video src=\"https://ads.example/ad.flv\"></video>","123"));
    }
    @Test void supportsNewPullDatasQualityFallbackAndHls() throws Exception {
        var old = JSON.readTree(stream("sd"));
        var newer = JSON.createObjectNode();
        newer.putObject("pull_datas").set("arbitrary-key", old.path("live_core_sdk_data").path("pull_data"));
        assertEquals(URL, DouyinStreamResolver.streamUrl(newer));
        assertEquals("https://cdn.example/a.m3u8", DouyinStreamResolver.streamUrl(JSON.readTree("{\"hls_pull_url\":\"https://cdn.example/a.m3u8\"}")));
    }
    @Test void signatureMatchesIndependentPythonReferenceVectors() throws Exception {
        try (var in = getClass().getResourceAsStream("/abogus-vectors.json")) {
            for (var v : JSON.readTree(in)) assertEquals(v.path("signature").asText(),
                    AbogusSigner.sign(v.path("query").asText(),v.path("ua").asText(),v.path("timestamp").asLong()));
        }
    }
    @Test void pageCookieIsCarriedIntoSignedFallbackAndHeadersReachSource() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger requests = new AtomicInteger();
        String response = "{\"status_code\":0,\"data\":{\"data\":[{\"status\":2,\"stream_url\":" + stream("origin") + "}]}}";
        server.createContext("/123", e -> {
            e.getResponseHeaders().add("Set-Cookie","ttwid=server-issued; Path=/");
            byte[] bytes = "<html>No stream in page</html>".getBytes();
            e.sendResponseHeaders(200,bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.createContext("/webcast/room/web/enter/", e -> {
            try {
                assertTrue(e.getRequestHeaders().getFirst("Cookie").contains("ttwid=server-issued"));
                assertEquals(DouyinStreamResolver.UA,e.getRequestHeaders().getFirst("User-Agent"));
                String q = e.getRequestURI().getRawQuery();
                assertTrue(q.contains("web_rid=123&msToken=&a_bogus="));
                assertFalse(q.endsWith("a_bogus="));
                requests.incrementAndGet();
                byte[] bytes = response.getBytes(); e.sendResponseHeaders(200,bytes.length); e.getResponseBody().write(bytes);
            } finally { e.close(); }
        });
        server.start();
        try {
            var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
            var resolver = new DouyinStreamResolver(client,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"));
            var source = resolver.resolve("123");
            assertEquals(URL,source.url()); assertEquals(1,requests.get());
            assertEquals(DouyinStreamResolver.UA,source.headers().get("User-Agent"));
            assertEquals("https://live.douyin.com/123",source.headers().get("Referer"));
            assertFalse(source.headers().containsKey("Cookie"), "Room cookies must not leak to an unrelated CDN");
        } finally { server.stop(0); }
    }
}
