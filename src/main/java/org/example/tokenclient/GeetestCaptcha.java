package org.example.tokenclient;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Worker;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Window;
import javafx.util.Duration;

/** Official widget on a real HTTP origin, with a system browser option for WebKit compatibility. */
final class GeetestCaptcha implements CaptchaProvider {
    private final Consumer<URI> openBrowser;
    private final String sdk;
    private Dialog<String> active;

    GeetestCaptcha() {
        this(uri -> {
            try { Desktop.getDesktop().browse(uri); }
            catch (Exception error) { throw new IllegalStateException("无法打开系统浏览器"); }
        });
    }

    GeetestCaptcha(Consumer<URI> openBrowser) { this(openBrowser, LoopbackCaptcha.SDK); }
    GeetestCaptcha(Consumer<URI> openBrowser, String sdk) { this.openBrowser = openBrowser; this.sdk = sdk; }

    @Override public CompletionStage<String> verify(Window owner) {
        cancel();
        final LoopbackCaptcha challenge;
        try { challenge = new LoopbackCaptcha(sdk); }
        catch (java.io.IOException error) { return CompletableFuture.failedFuture(new IllegalStateException("无法启动本地验证页面")); }
        var result = new CompletableFuture<String>();
        Dialog<String> dialog = new Dialog<>();
        active = dialog;
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("人机验证 · 请按提示点选符号");
        dialog.setResultConverter(button -> null);
        dialog.getDialogPane().setId("captcha-dialog");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
        Label tip = new Label("正在加载验证组件；验证通过后将发送短信。");
        tip.setId("captcha-status");
        tip.setWrapText(true);
        Button external = new Button("在系统浏览器完成验证");
        external.setId("captcha-browser");
        WebView web;
        Node content;
        try {
            web = new WebView();
            web.setId("captcha-web");
            web.setContextMenuEnabled(false);
            web.setPrefSize(540, 480);
            web.getEngine().setCreatePopupHandler(config -> null);
            content = web;
        } catch (RuntimeException | LinkageError error) {
            web = null;
            content = new Label("内置浏览器不可用，请点击上方按钮使用系统浏览器。");
        }
        final WebView embedded = web;
        external.setOnAction(event -> {
            try {
                openBrowser.accept(challenge.page());
                // Avoid having two copies of the widget requesting challenges at once.
                if (embedded != null) embedded.getEngine().load(null);
                tip.setText("请在系统浏览器中完成验证，结果会自动返回此窗口。");
            } catch (RuntimeException error) { tip.setText("无法自动打开系统浏览器，请检查默认浏览器设置后重试。"); }
        });
        dialog.getDialogPane().setContent(new VBox(12, tip, external, content));
        long started = System.nanoTime();
        Timeline poll = new Timeline(new KeyFrame(Duration.millis(250), event -> {
            String proof = challenge.proof();
            if (proof != null) { dialog.setResult(proof); dialog.close(); return; }
            switch (challenge.status()) {
                case "sdk-error" -> tip.setText("极验脚本下载失败，请检查网络，或在系统浏览器完成验证。");
                case "init-error", "widget-error" -> tip.setText("验证组件初始化或加载失败，请使用系统浏览器完成验证。");
                case "ready" -> tip.setText("请按图案提示完成验证，通过后自动发送短信。");
                case "closed" -> tip.setText("验证已收起，可点击页面中的“显示验证符号”继续。");
                default -> {
                    if (System.nanoTime() - started > java.time.Duration.ofSeconds(15).toNanos())
                        tip.setText("验证组件加载较慢或不兼容，请尝试系统浏览器并检查网络。");
                }
            }
        }));
        poll.setCycleCount(480);
        poll.setOnFinished(event -> {
            tip.setText("验证已超时，请取消后重新获取验证符号。");
            external.setDisable(true);
            challenge.close();
            if (embedded != null) embedded.getEngine().load(null);
        });
        dialog.setOnHidden(event -> {
            poll.stop();
            challenge.close();
            if (embedded != null) embedded.getEngine().load(null);
            if (active == dialog) active = null;
            result.complete(dialog.getResult());
        });
        dialog.show();
        if (embedded != null) {
            embedded.getEngine().getLoadWorker().stateProperty().addListener((obs, previous, state) -> {
                if (state == Worker.State.FAILED && dialog.isShowing())
                    tip.setText("内置验证页面加载失败，请在系统浏览器中完成验证。");
            });
            embedded.getEngine().locationProperty().addListener((obs, previous, location) -> {
                if (location != null && !location.isEmpty() && !location.equals("about:blank")
                        && !location.equals(challenge.page().toString())) embedded.getEngine().load(null);
            });
            try { embedded.getEngine().load(challenge.page().toString()); }
            catch (RuntimeException error) { tip.setText("内置验证页面加载失败，请在系统浏览器中完成验证。"); }
        }
        poll.playFromStart();
        return result;
    }

    @Override public void cancel() { if (active != null) active.close(); }
}
