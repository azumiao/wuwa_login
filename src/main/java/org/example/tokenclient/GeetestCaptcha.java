package org.example.tokenclient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Window;
import javafx.util.Duration;

/** Displays the official GeeTest v4 widget; the user completes the challenge. */
final class GeetestCaptcha implements CaptchaProvider {
    private Dialog<String> active;

    @Override public CompletionStage<String> verify(Window owner) {
        cancel();
        var result = new CompletableFuture<String>();
        Dialog<String> dialog = new Dialog<>();
        active = dialog;
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("人机验证 · 请按提示点选符号");
        dialog.setResultConverter(button -> null);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
        WebView web = new WebView();
        web.setContextMenuEnabled(false);
        web.setPrefSize(540, 480);
        Label tip = new Label("正在加载验证符号，请完成验证；通过后将发送短信。");
        tip.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(10, tip, web));
        // Poll plain strings instead of exposing a Java object to remote scripts.
        Timeline poll = new Timeline(new KeyFrame(Duration.millis(250), event -> {
            try {
                Object proof = web.getEngine().executeScript("window.kuroProof || ''");
                if (proof instanceof String value && KuroAuthService.validProof(value)) {
                    dialog.setResult(value);
                    dialog.close();
                }
                Object failure = web.getEngine().executeScript("window.kuroFailure || false");
                if (Boolean.TRUE.equals(failure)) tip.setText("验证加载失败，请检查网络，取消后重试。");
            } catch (RuntimeException ignored) { /* The document may still be loading. */ }
        }));
        poll.setCycleCount(480);
        poll.setOnFinished(event -> {
            tip.setText("验证超时，请取消后重新获取验证符号。");
            web.getEngine().load(null);
        });
        web.getEngine().setCreatePopupHandler(config -> null);
        web.getEngine().locationProperty().addListener((obs, old, location) -> {
            if (location != null && !location.isEmpty() && !location.equals("about:blank")) dialog.close();
        });
        dialog.setOnHidden(event -> {
            poll.stop();
            String proof = dialog.getResult();
            web.getEngine().load(null);
            if (active == dialog) active = null;
            result.complete(proof);
        });
        web.getEngine().loadContent("""
                <!doctype html><html lang="zh-CN"><head><meta charset="UTF-8">
                <meta name="referrer" content="no-referrer">
                <style>body{font:16px sans-serif;padding:24px;color:#263044}#captcha{margin-top:24px}</style>
                </head><body><p id="status">请根据图案提示完成验证</p><div id="captcha"></div>
                <script>
                window.kuroProof='';window.kuroFailure=false;
                function start(){
                  initGeetest4({captchaId:'3f7e2d848ce0cb7e7d019d621e556ce2',product:'bind',language:'zho'},function(c){
                    c.onReady(function(){c.showCaptcha();});
                    c.onSuccess(function(){window.kuroProof=JSON.stringify(c.getValidate());});
                    c.onError(function(){window.kuroFailure=true;});
                    c.onClose(function(){document.getElementById('status').textContent='已关闭验证，请取消后重试';});
                  });
                }
                </script><script src="https://static.geetest.com/v4/gt4.js" onload="start()" onerror="window.kuroFailure=true"></script>
                </body></html>
                """);
        dialog.show();
        poll.playFromStart();
        return result;
    }

    @Override public void cancel() { if (active != null) active.close(); }
}
