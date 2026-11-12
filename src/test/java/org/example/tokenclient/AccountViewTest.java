package org.example.tokenclient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountViewTest {
    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        assertTrue(started.await(15, TimeUnit.SECONDS));
    }

    @Test
    void fixedPlatformAndInitialPreview() throws Exception {
        fx(() -> {
            AccountView view = view(TokenAuthService.unconfigured());
            Stage stage = new Stage();
            try {
                stage.setScene(new Scene(view, 1100, 800));
                stage.show();
                view.applyCss();
                view.layout();
                assertTrue(view.lookupAll(".combo-box").isEmpty());
                assertEquals("●  未登录", label(view, "login-status").getText());
                String preview = System.getProperty("preview.path");
                if (preview != null) {
                    Path path = Path.of(preview).toAbsolutePath();
                    Files.createDirectories(path.getParent());
                    ImageIO.write(SwingFXUtils.fromFXImage(view.snapshot(null, null), null), "png", path.toFile());
                }
            } finally { stage.close(); }
            return null;
        });
    }

    @Test
    void invalidInputNeverCallsAuthenticationAndRevealKeepsInput() throws Exception {
        fx(() -> {
            AccountView view = view(token -> { fail("Invalid tokens must not be submitted"); return null; });
            button(view, "login-button").fire();
            assertEquals("请先输入 Token。", label(view, "feedback").getText());
            field(view, "token-input").setText("bad token");
            button(view, "login-button").fire();
            assertTrue(label(view, "feedback").getText().contains("不能包含"));
            field(view, "token-input").setText("test-token");
            ((ToggleButton) view.lookup("#reveal-token")).fire();
            assertTrue(field(view, "token-visible").isVisible());
            assertEquals("test-token", field(view, "token-visible").getText());
            field(view, "token-visible").setText("edited-token");
            ((ToggleButton) view.lookup("#reveal-token")).fire();
            assertFalse(field(view, "token-visible").isVisible());
            assertEquals("edited-token", field(view, "token-input").getText());
            return null;
        });
    }

    @Test
    void tokenAcquisitionUsesOnlyConfiguredUrl() throws Exception {
        fx(() -> {
            AccountView missing = view(TokenAuthService.unconfigured());
            button(missing, "get-token").fire();
            assertTrue(label(missing, "feedback").getText().contains("尚未配置"));
            URI url = URI.create("https://example.com/settings/tokens");
            AtomicReference<URI> opened = new AtomicReference<>();
            AccountView configured = new AccountView(new PlatformConfig("测试平台", Optional.of(url)),
                    TokenAuthService.unconfigured(), opened::set);
            button(configured, "get-token").fire();
            assertEquals(url, opened.get());
            assertThrows(IllegalArgumentException.class,
                    () -> new PlatformConfig("平台", Optional.of(URI.create("file:///tmp/token"))));
            return null;
        });
    }

    @Test
    void asynchronousSuccessDisablesDuplicateLoginAndClearsToken() throws Exception {
        CompletableFuture<TokenAuthService.LoginResult> response = new CompletableFuture<>();
        AccountView view = fx(() -> {
            AccountView current = view(token -> { assertEquals("test-token", token); return response; });
            field(current, "token-input").setText("  test-token  ");
            button(current, "login-button").fire();
            assertTrue(button(current, "login-button").isDisabled());
            assertTrue(field(current, "token-input").isDisabled());
            return current;
        });
        response.complete(new TokenAuthService.LoginResult(true, "登录成功"));
        fx(() -> {
            assertEquals("●  已登录", label(view, "login-status").getText());
            assertFalse(button(view, "login-button").isDisabled());
            assertEquals("", field(view, "token-input").getText());
            assertEquals("", field(view, "token-visible").getText());
            return null;
        });
    }

    @Test
    void unconfiguredAndExceptionalServicesNeverClaimSuccess() throws Exception {
        AccountView unconfigured = fx(() -> {
            AccountView view = view(TokenAuthService.unconfigured());
            field(view, "token-input").setText("test-token");
            button(view, "login-button").fire();
            return view;
        });
        fx(() -> {
            assertEquals("●  未登录", label(unconfigured, "login-status").getText());
            assertTrue(label(unconfigured, "feedback").getText().contains("尚未接入"));
            return null;
        });
        AccountView failed = fx(() -> {
            AccountView view = view(token -> CompletableFuture.failedFuture(new RuntimeException("secret-token")));
            field(view, "token-input").setText("test-token");
            button(view, "login-button").fire();
            return view;
        });
        fx(() -> {
            assertEquals("●  未登录", label(failed, "login-status").getText());
            assertFalse(label(failed, "feedback").getText().contains("secret-token"));
            assertFalse(button(failed, "login-button").isDisabled());
            return null;
        });
    }

    private static AccountView view(TokenAuthService service) {
        return new AccountView(new PlatformConfig("默认平台", Optional.empty()), service,
                uri -> fail("No URL should be opened without configuration"));
    }

    private static void prepare(AccountView view) {
        if (view.getScene() == null) new Scene(view, 1100, 740);
        view.applyCss();
        view.layout();
    }

    private static Button button(AccountView view, String id) { prepare(view); return (Button) view.lookup("#" + id); }
    private static TextField field(AccountView view, String id) { prepare(view); return (TextField) view.lookup("#" + id); }
    private static Label label(AccountView view, String id) { prepare(view); return (Label) view.lookup("#" + id); }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}
