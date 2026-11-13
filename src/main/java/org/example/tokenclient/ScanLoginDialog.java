package org.example.tokenclient;

import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

/** Shared confirmation flow for manual images, text and live-stream recognition. */
final class ScanLoginDialog extends Dialog<Void> {
    private final AuthService.Session session;
    private final ScanService service;
    private final CaptchaProvider captcha;
    private final Runnable invalidToken;
    private final TextArea qr = new TextArea();
    private final ComboBox<ScanService.Role> roles = new ComboBox<>();
    private final Button read = new Button("扫描二维码");
    private final Button image = new Button("选择二维码图片");
    private final Button confirm = new Button("二次确认登录");
    private final Button cancelScan = new Button("取消本次扫码");
    private final Label confirmationTitle = new Label("扫码成功 · 请二次确认登录");
    private final VBox confirmation = new VBox(12);
    private final Button send = new Button("获取短信验证码");
    private final TextField code = new TextField();
    private final Label feedback = new Label("粘贴你要登录的游戏二维码内容，或选择二维码图片。");
    private final HBox sms = new HBox(10, code, send);
    private final Timeline cooldown = new Timeline();
    private final PauseTransition autoRead = new PauseTransition(Duration.millis(400));
    private boolean busy;
    private boolean needsSms;
    private boolean completed;
    private boolean scanReady;
    private boolean resizeScheduled;
    private int remaining;
    private long generation;
    private Consumer<String> terminalCode = ignored -> { };

    void setOnTerminalCode(Consumer<String> callback) { terminalCode = callback; }

    ScanLoginDialog(Window owner, AuthService.Session session, ScanService service, CaptchaProvider captcha, Runnable invalidToken) {
        this.session = session;
        this.service = service;
        this.captcha = captcha;
        this.invalidToken = invalidToken;
        if (owner != null) initOwner(owner);
        setTitle("扫码登录游戏");
        getDialogPane().setId("scan-dialog");
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ((Button) getDialogPane().lookupButton(ButtonType.CLOSE)).setText("关闭");
        getDialogPane().getStylesheets().add(AccountView.class.getResource("account.css").toExternalForm());
        getDialogPane().getStyleClass().add("app");
        qr.setId("qr-input");
        qr.setPromptText("游戏登录二维码内容（含 KURO）");
        qr.setPrefRowCount(3);
        qr.setWrapText(true);
        read.setId("read-qr");
        read.setOnAction(event -> loadRoles());
        image.setOnAction(event -> readImage());
        roles.setId("qr-role");
        roles.setMaxWidth(Double.MAX_VALUE);
        roles.setPromptText("读取二维码后选择角色");
        roles.valueProperty().addListener((obs, old, value) -> update());
        confirm.setId("confirm-qr");
        confirm.getStyleClass().add("primary");
        confirm.setOnAction(event -> confirm());
        cancelScan.setId("cancel-qr");
        cancelScan.getStyleClass().add("secondary");
        cancelScan.setOnAction(event -> {
            if (busy) return;
            qr.clear();
            feedback.setText("本次扫码已结束，可以选择新的二维码。");
        });
        send.setId("send-scan-code");
        send.setOnAction(event -> sendCode());
        code.setId("scan-code");
        code.setPromptText("6 位短信验证码");
        code.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().matches("[0-9]{0,6}") ? change : null));
        feedback.setId("scan-feedback");
        feedback.setWrapText(true);
        confirmationTitle.setId("qr-confirmation-title");
        confirmationTitle.getStyleClass().add("section-title");
        Label account = new Label("登录账号：" + AccountView.maskPhone(session.account()));
        Label tip = new Label("请确认这是你要登录的游戏设备，点击下方按钮后继续登录。");
        tip.setWrapText(true);
        confirmation.setId("qr-confirmation");
        confirmation.getStyleClass().add("card");
        confirmation.getChildren().setAll(confirmationTitle, account, roles, tip, sms, new HBox(10, confirm, cancelScan));
        autoRead.setOnFinished(event -> loadRoles());
        qr.textProperty().addListener((obs, old, value) -> {
            reset();
            if (KuroAuthService.validQr(value.strip())) {
                feedback.setText("已识别二维码，正在准备扫描…");
                autoRead.playFromStart();
            } else feedback.setText("请输入有效的库洛游戏二维码，或选择二维码图片。");
        });
        VBox body = new VBox(16, new Label("请确认这是你要登录的游戏设备。首次扫码可能需要短信验证。"),
                qr, new HBox(10, image, read), confirmation, feedback);
        body.setPrefWidth(620);
        body.setPadding(new Insets(20));
        getDialogPane().setContent(body);
        setOnHiding(event -> { generation++; cooldown.stop(); captcha.cancel(); code.clear(); qr.clear(); autoRead.stop(); });
        update();
    }

    private void reset() {
        generation++;
        autoRead.stop();
        scanReady = false;
        roles.getItems().clear();
        needsSms = false;
        completed = false;
        code.clear();
        update();
    }

    private void loadRoles() {
        if (busy) return;
        if (!KuroAuthService.validQr(qr.getText().strip())) { feedback.setText("请输入有效的库洛游戏二维码内容。"); return; }
        reset();
        feedback.setText("正在扫描二维码，请稍候…");
        execute(() -> service.roles(session, qr.getText().strip()), result -> {
            if (handle(result)) return;
            if (result.status() == ScanService.Status.SUCCESS) {
                scanReady = true;
                roles.getItems().setAll(result.roles());
                if (!roles.getItems().isEmpty()) roles.getSelectionModel().selectFirst();
                feedback.setText("扫码成功，请点击“二次确认登录”继续。");
            }
        });
    }

    private void confirm() {
        if (busy || completed || !scanReady || (!roles.getItems().isEmpty() && roles.getValue() == null)) return;
        if (needsSms && !code.getText().matches("[0-9]{6}")) { feedback.setText("请输入 6 位短信验证码。"); return; }
        String payload = qr.getText().strip();
        String id = roles.getValue() == null ? "" : roles.getValue().id();
        String verification = needsSms ? code.getText() : "";
        execute(() -> service.scan(session, payload, id, verification), result -> {
            if (handle(result)) return;
            if (result.status() == ScanService.Status.SMS_REQUIRED) {
                needsSms = true;
                code.clear();
            } else if (result.status() == ScanService.Status.SUCCESS) {
                code.clear();
                completed = true;
                terminalCode.accept(payload);
            }
        });
    }

    private boolean handle(ScanService.ScanResult result) {
        feedback.setText(result.message());
        if (result.status() == ScanService.Status.INVALID_TOKEN) { close(); invalidToken.run(); return true; }
        if (result.status() == ScanService.Status.EXPIRED) { terminalCode.accept(qr.getText().strip()); reset(); return true; }
        return false;
    }

    private void execute(java.util.function.Supplier<CompletionStage<ScanService.ScanResult>> action, Consumer<ScanService.ScanResult> done) {
        busy = true;
        update();
        long request = generation;
        try {
            action.get().whenComplete((result, error) -> Platform.runLater(() -> {
                if (request != generation || !isShowing()) return;
                busy = false;
                if (error != null || result == null) feedback.setText("网络请求失败，请重试。");
                else done.accept(result);
                update();
            }));
        } catch (RuntimeException error) { busy = false; feedback.setText("扫码服务暂不可用，请重试。"); update(); }
    }

    private void sendCode() {
        if (busy || remaining > 0 || !needsSms) return;
        busy = true;
        update();
        long request = generation;
        try {
            captcha.verify(getDialogPane().getScene().getWindow()).whenComplete((proof, error) -> Platform.runLater(() -> {
                if (request != generation || !isShowing()) return;
                if (error != null || proof == null) { busy = false; feedback.setText("未完成人机验证，可重新获取短信。"); update(); return; }
                sendVerified(request, proof);
            }));
        } catch (RuntimeException error) { busy = false; feedback.setText("人机验证暂不可用。"); update(); }
    }

    private void sendVerified(long request, String proof) {
        try {
            service.sendScanCode(session, proof).whenComplete((result, error) -> Platform.runLater(() -> {
                if (request != generation || !isShowing()) return;
                busy = false;
                if (error != null || result == null) feedback.setText("短信发送失败，请重试。");
                else {
                    feedback.setText(result.message());
                    if (result.invalidToken()) { close(); invalidToken.run(); return; }
                    if (result.success()) {
                        remaining = 60;
                        cooldown.getKeyFrames().setAll(new KeyFrame(Duration.seconds(1), event -> { remaining--; update(); }));
                        cooldown.setCycleCount(60);
                        cooldown.playFromStart();
                    }
                }
                update();
            }));
        } catch (RuntimeException error) { busy = false; feedback.setText("短信服务暂不可用。"); update(); }
    }

    private void readImage() {
        if (busy) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择游戏登录二维码");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("二维码图片", "*.png", "*.jpg", "*.jpeg", "*.bmp"));
        var file = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
        if (file == null) return;
        readImage(file.toPath());
    }

    void readImage(java.nio.file.Path file) {
        if (busy) return;
        reset();
        feedback.setText("正在识别二维码图片…");
        busy = true;
        update();
        long request = generation;
        CompletableFuture.supplyAsync(() -> {
            try {
                if (java.nio.file.Files.size(file) > 20_000_000) throw new IllegalArgumentException();
                var picture = ImageIO.read(file.toFile());
                if (picture == null) throw new IllegalArgumentException();
                try (var decoder = new WechatQrDecoder()) {
                    return decoder.decode(VideoFrame.fromImage(picture)).stream()
                            .filter(KuroAuthService::validQr).findFirst().orElseThrow();
                }
            } catch (Exception error) { throw new CompletionException(new IllegalArgumentException("无法读取二维码")); }
        }).whenComplete((value, error) -> Platform.runLater(() -> {
            if (request != generation || !isShowing()) return;
            busy = false;
            if (error != null) feedback.setText("未识别到二维码，请换一张清晰图片。");
            else {
                qr.setText(value);
                loadRoles();
            }
            update();
        }));
    }

    void acceptLiveCode(String value) {
        if (busy || scanReady || !isShowing()) return;
        qr.setText(value);
        autoRead.stop();
        loadRoles();
    }

    private void update() {
        boolean layoutChanged = confirmation.isManaged() != scanReady || sms.isManaged() != needsSms
                || roles.isManaged() != !roles.getItems().isEmpty();
        qr.setDisable(busy);
        read.setDisable(busy);
        image.setDisable(busy);
        read.setText(scanReady ? "重新扫描二维码" : "扫描二维码");
        confirmation.setVisible(scanReady);
        confirmation.setManaged(scanReady);
        confirmationTitle.setText(completed ? "游戏登录成功" : needsSms ? "请完成短信验证" : "扫码成功 · 请二次确认登录");
        roles.setVisible(!roles.getItems().isEmpty());
        roles.setManaged(!roles.getItems().isEmpty());
        roles.setDisable(busy || needsSms || completed);
        confirm.setDisable(busy || completed || !scanReady || (!roles.getItems().isEmpty() && roles.getValue() == null));
        confirm.setText(completed ? "已登录" : needsSms ? "验证短信并登录游戏" : "二次确认登录");
        cancelScan.setDisable(busy);
        cancelScan.setText(completed ? "扫描其他二维码" : "取消本次扫码");
        sms.setVisible(needsSms);
        sms.setManaged(needsSms);
        code.setDisable(busy || completed);
        send.setDisable(busy || completed || remaining > 0);
        send.setText(remaining > 0 ? remaining + " 秒后重试" : "获取短信验证码");
        if (layoutChanged && isShowing() && !resizeScheduled) {
            resizeScheduled = true;
            Platform.runLater(() -> {
                resizeScheduled = false;
                // Grow to reveal confirmation/SMS controls, keeping the window stable on cancellation.
                if (isShowing() && getDialogPane().prefHeight(getDialogPane().getWidth()) > getDialogPane().getHeight())
                    getDialogPane().getScene().getWindow().sizeToScene();
            });
        }
    }
}
