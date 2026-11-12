package org.example.tokenclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/** Kuro endpoints verified against Kuro_login and Haiyu; no automatic retries for mutations. */
public final class KuroAuthService implements AuthService, ScanService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client;
    private final URI base;
    private final String deviceId;

    public KuroAuthService(String deviceId) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                URI.create("https://api.kurobbs.com"), deviceId);
    }

    KuroAuthService(HttpClient client, URI base, String deviceId) {
        this.client = client;
        this.base = base;
        this.deviceId = deviceId;
    }

    @Override public boolean requiresCaptcha() { return true; }

    @Override public CompletionStage<CodeResult> sendCode(String phone) {
        return CompletableFuture.completedFuture(new CodeResult(false, "请先完成人机验证。"));
    }

    @Override public CompletionStage<CodeResult> sendCode(String phone, String proof) {
        if (!phone.matches("1[3-9][0-9]{9}") || !validProof(proof))
            return CompletableFuture.completedFuture(new CodeResult(false, "手机号或人机验证结果无效，请重新验证。"));
        return post("/user/getSmsCode", Map.of("mobile", phone, "geeTestData", proof), null)
                .thenApply(this::smsResult).exceptionally(error -> new CodeResult(false, "短信发送失败，请检查网络并重试。"));
    }

    @Override public CompletionStage<Result> loginWithPhone(String phone, String code) {
        if (!phone.matches("1[3-9][0-9]{9}") || !code.matches("[0-9]{6}"))
            return CompletableFuture.completedFuture(new Result(false, "请输入正确的手机号和 6 位验证码。", null));
        return post("/user/sdkLogin", Map.of("mobile", phone, "code", code, "gameList", "", "devCode", deviceId), null)
                .thenCompose(data -> {
                    if (!ok(data)) return CompletableFuture.completedFuture(new Result(false, ApiFeedback.failure("手机号登录", data), null));
                    String token = data.path("data").path("token").asText();
                    String id;
                    try { id = TokenFormat.userId(token); }
                    catch (IllegalArgumentException error) { return CompletableFuture.completedFuture(invalid()); }
                    if (!id.equals(data.path("data").path("userId").asText()))
                        return CompletableFuture.completedFuture(invalid());
                    return loginWithToken(token, deviceId);
                }).exceptionally(error -> unavailable());
    }

    @Override public CompletionStage<Result> loginWithToken(String token) {
        try {
            TokenInput input = TokenInput.parse(token);
            return loginWithToken(input.token(), input.deviceId());
        } catch (IllegalArgumentException error) { return CompletableFuture.completedFuture(invalid()); }
    }

    @Override public CompletionStage<Result> loginWithToken(String token, String suppliedDevice) {
        final String id;
        final String did = suppliedDevice == null || suppliedDevice.isEmpty() ? deviceId : suppliedDevice;
        try { new TokenInput(token, did); id = TokenFormat.userId(token); }
        catch (IllegalArgumentException error) { return CompletableFuture.completedFuture(invalid()); }
        return post("/user/mineV2", Map.of("otherUserId", id), token, did).thenApply(data -> {
            if (invalidResponse(data)) return invalid();
            if (!ok(data)) return unavailable();
            JsonNode mine = data.path("data").path("mine");
            // Require the server to identify this as the authenticated user's own profile.
            if (!id.equals(mine.path("userId").asText()) || mine.path("isLoginUser").asInt() != 1)
                return new Result(false, "服务端未确认 Token 的账号身份，请重新登录。", null);
            String account = mine.path("mobile").asText();
            if (account.isBlank()) account = mine.path("userName").asText("库街区用户 " + id);
            return new Result(true, "登录成功", new Session(account, token, did));
        }).exceptionally(error -> unavailable());
    }

    @Override public CompletionStage<ScanResult> roles(Session session, String qrCode) {
        if (!validQr(qrCode)) return CompletableFuture.completedFuture(new ScanResult(Status.FAILURE, "请输入有效的库洛游戏二维码内容。"));
        return post("/user/auth/roleInfos", Map.of("qrCode", qrCode), session.token(), sessionDevice(session)).thenApply(data -> {
            if (!ok(data)) return scanResult(data, "扫码验证");
            if (data.path("data").isBoolean() && !data.path("data").asBoolean())
                return new ScanResult(Status.FAILURE, "二维码扫描未成功，请重新获取二维码。");
            List<Role> roles = new ArrayList<>();
            JsonNode list = data.path("data");
            if (list.isArray()) for (JsonNode role : list) {
                String id = role.path("id").asText();
                if (role.path("support").asBoolean(true)) {
                    String name = role.path("roleName").asText();
                    if (name.isBlank()) name = role.path("gameName").asText("游戏角色");
                    roles.add(new Role(id, name + " · " + role.path("serverName").asText("默认区服")));
                }
            }
            if (list.isArray() && !list.isEmpty() && roles.isEmpty())
                return new ScanResult(Status.FAILURE, "二维码中没有受支持的游戏角色。");
            // KuRo_Scanner confirms successful scans with id="" when no role binding is returned.
            return new ScanResult(Status.SUCCESS, "扫码成功，请二次确认登录游戏。", List.copyOf(roles));
        }).exceptionally(error -> new ScanResult(Status.FAILURE, ApiFeedback.unavailable("扫码验证", error)));
    }

    @Override public CompletionStage<ScanResult> scan(Session session, String qrCode, String roleId, String sms) {
        if (!validQr(qrCode) || roleId == null || (!sms.isEmpty() && !sms.matches("[0-9]{6}")))
            return CompletableFuture.completedFuture(new ScanResult(Status.FAILURE, "二维码、角色或验证码无效。"));
        return post("/user/auth/scanLogin", Map.of("qrCode", qrCode, "id", roleId,
                "autoLogin", "false", "verifyCode", sms), session.token(), sessionDevice(session))
                .thenApply(data -> scanResult(data, "二次确认登录"))
                .exceptionally(error -> new ScanResult(Status.FAILURE, ApiFeedback.unavailable("二次确认登录", error)));
    }

    @Override public CompletionStage<CodeResult> sendScanCode(Session session, String proof) {
        if (!validProof(proof)) return CompletableFuture.completedFuture(new CodeResult(false, "请重新完成人机验证。"));
        return post("/user/sms/scanSms", Map.of("geeTestData", proof), session.token(), sessionDevice(session))
                .thenApply(this::smsResult).exceptionally(error -> new CodeResult(false, ApiFeedback.unavailable("扫码短信发送", error)));
    }

    private CodeResult smsResult(JsonNode data) {
        if (invalidResponse(data)) return new CodeResult(false, "Token 已失效，请重新登录。", true);
        // A 200 containing geeTest=true is a challenge, not a sent SMS.
        if (data.path("data").path("geeTest").asBoolean()) return new CodeResult(false, "人机验证未通过，请重新验证。");
        return new CodeResult(ok(data), ok(data) ? "验证码已发送，请查收短信。" : ApiFeedback.failure("短信发送", data));
    }

    private ScanResult scanResult(JsonNode data, String operation) {
        return switch (data.path("code").asInt(-1)) {
            case 200 -> ok(data) && !(data.path("data").isBoolean() && !data.path("data").asBoolean())
                    ? new ScanResult(Status.SUCCESS, "游戏扫码登录成功。") : new ScanResult(Status.FAILURE, ApiFeedback.failure(operation, data));
            case 2240 -> new ScanResult(Status.SMS_REQUIRED, "首次扫码或新设备需要短信验证，请获取验证码。");
            case 220, 401 -> new ScanResult(Status.INVALID_TOKEN, "Token 已失效，请重新登录。");
            case 2209 -> new ScanResult(Status.EXPIRED, "二维码已过期，请重新获取二维码。");
            default -> new ScanResult(Status.FAILURE, ApiFeedback.failure(operation, data));
        };
    }

    private CompletionStage<JsonNode> post(String path, Map<String, String> data, String token) {
        return post(path, data, token, deviceId);
    }

    private String sessionDevice(Session session) { return session.deviceId().isEmpty() ? deviceId : session.deviceId(); }

    private CompletionStage<JsonNode> post(String path, Map<String, String> data, String token, String did) {
        String body = data.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json").header("User-Agent", "okhttp/3.11.0")
                .header("osversion", "Android").header("countrycode", "CN").header("model", "MIX 2")
                .header("source", "android").header("lang", "zh-Hans").header("version", "2.5.0")
                .header("versioncode", "2500").header("channelid", "4")
                .header("devcode", did).header("did", did).header("distinct_id", did);
        if (token != null) request.header("token", token);
        return client.sendAsync(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    try {
                        if (response.statusCode() == 401) return JSON.readTree("{\"code\":220}");
                        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ApiFeedback.HttpFailure(response.statusCode());
                        if (response.body().length() > 1_000_000) throw new IllegalStateException();
                        JsonNode value = JSON.readTree(response.body());
                        if (value == null || !value.isObject()) throw new IllegalStateException();
                        List<String> credentials = new ArrayList<>();
                        credentials.add(token);
                        credentials.add(did);
                        for (String key : List.of("mobile", "code", "verifyCode", "geeTestData", "qrCode")) credentials.add(data.get(key));
                        ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("msg", ApiFeedback.message(
                                value.path("msg").asText(value.path("message").asText("")), credentials));
                        return value;
                    } catch (ApiFeedback.HttpFailure error) { throw error; }
                    catch (Exception error) { throw new CompletionException(new IllegalStateException("服务响应不可用")); }
                });
    }

    static boolean validProof(String proof) {
        try {
            if (proof == null || proof.length() > 16384) return false;
            JsonNode data = JSON.readTree(proof);
            for (String key : List.of("lot_number", "captcha_output", "pass_token", "gen_time")) {
                if (!data.path(key).isTextual() || data.path(key).asText().isBlank()) return false;
            }
            return true;
        } catch (Exception error) { return false; }
    }

    static boolean validQr(String qr) {
        return qr != null && qr.length() >= 24 && qr.length() <= 4096 && qr.toUpperCase(Locale.ROOT).contains("KURO")
                && qr.chars().noneMatch(Character::isWhitespace);
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static boolean ok(JsonNode data) { return data.path("code").asInt(-1) == 200 && data.path("success").asBoolean(true); }
    private static boolean invalidResponse(JsonNode data) { return Set.of(220, 401).contains(data.path("code").asInt(-1)); }
    private static Result invalid() { return new Result(false, "Token 不合法或已失效，请重新登录。", null, true); }
    private static Result unavailable() { return new Result(false, "暂时无法验证登录，请检查网络并重试。", null); }
}
