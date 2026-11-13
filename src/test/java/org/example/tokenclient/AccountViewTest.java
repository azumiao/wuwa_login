package org.example.tokenclient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountViewTest {
    private final List<AccountView> views = new ArrayList<>();

    @BeforeAll static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        assertTrue(started.await(15, TimeUnit.SECONDS));
    }

    @AfterEach void closeWindows() throws Exception {
        fx(() -> {
            views.forEach(AccountView::dispose);
            new ArrayList<>(Window.getWindows()).forEach(Window::hide);
            return null;
        });
    }

    @Test void loginScreenHasBothMethodsAndNoGuideOrPlatformSelector() throws Exception {
        fx(() -> {
            AccountView view = view(new DemoAuthService(), true, token -> {});
            assertNotNull(view.lookup("#phone-input"));
            assertNotNull(view.lookup("#token-input"));
            assertTrue(view.lookupAll(".combo-box").isEmpty());
            assertTrue(view.lookupAll(".label").stream().map(node -> ((Label) node).getText())
                    .noneMatch(text -> text.equals("登录指南") || text.equals("默认平台")));
            assertNotNull(view.lookup("#demo-banner"));
            snapshot(view, "login");
            button(view, "phone-login").fire();
            assertTrue(label(view, "feedback").getText().contains("11 位手机号"));
            return null;
        });
    }

    @Test void phoneLoginExportsReturnedTokenAndShowsRoomMonitor() throws Exception {
        AtomicReference<String> copied = new AtomicReference<>();
        AccountView view = fx(() -> {
            AccountView current = view(new DemoAuthService(), true, copied::set);
            field(current, "phone-input").setText("13812345678");
            button(current, "phone-login").fire();
            Parent dialog = dialog("phone-dialog");
            button(dialog, "send-code").fire();
            assertTrue(button(dialog, "send-code").isDisabled());
            return current;
        });
        fx(() -> {
            Parent dialog = dialog("phone-dialog");
            assertTrue(label(dialog, "sms-feedback").getText().contains("未发送短信"));
            assertTrue(button(dialog, "send-code").isDisabled());
            assertTrue(button(dialog, "send-code").getText().contains("秒后重试"));
            field(dialog, "sms-code").setText("123456");
            snapshot(dialog, "phone-dialog");
            button(dialog, "verify-phone").fire();
            return null;
        });
        fx(() -> {
            assertEquals("138****5678", label(view, "current-account").getText());
            assertEquals("●  演示登录", label(view, "login-status").getText());
            assertNull(view.lookup("#phone-input"));
            assertNotNull(view.lookup("#room-input"));
            button(view, "get-token").fire();
            Parent dialog = dialog("token-dialog");
            assertFalse(field(dialog, "export-token-visible").isVisible());
            ((ToggleButton) dialog.lookup("#export-reveal")).fire();
            assertTrue(field(dialog, "export-token-visible").isVisible());
            button(dialog, "copy-token").fire();
            assertTrue(copied.get().startsWith("demo-"));
            assertEquals(copied.get(), field(dialog, "export-token-visible").getText());
            close(dialog);
            button(view, "monitor-button").fire();
            assertTrue(label(view, "feedback").getText().contains("请先输入直播间"));
            field(view, "room-input").setText("12345678");
            button(view, "monitor-button").fire();
            assertEquals("演示监视中", label(view, "monitor-status").getText());
            assertTrue(field(view, "room-input").isDisabled());
            button(view, "monitor-button").fire();
            assertEquals("等待中", label(view, "monitor-status").getText());
            assertFalse(field(view, "room-input").isDisabled());
            snapshot(view, "monitor");
            button(view, "logout-button").fire();
            assertEquals("●  未登录", label(view, "login-status").getText());
            assertEquals("", field(view, "token-input").getText());
            assertNull(view.lookup("#get-token"));
            return null;
        });
    }

    @Test void existingTokenWorksWithoutPhoneAndCannotSubmitTwice() throws Exception {
        CompletableFuture<AuthService.Result> response = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> copied = new AtomicReference<>();
        AuthService service = new AuthService() {
            @Override public CompletionStage<Result> loginWithToken(String token) {
                calls.incrementAndGet();
                assertEquals("existing-token", token);
                return response;
            }
        };
        AccountView view = fx(() -> {
            AccountView current = view(service, false, copied::set);
            button(current, "login-button").fire();
            assertTrue(label(current, "feedback").getText().contains("请先输入"));
            field(current, "token-input").setText("bad token");
            button(current, "login-button").fire();
            assertTrue(label(current, "feedback").getText().contains("不能包含"));
            assertEquals(0, calls.get());
            field(current, "token-input").setText("  existing-token  ");
            ((ToggleButton) current.lookup("#reveal-token")).fire();
            assertTrue(field(current, "token-visible").isVisible());
            assertEquals("  existing-token  ", field(current, "token-visible").getText());
            button(current, "login-button").fire();
            button(current, "login-button").fire();
            assertTrue(button(current, "phone-login").isDisabled());
            assertTrue(button(current, "login-button").isDisabled());
            assertEquals(1, calls.get());
            return current;
        });
        response.complete(new AuthService.Result(true, "登录成功", new AuthService.Session("测试账号", "existing-token")));
        fx(() -> {
            assertEquals("●  已登录", label(view, "login-status").getText());
            button(view, "get-token").fire();
            Parent dialog = dialog("token-dialog");
            TextField export = field(dialog, "export-token");
            button(dialog, "copy-token").fire();
            assertEquals("existing-token", copied.get());
            close(dialog);
            assertEquals("", export.getText());
            field(view, "room-input").setText("123");
            button(view, "monitor-button").fire();
            assertEquals("等待中", label(view, "monitor-status").getText());
            assertTrue(label(view, "feedback").getText().contains("不支持游戏扫码"));
            button(view, "logout-button").fire();
            assertEquals("", field(view, "token-input").getText());
            assertFalse(field(view, "token-visible").isVisible());
            return null;
        });
    }

    @Test void cancelledPhoneLoginIgnoresLateSuccess() throws Exception {
        CompletableFuture<AuthService.Result> response = new CompletableFuture<>();
        AuthService service = new AuthService() {
            @Override public CompletionStage<Result> loginWithPhone(String phone, String code) { return response; }
        };
        AccountView view = fx(() -> {
            AccountView current = view(service, false, token -> {});
            field(current, "phone-input").setText("13812345678");
            button(current, "phone-login").fire();
            Parent dialog = dialog("phone-dialog");
            field(dialog, "sms-code").setText("123456");
            button(dialog, "verify-phone").fire();
            assertTrue(button(dialog, "verify-phone").isDisabled());
            close(dialog);
            return current;
        });
        response.complete(new AuthService.Result(true, "成功", new AuthService.Session("13812345678", "late-token")));
        fx(() -> {
            assertEquals("●  未登录", label(view, "login-status").getText());
            assertNotNull(view.lookup("#phone-login"));
            return null;
        });
    }

    @Test void smsFailureAndWrongCodeCanBeRetried() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        AuthService service = new AuthService() {
            @Override public CompletionStage<CodeResult> sendCode(String phone) {
                sends.incrementAndGet();
                return CompletableFuture.failedFuture(new RuntimeException("private-phone"));
            }
            @Override public CompletionStage<Result> loginWithPhone(String phone, String code) {
                return CompletableFuture.completedFuture(new Result(false, "验证码错误", null));
            }
        };
        fx(() -> {
            AccountView view = view(service, false, token -> {});
            field(view, "phone-input").setText("13812345678");
            button(view, "phone-login").fire();
            Parent dialog = dialog("phone-dialog");
            button(dialog, "verify-phone").fire();
            assertTrue(label(dialog, "sms-feedback").getText().contains("6 位"));
            button(dialog, "send-code").fire();
            return null;
        });
        fx(() -> {
            Parent dialog = dialog("phone-dialog");
            assertFalse(button(dialog, "send-code").isDisabled());
            assertFalse(label(dialog, "sms-feedback").getText().contains("private-phone"));
            button(dialog, "send-code").fire();
            return null;
        });
        fx(() -> {
            Parent dialog = dialog("phone-dialog");
            assertEquals(2, sends.get());
            field(dialog, "sms-code").setText("654321");
            button(dialog, "verify-phone").fire();
            return null;
        });
        fx(() -> {
            Parent dialog = dialog("phone-dialog");
            assertEquals("验证码错误", label(dialog, "sms-feedback").getText());
            assertFalse(button(dialog, "verify-phone").isDisabled());
            return null;
        });
    }

    @Test void unavailableAndExceptionalTokenServicesDoNotClaimSuccess() throws Exception {
        AccountView unavailable = fx(() -> {
            AccountView view = view(new AuthService() {}, false, token -> {});
            field(view, "token-input").setText("test-token");
            button(view, "login-button").fire();
            return view;
        });
        fx(() -> {
            assertTrue(label(unavailable, "feedback").getText().contains("尚未接入"));
            assertEquals("●  未登录", label(unavailable, "login-status").getText());
            return null;
        });
        AccountView failed = fx(() -> {
            AccountView view = view(new AuthService() {
                @Override public CompletionStage<Result> loginWithToken(String token) {
                    return CompletableFuture.failedFuture(new RuntimeException("private-token"));
                }
            }, false, token -> {});
            field(view, "token-input").setText("test-token");
            button(view, "login-button").fire();
            return view;
        });
        fx(() -> {
            assertEquals("●  未登录", label(failed, "login-status").getText());
            assertFalse(label(failed, "feedback").getText().contains("private-token"));
            assertFalse(button(failed, "login-button").isDisabled());
            return null;
        });
    }

    @Test void disposingIgnoresPendingTokenLogin() throws Exception {
        CompletableFuture<AuthService.Result> response = new CompletableFuture<>();
        AccountView view = fx(() -> {
            AccountView current = view(new AuthService() {
                @Override public CompletionStage<Result> loginWithToken(String token) { return response; }
            }, false, token -> {});
            field(current, "token-input").setText("test-token");
            button(current, "login-button").fire();
            current.dispose();
            return current;
        });
        response.complete(new AuthService.Result(true, "成功", new AuthService.Session("账号", "late-token")));
        fx(() -> {
            assertEquals("●  未登录", label(view, "login-status").getText());
            assertEquals("", field(view, "token-input").getText());
            return null;
        });
    }

    @Test void savedTokenIsCheckedBeforeLoginAndClosingPreservesIt() throws Exception {
        MemoryCredentials store = new MemoryCredentials();
        store.saved = new AuthService.Session("cached-account", "saved-token");
        CompletableFuture<AuthService.Result> check = new CompletableFuture<>();
        AccountView view = fx(() -> {
            AccountView current = persistentView(new AuthService() {
                @Override public CompletionStage<Result> loginWithToken(String token) {
                    assertEquals("saved-token", token);
                    return check;
                }
            }, store, owner -> CompletableFuture.completedFuture(null));
            current.restoreSession();
            assertEquals("●  未登录", label(current, "login-status").getText());
            assertTrue(button(current, "phone-login").isDisabled());
            assertNull(current.lookup("#get-token"));
            return current;
        });
        check.complete(new AuthService.Result(true, "成功", new AuthService.Session("server-account", "saved-token")));
        fx(() -> {
            assertEquals("server-account", label(view, "current-account").getText());
            assertEquals("●  已登录", label(view, "login-status").getText());
            assertEquals("server-account", store.saved.account());
            view.dispose();
            assertNotNull(store.saved);
            return null;
        });
    }

    @Test void failedRestoreCanRetryAndOnlyConfirmedInvalidTokenIsDeleted() throws Exception {
        MemoryCredentials store = new MemoryCredentials();
        store.saved = new AuthService.Session("账号", "saved-token");
        AtomicInteger calls = new AtomicInteger();
        AccountView view = fx(() -> {
            AccountView current = persistentView(new AuthService() {
                @Override public CompletionStage<Result> loginWithToken(String token) {
                    return CompletableFuture.completedFuture(calls.incrementAndGet() == 1
                            ? new Result(false, "网络不可用", null) : new Result(false, "Token 已失效", null, true));
                }
            }, store, owner -> CompletableFuture.completedFuture(null));
            current.restoreSession();
            return current;
        });
        fx(() -> {
            assertNotNull(store.saved);
            assertEquals("●  未登录", label(view, "login-status").getText());
            assertTrue(button(view, "retry-saved").isVisible());
            button(view, "retry-saved").fire();
            return null;
        });
        fx(() -> {
            assertNull(store.saved);
            assertFalse(button(view, "retry-saved").isVisible());
            assertTrue(label(view, "feedback").getText().contains("失效"));
            return null;
        });
    }

    @Test void phoneWaitsForHumanVerificationThenPersistsAndLogoutDeletesSession() throws Exception {
        MemoryCredentials store = new MemoryCredentials();
        CompletableFuture<String> proof = new CompletableFuture<>();
        AtomicInteger sends = new AtomicInteger();
        AuthService service = new AuthService() {
            @Override public boolean requiresCaptcha() { return true; }
            @Override public CompletionStage<CodeResult> sendCode(String phone, String value) {
                assertEquals(KuroAuthServiceTest.PROOF, value);
                sends.incrementAndGet();
                return CompletableFuture.completedFuture(new CodeResult(true, "已发送"));
            }
            @Override public CompletionStage<Result> loginWithPhone(String phone, String code) {
                return CompletableFuture.completedFuture(new Result(true, "成功", new Session(phone, "phone-token")));
            }
        };
        AccountView view = fx(() -> {
            AccountView current = persistentView(service, store, owner -> proof);
            field(current, "phone-input").setText("13812345678");
            button(current, "phone-login").fire();
            assertEquals(0, sends.get());
            assertTrue(button(dialog("phone-dialog"), "send-code").isDisabled());
            return current;
        });
        proof.complete(KuroAuthServiceTest.PROOF);
        fx(() -> null);
        fx(() -> {
            assertEquals(1, sends.get());
            Parent phone = dialog("phone-dialog");
            assertTrue(button(phone, "send-code").isDisabled());
            field(phone, "sms-code").setText("123456");
            button(phone, "verify-phone").fire();
            return null;
        });
        fx(() -> {
            assertEquals("phone-token", store.saved.token());
            button(view, "logout-button").fire();
            assertNull(store.saved);
            return null;
        });
    }

    @Test void closingPhoneDialogWhileCaptchaPendingNeverSendsSms() throws Exception {
        CompletableFuture<String> proof = new CompletableFuture<>();
        AtomicInteger sends = new AtomicInteger();
        fx(() -> {
            AccountView view = persistentView(new AuthService() {
                @Override public boolean requiresCaptcha() { return true; }
                @Override public CompletionStage<CodeResult> sendCode(String phone, String value) {
                    sends.incrementAndGet();
                    return CompletableFuture.completedFuture(new CodeResult(true, "已发送"));
                }
            }, new MemoryCredentials(), owner -> proof);
            field(view, "phone-input").setText("13812345678");
            button(view, "phone-login").fire();
            close(dialog("phone-dialog"));
            return null;
        });
        proof.complete(KuroAuthServiceTest.PROOF);
        fx(() -> { assertEquals(0, sends.get()); return null; });
    }

    @Test void scanSmsContinuationUsesOriginalQrAndRoleAndCannotSubmitTwice() throws Exception {
        List<String> submissions = new ArrayList<>();
        ScanService service = new ScanService() {
            @Override public CompletionStage<ScanResult> roles(AuthService.Session session, String qr) {
                return CompletableFuture.completedFuture(new ScanResult(Status.SUCCESS, "选择角色", List.of(new Role("role-1", "漂泊者"))));
            }
            @Override public CompletionStage<ScanResult> scan(AuthService.Session session, String qr, String id, String code) {
                assertEquals(KuroAuthServiceTest.QR, qr);
                assertEquals("role-1", id);
                submissions.add(code);
                return CompletableFuture.completedFuture(new ScanResult(code.isEmpty() ? Status.SMS_REQUIRED : Status.SUCCESS, code.isEmpty() ? "需要短信" : "登录成功"));
            }
            @Override public CompletionStage<AuthService.CodeResult> sendScanCode(AuthService.Session session, String proof) {
                assertEquals(KuroAuthServiceTest.PROOF, proof);
                return CompletableFuture.completedFuture(new AuthService.CodeResult(true, "已发送"));
            }
        };
        fx(() -> {
            var dialog = new ScanLoginDialog(null, new AuthService.Session("账号", "token"), service,
                    owner -> CompletableFuture.completedFuture(KuroAuthServiceTest.PROOF), () -> fail("Unexpected invalid token"));
            dialog.show();
            ((TextArea) dialog.getDialogPane().lookup("#qr-input")).setText(KuroAuthServiceTest.QR);
            button(dialog.getDialogPane(), "read-qr").fire();
            return null;
        });
        fx(() -> {
            assertEquals("二次确认登录", button(dialog("scan-dialog"), "confirm-qr").getText());
            assertTrue(submissions.isEmpty());
            button(dialog("scan-dialog"), "confirm-qr").fire();
            button(dialog("scan-dialog"), "confirm-qr").fire();
            return null;
        });
        fx(() -> {
            Parent dialog = dialog("scan-dialog");
            assertTrue(field(dialog, "scan-code").isVisible());
            snapshot(dialog, "scan-sms");
            button(dialog, "send-scan-code").fire();
            return null;
        });
        fx(() -> null);
        fx(() -> {
            Parent dialog = dialog("scan-dialog");
            assertTrue(button(dialog, "send-scan-code").isDisabled());
            field(dialog, "scan-code").setText("123456");
            button(dialog, "confirm-qr").fire();
            return null;
        });
        fx(() -> {
            assertEquals(List.of("", "123456"), submissions);
            assertTrue(button(dialog("scan-dialog"), "confirm-qr").isDisabled());
            close(dialog("scan-dialog"));
            return null;
        });
    }

    @Test void decodedImageAutomaticallyShowsSecondConfirmationWithoutRoleIdAndCancelNeverLogsIn(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Path image = directory.resolve("game-qr.png");
        var pixels = new com.google.zxing.qrcode.QRCodeWriter().encode(KuroAuthServiceTest.QR, com.google.zxing.BarcodeFormat.QR_CODE, 300, 300);
        com.google.zxing.client.j2se.MatrixToImageWriter.writeToPath(pixels, "PNG", image);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger logins = new AtomicInteger();
        ScanService scanner = new ScanService() {
            public CompletionStage<ScanResult> roles(AuthService.Session session, String qr) {
                assertEquals(KuroAuthServiceTest.QR, qr);
                reads.incrementAndGet();
                return CompletableFuture.completedFuture(new ScanResult(Status.SUCCESS, "扫码成功"));
            }
            public CompletionStage<ScanResult> scan(AuthService.Session session, String qr, String id, String sms) {
                assertEquals(KuroAuthServiceTest.QR, qr);
                assertEquals("", id);
                logins.incrementAndGet();
                return CompletableFuture.completedFuture(new ScanResult(Status.SUCCESS, "游戏登录成功"));
            }
            public CompletionStage<AuthService.CodeResult> sendScanCode(AuthService.Session session, String proof) {
                throw new AssertionError("Unexpected SMS");
            }
        };
        fx(() -> {
            var dialog = new ScanLoginDialog(null, new AuthService.Session("13812345678", "token"), scanner,
                    owner -> CompletableFuture.completedFuture(null), () -> fail("Unexpected invalid token"));
            dialog.show();
            dialog.readImage(image);
            return null;
        });
        eventually(() -> fx(() -> !button(dialog("scan-dialog"), "confirm-qr").isDisabled()));
        fx(() -> {
            Parent pane = dialog("scan-dialog");
            assertEquals(1, reads.get());
            assertEquals(0, logins.get(), "Recognizing a QR must wait for explicit confirmation");
            assertTrue(pane.lookup("#qr-confirmation").isVisible());
            assertFalse(pane.lookup("#qr-role").isManaged());
            assertEquals("二次确认登录", button(pane, "confirm-qr").getText());
            snapshot(pane, "scan-confirmation");
            button(pane, "cancel-qr").fire();
            assertEquals(0, logins.get());
            assertFalse(pane.lookup("#qr-confirmation").isVisible());
            assertEquals("", ((TextArea) pane.lookup("#qr-input")).getText());
            ((TextArea) pane.lookup("#qr-input")).setText(KuroAuthServiceTest.QR);
            return null;
        });
        eventually(() -> fx(() -> !button(dialog("scan-dialog"), "confirm-qr").isDisabled()));
        fx(() -> {
            Parent pane = dialog("scan-dialog");
            assertEquals(2, reads.get(), "Pasted QR must also be scanned automatically");
            assertEquals(0, logins.get());
            button(pane, "confirm-qr").fire();
            button(pane, "confirm-qr").fire();
            return null;
        });
        fx(() -> {
            Parent pane = dialog("scan-dialog");
            assertEquals(1, logins.get());
            assertEquals("已登录", button(pane, "confirm-qr").getText());
            assertTrue(button(pane, "confirm-qr").isDisabled());
            close(pane);
            return null;
        });
    }

    @Test void editingQrInvalidatesPreviousConfirmationAndExpiredQrCannotSubmit() throws Exception {
        var expired = new CompletableFuture<ScanService.ScanResult>();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger logins = new AtomicInteger();
        ScanService scanner = new ScanService() {
            public CompletionStage<ScanResult> roles(AuthService.Session session, String qr) {
                return reads.incrementAndGet() == 1
                        ? CompletableFuture.completedFuture(new ScanResult(Status.SUCCESS, "扫码成功")) : expired;
            }
            public CompletionStage<ScanResult> scan(AuthService.Session session, String qr, String id, String sms) {
                logins.incrementAndGet();
                return CompletableFuture.completedFuture(new ScanResult(Status.SUCCESS, "成功"));
            }
            public CompletionStage<AuthService.CodeResult> sendScanCode(AuthService.Session session, String proof) {
                throw new AssertionError("Unexpected SMS");
            }
        };
        fx(() -> {
            var dialog = new ScanLoginDialog(null, new AuthService.Session("账号", "token"), scanner,
                    owner -> CompletableFuture.completedFuture(null), () -> fail("Unexpected invalid token"));
            dialog.show();
            ((TextArea) dialog.getDialogPane().lookup("#qr-input")).setText(KuroAuthServiceTest.QR);
            return null;
        });
        eventually(() -> fx(() -> !button(dialog("scan-dialog"), "confirm-qr").isDisabled()));
        fx(() -> {
            Parent pane = dialog("scan-dialog");
            ((TextArea) pane.lookup("#qr-input")).setText(KuroAuthServiceTest.QR + "new");
            assertTrue(button(pane, "confirm-qr").isDisabled());
            assertFalse(pane.lookup("#qr-confirmation").isVisible());
            return null;
        });
        eventually(() -> reads.get() == 2);
        expired.complete(new ScanService.ScanResult(ScanService.Status.EXPIRED, "二维码已过期"));
        fx(() -> {
            Parent pane = dialog("scan-dialog");
            assertTrue(label(pane, "scan-feedback").getText().contains("已过期"));
            assertTrue(button(pane, "confirm-qr").isDisabled());
            button(pane, "confirm-qr").fire();
            assertEquals(0, logins.get());
            close(pane);
            return null;
        });
    }

    static final class MemoryCredentials implements CredentialStore {
        AuthService.Session saved;
        public java.util.Optional<AuthService.Session> load() { return java.util.Optional.ofNullable(saved); }
        public void save(AuthService.Session session) { saved = session; }
        public void clear() { saved = null; }
    }

    @Test void officialCaptchaWindowCanOpenAndCancel() throws Exception {
        AtomicReference<CompletionStage<String>> result = new AtomicReference<>();
        var sdk = mockSdk("window.initGeetest4 = function() {};");
        try {
            fx(() -> {
                GeetestCaptcha captcha = new GeetestCaptcha(uri -> fail("Unexpected browser"), sdkUrl(sdk));
                result.set(captcha.verify(null));
                captcha.cancel();
                return null;
            });
            assertNull(result.get().toCompletableFuture().get(5, TimeUnit.SECONDS));
        } finally { sdk.stop(0); }
    }

    @Test void realWebViewInitializesSdkOnHttpOriginAndSendsSmsAfterCallback() throws Exception {
        var sdk = mockSdk("""
                window.initGeetest4 = function(config, complete) {
                  if (location.protocol !== 'http:' || config.clientType !== 'android' || config.product !== 'bind')
                    throw new Error('Wrong origin or configuration');
                  var success;
                  var widget = {
                    onReady: function(fn) { setTimeout(fn, 20); return widget; },
                    onSuccess: function(fn) { success = fn; return widget; },
                    onError: function(fn) { return widget; },
                    onClose: function(fn) { return widget; },
                    showCaptcha: function() { setTimeout(function(){success();}, 20); },
                    getValidate: function() { return {lot_number:'lot',captcha_output:'output',pass_token:'pass',gen_time:'123'}; },
                    destroy: function() {}
                  };
                  complete(widget);
                };
                """);
        AtomicInteger sends = new AtomicInteger();
        GeetestCaptcha captcha = new GeetestCaptcha(uri -> fail("Unexpected system browser"), sdkUrl(sdk));
        try {
            fx(() -> {
                AccountView view = persistentView(new AuthService() {
                    @Override public boolean requiresCaptcha() { return true; }
                    @Override public CompletionStage<CodeResult> sendCode(String phone, String proof) {
                        assertEquals(KuroAuthServiceTest.PROOF, proof);
                        sends.incrementAndGet();
                        return CompletableFuture.completedFuture(new CodeResult(true, "短信已发送"));
                    }
                }, new MemoryCredentials(), captcha);
                field(view, "phone-input").setText("13812345678");
                button(view, "phone-login").fire();
                assertEquals(0, sends.get());
                return null;
            });
            eventually(() -> sends.get() == 1);
            fx(() -> {
                assertTrue(button(dialog("phone-dialog"), "send-code").isDisabled());
                assertTrue(label(dialog("phone-dialog"), "sms-feedback").getText().contains("已发送"));
                return null;
            });
        } finally { fx(() -> { captcha.cancel(); return null; }); sdk.stop(0); }
    }

    @Test void initializationErrorIsVisibleAndSystemBrowserCallbackCompletesVerification() throws Exception {
        var sdk = mockSdk("window.initGeetest4 = function() { throw new Error('unsupported browser'); };");
        AtomicReference<java.net.URI> opened = new AtomicReference<>();
        GeetestCaptcha captcha = new GeetestCaptcha(opened::set, sdkUrl(sdk));
        CompletionStage<String> result = fx(() -> captcha.verify(null));
        try {
            try {
                eventually(() -> fx(() -> label(dialog("captcha-dialog"), "captcha-status").getText().contains("初始化或加载失败")));
            } catch (AssertionError error) {
                fail(fx(() -> {
                    Parent pane = dialog("captcha-dialog");
                    var engine = ((javafx.scene.web.WebView) pane.lookup("#captcha-web")).getEngine();
                    return label(pane, "captcha-status").getText() + " / " + engine.getLoadWorker().getState()
                            + " / " + engine.executeScript("document.body.textContent");
                }), error);
            }
            fx(() -> { button(dialog("captcha-dialog"), "captcha-browser").fire(); return null; });
            java.net.URI page = opened.get();
            assertNotNull(page);
            var client = java.net.http.HttpClient.newHttpClient();
            var response = client.send(java.net.http.HttpRequest.newBuilder(page).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var callback = java.net.http.HttpRequest.newBuilder(page.resolve("complete"))
                    .header("Origin", "http://" + page.getAuthority())
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(KuroAuthServiceTest.PROOF)).build();
            assertEquals(200, client.send(callback, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(KuroAuthServiceTest.PROOF, result.toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertThrows(java.io.IOException.class, () -> client.send(callback, java.net.http.HttpResponse.BodyHandlers.ofString()));
        } finally { fx(() -> { captcha.cancel(); return null; }); sdk.stop(0); }
    }

    private static com.sun.net.httpserver.HttpServer mockSdk(String script) throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sdk.js", exchange -> {
            byte[] content = script.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/javascript");
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String sdkUrl(com.sun.net.httpserver.HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/sdk.js";
    }

    private static void eventually(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (condition.call()) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for asynchronous verification");
    }

    private AccountView persistentView(AuthService service, CredentialStore store, CaptchaProvider captcha) {
        return persistentView(service, store, captcha, token -> {});
    }

    private AccountView persistentView(AuthService service, CredentialStore store, CaptchaProvider captcha, java.util.function.Consumer<String> copy) {
        AccountView view = new AccountView(service, store, captcha, copy);
        views.add(view);
        Stage stage = new Stage();
        stage.setScene(new Scene(view, 1100, 800));
        stage.show();
        view.applyCss();
        view.layout();
        return view;
    }

    @Test void pairedTokenCanBeImportedExportedAndRestoredWithItsOwnDevice() throws Exception {
        String jwt = KuroAuthServiceTest.token("123", null);
        String did = KuroAuthServiceTest.IMPORTED_DEVICE;
        MemoryCredentials store = new MemoryCredentials();
        AtomicReference<String> copied = new AtomicReference<>();
        AtomicInteger validations = new AtomicInteger();
        AuthService service = new AuthService() {
            @Override public CompletionStage<Result> loginWithToken(String token, String deviceId) {
                assertEquals(jwt, token);
                assertEquals(did, deviceId);
                validations.incrementAndGet();
                return CompletableFuture.completedFuture(new Result(true, "成功", new Session("账号", token, deviceId)));
            }
        };
        AccountView current = fx(() -> {
            var view = persistentView(service, store, owner -> CompletableFuture.completedFuture(null), copied::set);
            field(view, "token-input").setText("添加token " + jwt + "， " + did);
            button(view, "login-button").fire();
            return view;
        });
        fx(() -> {
            assertEquals(did, store.saved.deviceId());
            button(current, "get-token").fire();
            Parent pane = dialog("token-dialog");
            assertEquals(jwt, field(pane, "export-token").getText());
            assertEquals(did, field(pane, "export-device").getText());
            button(pane, "copy-token-device").fire();
            assertEquals(jwt + ", " + did, copied.get());
            button(pane, "copy-token").fire();
            assertEquals(jwt, copied.get());
            close(pane);
            current.dispose();
            current.getScene().getWindow().hide();
            var reopened = persistentView(service, store, owner -> CompletableFuture.completedFuture(null));
            reopened.restoreSession();
            return null;
        });
        fx(() -> { assertEquals(2, validations.get()); assertEquals(did, store.saved.deviceId()); return null; });
    }

    @Test void liveQrOpensConfirmationAndLogoutStopsCapture() throws Exception {
        AtomicInteger reads = new AtomicInteger(), logins = new AtomicInteger();
        class LiveAuth implements AuthService, ScanService {
            public CompletionStage<Result> loginWithToken(String token) {
                return CompletableFuture.completedFuture(new Result(true,"成功",new Session("测试账号",token)));
            }
            public CompletionStage<ScanResult> roles(Session session, String qr) {
                reads.incrementAndGet(); return CompletableFuture.completedFuture(new ScanResult(Status.SUCCESS,"扫码成功"));
            }
            public CompletionStage<ScanResult> scan(Session session, String qr, String role, String sms) {
                logins.incrementAndGet(); return CompletableFuture.completedFuture(new ScanResult(Status.SUCCESS,"成功"));
            }
            public CompletionStage<CodeResult> sendScanCode(Session session, String proof) { throw new AssertionError(); }
        }
        BlockingQueue<VideoFrame> frames = new LinkedBlockingQueue<>();
        CountDownLatch closed = new CountDownLatch(1);
        var scanner = new LiveScanService(room -> new LiveScanService.FrameSource() {
            public VideoFrame read() throws InterruptedException { return frames.take(); }
            public void close() { closed.countDown(); }
        }, () -> new LiveScanService.Decoder() {
            public java.util.List<String> decode(VideoFrame frame) { return java.util.List.of(KuroAuthServiceTest.QR); }
            public void close() { }
        }, java.time.Duration.ofSeconds(5));
        AccountView current = fx(() -> {
            var v = persistentView(new LiveAuth(),new MemoryCredentials(),owner -> CompletableFuture.completedFuture(null));
            v.setLiveScanner(scanner);
            field(v,"token-input").setText("test-token"); button(v,"login-button").fire(); return v;
        });
        fx(() -> {
            field(current,"room-input").setText("123456"); button(current,"monitor-button").fire();
            assertTrue(field(current,"room-input").isDisabled()); return null;
        });
        frames.add(new VideoFrame(1,1,3,new byte[3],0,System.nanoTime()));
        eventually(() -> reads.get()==1);
        fx(() -> {
            var pane = dialog("scan-dialog");
            assertFalse(button(pane,"confirm-qr").isDisabled()); assertEquals(0,logins.get());
            button(pane,"confirm-qr").fire(); return null;
        });
        eventually(() -> logins.get()==1);
        fx(() -> { close(dialog("scan-dialog")); button(current,"logout-button").fire(); return null; });
        assertTrue(closed.await(3,TimeUnit.SECONDS)); assertFalse(scanner.running());
    }

    private AccountView view(AuthService auth, boolean demo, java.util.function.Consumer<String> copy) {
        AccountView view = new AccountView(auth, demo, copy);
        views.add(view);
        Stage stage = new Stage();
        stage.setScene(new Scene(view, 1100, 800));
        stage.show();
        view.applyCss();
        view.layout();
        return view;
    }

    private static Parent dialog(String id) {
        return Window.getWindows().stream().map(window -> window.getScene().getRoot())
                .filter(root -> root.lookup("#" + id) != null).findFirst().orElseThrow();
    }

    private static void close(Parent root) {
        DialogPane pane = root instanceof DialogPane dialog ? dialog : (DialogPane) root.lookup(".dialog-pane");
        ButtonType type = pane.getButtonTypes().contains(ButtonType.CANCEL) ? ButtonType.CANCEL : ButtonType.CLOSE;
        ((Button) pane.lookupButton(type)).fire();
    }

    private static void snapshot(Parent root, String name) throws Exception {
        String directory = System.getProperty("preview.dir");
        if (directory == null) return;
        root.applyCss();
        root.layout();
        Path path = Path.of(directory, name + ".png").toAbsolutePath();
        Files.createDirectories(path.getParent());
        ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null, null), null), "png", path.toFile());
    }

    private static Button button(Parent root, String id) { return (Button) root.lookup("#" + id); }
    private static TextField field(Parent root, String id) { return (TextField) root.lookup("#" + id); }
    private static Label label(Parent root, String id) { return (Label) root.lookup("#" + id); }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}
