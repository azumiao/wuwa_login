package org.example.tokenclient;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class KuroAuthServiceTest {
    static final String PROOF = "{\"lot_number\":\"lot\",\"captcha_output\":\"output\",\"pass_token\":\"pass\",\"gen_time\":\"123\"}";
    static final String QR = "https://example.invalid/G152#KUROabcdefghijklmnopqrstuvwx";
    static final String DEVICE = "11111111-2222-4333-8444-555555555555";
    static final String IMPORTED_DEVICE = "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE";
    private HttpServer server;
    private KuroAuthService service;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private java.util.function.Function<Request, String> respond;
    private int httpStatus = 200;
    record Request(String path, Map<String, String> form, String token, String device, String did, String version, String versionCode) {}

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Map<String, String> fields = new HashMap<>();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.isEmpty()) for (String pair : body.split("&")) {
                String[] parts = pair.split("=", 2);
                fields.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
            }
            Request request = new Request(exchange.getRequestURI().getPath(), fields,
                    exchange.getRequestHeaders().getFirst("token"), exchange.getRequestHeaders().getFirst("devcode"),
                    exchange.getRequestHeaders().getFirst("did"), exchange.getRequestHeaders().getFirst("version"),
                    exchange.getRequestHeaders().getFirst("versioncode"));
            requests.add(request);
            byte[] data = respond.apply(request).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(httpStatus, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        service = new KuroAuthService(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()), DEVICE);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void sendsSmsOnlyWithCompletedProofAndEncodesForm() throws Exception {
        respond = request -> "{\"code\":200}";
        assertFalse(await(service.sendCode("13812345678")).success());
        assertFalse(await(service.sendCode("13812345678", "{}")).success());
        assertTrue(requests.isEmpty());
        assertTrue(await(service.sendCode("13812345678", PROOF)).success());
        Request request = requests.getFirst();
        assertEquals("/user/getSmsCode", request.path());
        assertEquals(PROOF, request.form().get("geeTestData"));
        assertNull(request.token());
        assertEquals(DEVICE, request.device());
    }

    @Test void successfulPhoneLoginVerifiesReturnedTokenWithServer() throws Exception {
        String token = token("123", null);
        respond = request -> request.path().equals("/user/sdkLogin")
                ? "{\"code\":200,\"data\":{\"token\":\"" + token + "\",\"userId\":\"123\"}}" : mine("123", 1);
        var result = await(service.loginWithPhone("13812345678", "123456"));
        assertTrue(result.success());
        assertEquals(token, result.session().token());
        assertEquals("138****5678", result.session().account());
        assertEquals(DEVICE, result.session().deviceId());
        assertEquals(2, requests.size());
        assertEquals("123456", requests.getFirst().form().get("code"));
        assertEquals("/user/mineV2", requests.getLast().path());
        assertEquals("123", requests.getLast().form().get("otherUserId"));
        assertEquals(token, requests.getLast().token());
        assertEquals(requests.getFirst().device(), requests.getLast().device());
        assertTrue(requests.stream().allMatch(request -> request.device().equals(request.did())
                && request.version().equals("2.5.0") && request.versionCode().equals("2500")));
    }

    @Test void rejectsMalformedDemoExpiredAndUnsignedTokensBeforeNetwork() throws Exception {
        for (String token : List.of("demo-token", "bad token", token("123", Instant.now().getEpochSecond() - 1),
                b64("{\"alg\":\"none\"}") + "." + b64("{\"userId\":\"123\"}") + ".AA")) {
            assertTrue(await(service.loginWithToken(token)).invalidToken());
        }
        assertTrue(requests.isEmpty());
    }

    @Test void tokenWithoutExpStillRequiresServerAndForgeryIsRejected() throws Exception {
        respond = request -> "{\"code\":220}";
        assertTrue(await(service.loginWithToken(token("123", null))).invalidToken());
        assertEquals(1, requests.size());
    }

    @Test void serverMustConfirmOwnIdentityNotJustSuccessfulProfileResponse() throws Exception {
        respond = request -> mine("999", 1);
        assertFalse(await(service.loginWithToken(token("123", null))).success());
        respond = request -> mine("123", 0);
        assertFalse(await(service.loginWithToken(token("123", null))).success());
        respond = request -> "{\"code\":200}";
        assertFalse(await(service.loginWithToken(token("123", null))).success());
    }

    @Test void transientHttpAndMalformedJsonDoNotInvalidateCachedToken() throws Exception {
        respond = request -> "private-token-in-error";
        for (int status : List.of(503, 429, 403, 200)) {
            httpStatus = status;
            var result = await(service.loginWithToken(token("123", null)));
            assertFalse(result.success());
            assertFalse(result.invalidToken());
            assertFalse(result.message().contains("private-token"));
        }
        httpStatus = 401;
        assertTrue(await(service.loginWithToken(token("123", null))).invalidToken());
    }

    @Test void smsChallengeIsNotReportedAsSmsSent() throws Exception {
        respond = request -> "{\"code\":200,\"data\":{\"geeTest\":true}}";
        assertFalse(await(service.sendCode("13812345678", PROOF)).success());
    }

    @Test void firstScanRequiresSmsAndRetainsQrRoleAndDeviceForRetry() throws Exception {
        AtomicInteger scans = new AtomicInteger();
        respond = request -> switch (request.path()) {
            case "/user/auth/roleInfos" -> "{\"code\":200,\"data\":[{\"id\":\"role-1\",\"roleName\":\"漂泊者\",\"support\":true}]}";
            case "/user/auth/scanLogin" -> scans.incrementAndGet() == 1 ? "{\"code\":2240}" : "{\"code\":200,\"data\":true}";
            default -> "{\"code\":200}";
        };
        var session = new AuthService.Session("账号", token("123", null), IMPORTED_DEVICE);
        var roles = await(service.roles(session, QR));
        assertEquals("role-1", roles.roles().getFirst().id());
        assertEquals(ScanService.Status.SMS_REQUIRED, await(service.scan(session, QR, "role-1", "")).status());
        assertTrue(await(service.sendScanCode(session, PROOF)).success());
        assertEquals(ScanService.Status.SUCCESS, await(service.scan(session, QR, "role-1", "654321")).status());
        assertEquals(4, requests.size());
        assertEquals(QR, requests.getLast().form().get("qrCode"));
        assertEquals("role-1", requests.getLast().form().get("id"));
        assertEquals("654321", requests.getLast().form().get("verifyCode"));
        assertEquals("false", requests.getLast().form().get("autoLogin"));
        assertTrue(requests.stream().allMatch(request -> request.token().equals(session.token())));
        assertTrue(requests.stream().allMatch(request -> request.device().equals(IMPORTED_DEVICE)
                && request.did().equals(IMPORTED_DEVICE) && request.version().equals("2.5.0") && request.versionCode().equals("2500")));
    }

    @Test void expiredQrAndInvalidTokenRemainDistinctAndFailuresAreNotRetried() throws Exception {
        var session = new AuthService.Session("账号", token("123", null));
        respond = request -> "{\"code\":2209}";
        assertEquals(ScanService.Status.EXPIRED, await(service.roles(session, QR)).status());
        respond = request -> "{\"code\":220}";
        assertEquals(ScanService.Status.INVALID_TOKEN, await(service.scan(session, QR, "role", "")).status());
        assertTrue(await(service.sendScanCode(session, PROOF)).invalidToken());
        respond = request -> "{\"code\":200,\"data\":false}";
        assertEquals(ScanService.Status.FAILURE, await(service.scan(session, QR, "role", "")).status());
        respond = request -> "{\"code\":2241}";
        assertEquals(ScanService.Status.FAILURE, await(service.scan(session, QR, "role", "123456")).status());
        respond = request -> "{\"code\":200}";
        assertEquals(ScanService.Status.SUCCESS, await(service.scan(session, QR, "role", "123456")).status());
        assertEquals(6, requests.size());
    }

    @Test void successfulScanCanBeConfirmedWithoutRoleBindingId() throws Exception {
        var session = new AuthService.Session("账号", token("123", null));
        respond = request -> request.path().equals("/user/auth/roleInfos")
                ? "{\"code\":200,\"data\":[]}" : "{\"code\":200,\"data\":true}";
        var result = await(service.roles(session, QR));
        assertEquals(ScanService.Status.SUCCESS, result.status());
        assertTrue(result.roles().isEmpty());
        assertEquals(1, requests.size(), "Reading a QR must not confirm login");
        assertEquals(ScanService.Status.SUCCESS, await(service.scan(session, QR, "", "")).status());
        assertEquals("/user/auth/scanLogin", requests.getLast().path());
        assertTrue(requests.getLast().form().containsKey("id"));
        assertEquals("", requests.getLast().form().get("id"));

        respond = request -> "{\"code\":200,\"data\":[{\"roleName\":\"漂泊者\",\"support\":true}]}";
        result = await(service.roles(session, QR));
        assertEquals(ScanService.Status.SUCCESS, result.status());
        assertEquals("", result.roles().getFirst().id());
    }

    @Test void explicitlyUnsupportedOrFailedScanDoesNotEnterConfirmation() throws Exception {
        var session = new AuthService.Session("账号", token("123", null));
        respond = request -> "{\"code\":200,\"data\":[{\"id\":\"role\",\"support\":false}]}";
        assertEquals(ScanService.Status.FAILURE, await(service.roles(session, QR)).status());
        respond = request -> "{\"code\":200,\"data\":false}";
        assertEquals(ScanService.Status.FAILURE, await(service.roles(session, QR)).status());
    }

    @Test void pairedTokenImportKeepsJwtAndDeviceSeparateAcrossRestoreAndScanning() throws Exception {
        String jwt = token("123", null);
        respond = request -> request.path().equals("/user/mineV2") ? mine("123", 1) : "{\"code\":200,\"data\":[]}";
        for (String text : List.of(jwt + ", " + IMPORTED_DEVICE, "添加token " + jwt + "，" + IMPORTED_DEVICE)) {
            var result = await(service.loginWithToken(text));
            assertTrue(result.success());
            assertEquals(jwt, result.session().token());
            assertEquals(IMPORTED_DEVICE, result.session().deviceId());
            // A new service instance still uses the restored session's device, not its own default.
            var restarted = new KuroAuthService(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()), DEVICE);
            var restored = await(restarted.loginWithToken(result.session().token(), result.session().deviceId()));
            assertTrue(restored.success());
            await(restarted.roles(restored.session(), QR));
        }
        assertTrue(requests.stream().allMatch(request -> request.token().equals(jwt)
                && request.device().equals(IMPORTED_DEVICE) && request.did().equals(IMPORTED_DEVICE)));
    }

    @Test void malformedDevicePairIsRejectedBeforeAnyRequest() throws Exception {
        String jwt = token("123", null);
        for (String value : List.of(jwt + ",", jwt + ",not-a-device", jwt + "," + IMPORTED_DEVICE + ",extra")) {
            assertFalse(await(service.loginWithToken(value)).success());
        }
        assertTrue(requests.isEmpty());
    }

    @Test void scanFailureShowsServerCodeAndReasonWithoutLeakingCredentials() throws Exception {
        String jwt = token("123", null);
        var session = new AuthService.Session("账号", jwt, IMPORTED_DEVICE);
        String detail = "设备校验失败 " + jwt + " " + IMPORTED_DEVICE + " " + QR + " 13812345678";
        respond = request -> {
            try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("code", 2999, "msg", detail)); }
            catch (Exception error) { throw new AssertionError(error); }
        };
        var result = await(service.roles(session, QR));
        assertEquals(ScanService.Status.FAILURE, result.status());
        assertTrue(result.message().contains("扫码验证失败（错误码 2999）"));
        assertTrue(result.message().contains("设备校验失败"));
        for (String credential : List.of(jwt, IMPORTED_DEVICE, QR, "13812345678")) assertFalse(result.message().contains(credential));
        assertTrue(await(service.scan(session, QR, "", "")).message().contains("二次确认登录失败"));
        httpStatus = 403;
        result = await(service.roles(session, QR));
        assertEquals(ScanService.Status.FAILURE, result.status());
        assertTrue(result.message().contains("HTTP 403"));
        assertFalse(result.message().contains(jwt));
    }

    static String token(String id, Long exp) {
        return b64("{\"alg\":\"HS256\"}") + "." + b64("{\"userId\":\"" + id + "\"" + (exp == null ? "" : ",\"exp\":" + exp) + "}")
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    }
    private static String b64(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String mine(String id, int own) { return "{\"code\":200,\"data\":{\"mine\":{\"userId\":\"" + id + "\",\"isLoginUser\":" + own + ",\"mobile\":\"138****5678\"}}}"; }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(5, TimeUnit.SECONDS); }
}
