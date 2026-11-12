package org.example.tokenclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** One short-lived, loopback-only challenge. No phone number or account token is served. */
final class LoopbackCaptcha implements AutoCloseable {
    static final String SDK = "https://static.geetest.com/v4/gt4.js";
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicReference<String> proof = new AtomicReference<>();
    private final AtomicReference<String> status = new AtomicReference<>("loading");
    private final AtomicBoolean closed = new AtomicBoolean();
    private final URI page;
    private final String prefix = "/" + UUID.randomUUID() + "/";
    private final String html;

    LoopbackCaptcha() throws IOException { this(SDK); }

    LoopbackCaptcha(String sdk) throws IOException {
        try (var resource = LoopbackCaptcha.class.getResourceAsStream("geetest.html")) {
            if (resource == null) throw new IOException("缺少人机验证页面");
            html = new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("__SDK_URL__", new ObjectMapper().writeValueAsString(sdk));
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        page = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + prefix + "index.html");
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    URI page() { return page; }
    String proof() { return proof.get(); }
    String status() { return status.get(); }

    private void handle(HttpExchange request) throws IOException {
        try (request) {
            request.getResponseHeaders().set("Cache-Control", "no-store");
            request.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            request.getResponseHeaders().set("X-Frame-Options", "DENY");
            request.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            if (!page.getAuthority().equals(request.getRequestHeaders().getFirst("Host"))) {
                reply(request, 403, "text/plain", "Forbidden"); return;
            }
            String path = request.getRequestURI().getPath();
            if (request.getRequestMethod().equals("GET") && path.equals(page.getPath())) {
                reply(request, 200, "text/html; charset=utf-8", html); return;
            }
            if (!path.equals(prefix + "complete") && !path.equals(prefix + "status")) {
                reply(request, 404, "text/plain", "Not found"); return;
            }
            if (!request.getRequestMethod().equals("POST")) {
                reply(request, 405, "text/plain", "Method not allowed"); return;
            }
            String origin = "http://" + page.getAuthority();
            if (!origin.equals(request.getRequestHeaders().getFirst("Origin"))) {
                reply(request, 403, "text/plain", "Forbidden"); return;
            }
            byte[] body = request.getRequestBody().readNBytes(16385);
            if (body.length > 16384) { reply(request, 413, "text/plain", "Too large"); return; }
            String value = new String(body, StandardCharsets.UTF_8);
            if (path.equals(prefix + "complete")) {
                if (!KuroAuthService.validProof(value)) { reply(request, 400, "text/plain", "Invalid proof"); return; }
                proof.compareAndSet(null, value);
                status.set("complete");
            } else {
                if (!Set.of("initializing", "ready", "sdk-error", "init-error", "widget-error", "closed").contains(value)) {
                    reply(request, 400, "text/plain", "Invalid status"); return;
                }
                // XHRs may arrive out of order: an earlier "initializing" must not hide an error/ready state.
                if (proof.get() == null) status.updateAndGet(previous ->
                        value.equals("initializing") && !previous.equals("loading") ? previous : value);
            }
            reply(request, 200, "text/plain; charset=utf-8", "OK");
        }
    }

    private static void reply(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) { server.stop(0); executor.shutdownNow(); }
    }
}
