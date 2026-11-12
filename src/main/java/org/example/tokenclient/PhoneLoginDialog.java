package org.example.tokenclient;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.Duration;

final class PhoneLoginDialog extends Dialog<AuthService.Session> {
    private final TextField code = new TextField();
    private final Button send = new Button("获取验证码");
    private final Button verify = new Button("验证并登录");
    private final Label feedback = new Label("获取验证码后，输入 6 位短信验证码。");
    private final Timeline cooldown = new Timeline();
    private final AuthService auth;
    private final CaptchaProvider captcha;
    private final String phone;
    private int remaining;
    private long generation;
    private boolean busy;

    PhoneLoginDialog(Window owner, String phone, AuthService auth, boolean demo) {
        this(owner, phone, auth, demo, new GeetestCaptcha());
    }

    PhoneLoginDialog(Window owner, String phone, AuthService auth, boolean demo, CaptchaProvider captcha) {
        this.phone = phone;
        this.auth = auth;
        this.captcha = captcha;
        if (owner != null) initOwner(owner);
        setTitle("手机号登录");
        setResultConverter(button -> null);
        getDialogPane().setId("phone-dialog");
        getDialogPane().getStylesheets().add(AccountView.class.getResource("account.css").toExternalForm());
        getDialogPane().getStyleClass().add("app");
        getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        ((Button) getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
        Label heading = new Label("验证你的手机号");
        heading.getStyleClass().add("section-title");
        Label number = new Label("手机号：" + AccountView.maskPhone(phone));
        number.getStyleClass().add("muted");
        code.setId("sms-code");
        code.setPromptText("输入 6 位验证码");
        code.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().matches("[0-9]{0,6}") ? change : null));
        send.setId("send-code");
        send.getStyleClass().add("secondary");
        HBox codeRow = new HBox(10, code, send);
        HBox.setHgrow(code, Priority.ALWAYS);
        verify.setId("verify-phone");
        verify.getStyleClass().add("primary");
        verify.setMaxWidth(Double.MAX_VALUE);
        verify.setDefaultButton(true);
        code.setOnAction(event -> verify.fire());
        feedback.setId("sms-feedback");
        feedback.setWrapText(true);
        feedback.setMinHeight(40);
        feedback.getStyleClass().add("feedback");
        if (demo) feedback.setText("界面演示，不会发送真实短信。验证码：123456。");
        VBox content = new VBox(18, heading, number, codeRow, verify, feedback);
        content.setPadding(new Insets(20));
        content.setPrefWidth(440);
        getDialogPane().setContent(content);
        send.setOnAction(event -> sendCode());
        verify.setOnAction(event -> login());
        if (auth.requiresCaptcha()) setOnShown(event -> sendCode());
        setOnHiding(event -> { generation++; cooldown.stop(); captcha.cancel(); code.clear(); });
    }

    private void sendCode() {
        if (busy || remaining > 0) return;
        busy(true);
        long request = generation;
        feedback.setText(auth.requiresCaptcha() ? "请完成验证符号，通过后发送短信。" : "正在获取验证码…");
        try {
            var proof = auth.requiresCaptcha() ? captcha.verify(getDialogPane().getScene().getWindow())
                    : java.util.concurrent.CompletableFuture.completedFuture("");
            proof.whenComplete((value, failure) -> onFx(() -> {
                if (request != generation || !isShowing()) return;
                if (failure != null || value == null) {
                    busy(false);
                    feedback.setText("已取消或未完成人机验证，可重新获取验证码。");
                    return;
                }
                sendVerified(request, value);
            }));
        } catch (RuntimeException error) {
            busy(false);
            feedback.setText("短信服务暂不可用，请稍后重试。");
        }
    }

    private void sendVerified(long request, String proof) {
        try {
            auth.sendCode(phone, proof).whenComplete((result, error) -> Platform.runLater(() -> {
                if (request != generation || !isShowing()) return;
                busy(false);
                if (error != null || result == null) {
                    feedback.setText("验证码获取失败，请稍后重试。");
                } else {
                    feedback.setText(result.message());
                    if (result.success()) startCooldown();
                }
            }));
        } catch (RuntimeException error) {
            busy(false);
            feedback.setText("短信服务暂不可用，请稍后重试。");
        }
    }

    private static void onFx(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }

    private void startCooldown() {
        remaining = 60;
        updateSend();
        cooldown.getKeyFrames().setAll(new KeyFrame(Duration.seconds(1), event -> {
            remaining--;
            updateSend();
        }));
        cooldown.setCycleCount(60);
        cooldown.playFromStart();
    }

    private void login() {
        if (busy) return;
        if (!code.getText().matches("[0-9]{6}")) {
            feedback.setText("请输入 6 位验证码。");
            return;
        }
        busy(true);
        long request = generation;
        try {
            auth.loginWithPhone(phone, code.getText()).whenComplete((result, error) -> Platform.runLater(() -> {
                if (request != generation || !isShowing()) return;
                busy(false);
                if (error != null || result == null) {
                    feedback.setText("登录失败，请检查网络或稍后重试。");
                } else if (result.success()) {
                    code.clear();
                    setResult(result.session());
                    close();
                } else {
                    feedback.setText(result.message());
                }
            }));
        } catch (RuntimeException error) {
            busy(false);
            feedback.setText("登录服务暂不可用，请稍后重试。");
        }
    }

    private void busy(boolean value) {
        busy = value;
        code.setDisable(value);
        verify.setDisable(value);
        verify.setText(value ? "请稍候…" : "验证并登录");
        updateSend();
    }

    private void updateSend() {
        send.setDisable(busy || remaining > 0);
        send.setText(remaining > 0 ? remaining + " 秒后重试" : "获取验证码");
    }
}
