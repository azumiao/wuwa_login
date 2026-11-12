package org.example.tokenclient;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
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

/** Manual QR entry; future stream recognition can feed the same ScanService boundary. */
final class ScanLoginDialog extends Dialog<Void> {
    private final AuthService.Session session;
    private final ScanService service;
    private final CaptchaProvider captcha;
    private final Runnable invalidToken;
    private final TextArea qr = new TextArea();
    private final ComboBox<ScanService.Role> roles = new ComboBox<>();
    private final Button read = new Button("读取角色");
    private final Button image = new Button("选择二维码图片");
    private final Button confirm = new Button("确认登录游戏");
    private final Button send = new Button("获取短信验证码");
    private final TextField code = new TextField();
    private final Label feedback = new Label("粘贴你要登录的游戏二维码内容，或选择二维码图片。");
    private final HBox sms = new HBox(10, code, send);
    private final Timeline cooldown = new Timeline();
    private boolean busy;
    private boolean needsSms;
    private boolean completed;
    private int remaining;
    private long generation;

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
        confirm.setId("confirm-qr");
        confirm.getStyleClass().add("primary");
        confirm.setOnAction(event -> confirm());
        send.setId("send-scan-code");
        send.setOnAction(event -> sendCode());
        code.setId("scan-code");
        code.setPromptText("6 位短信验证码");
        code.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().matches("[0-9]{0,6}") ? change : null));
        feedback.setId("scan-feedback");
        feedback.setWrapText(true);
        qr.textProperty().addListener((obs, old, value) -> reset());
        VBox body = new VBox(16, new Label("请确认这是你要登录的游戏设备。首次扫码可能需要短信验证。"),
                qr, new HBox(10, image, read), roles, sms, confirm, feedback);
        body.setPrefWidth(620);
        body.setPadding(new Insets(20));
        getDialogPane().setContent(body);
        setOnHiding(event -> { generation++; cooldown.stop(); captcha.cancel(); code.clear(); qr.clear(); });
        update();
    }

    private void reset() {
        generation++;
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
        execute(() -> service.roles(session, qr.getText().strip()), result -> {
            if (handle(result)) return;
            if (result.status() == ScanService.Status.SUCCESS) {
                roles.getItems().setAll(result.roles());
                if (!roles.getItems().isEmpty()) roles.getSelectionModel().selectFirst();
            }
        });
    }

    private void confirm() {
        if (busy || completed || roles.getValue() == null) return;
        if (needsSms && !code.getText().matches("[0-9]{6}")) { feedback.setText("请输入 6 位短信验证码。"); return; }
        String payload = qr.getText().strip();
        String id = roles.getValue().id();
        String verification = needsSms ? code.getText() : "";
        execute(() -> service.scan(session, payload, id, verification), result -> {
            if (handle(result)) return;
            if (result.status() == ScanService.Status.SMS_REQUIRED) {
                needsSms = true;
                code.clear();
            } else if (result.status() == ScanService.Status.SUCCESS) {
                code.clear();
                completed = true;
            }
        });
    }

    private boolean handle(ScanService.ScanResult result) {
        feedback.setText(result.message());
        if (result.status() == ScanService.Status.INVALID_TOKEN) { close(); invalidToken.run(); return true; }
        if (result.status() == ScanService.Status.EXPIRED) { reset(); return true; }
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
        busy = true;
        update();
        long request = generation;
        CompletableFuture.supplyAsync(() -> {
            try {
                if (file.length() > 20_000_000) throw new IllegalArgumentException();
                var picture = ImageIO.read(file);
                if (picture == null) throw new IllegalArgumentException();
                return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(picture)))).getText();
            } catch (Exception error) { throw new CompletionException(new IllegalArgumentException("无法读取二维码")); }
        }).whenComplete((value, error) -> Platform.runLater(() -> {
            if (request != generation || !isShowing()) return;
            busy = false;
            if (error != null) feedback.setText("未识别到二维码，请换一张清晰图片。");
            else { qr.setText(value); feedback.setText("已识别二维码，请读取角色。"); }
            update();
        }));
    }

    private void update() {
        qr.setDisable(busy);
        read.setDisable(busy);
        image.setDisable(busy);
        roles.setDisable(busy || needsSms || completed);
        confirm.setDisable(busy || completed || roles.getValue() == null);
        confirm.setText(needsSms ? "验证短信并登录游戏" : "确认登录游戏");
        sms.setVisible(needsSms);
        sms.setManaged(needsSms);
        code.setDisable(busy || completed);
        send.setDisable(busy || completed || remaining > 0);
        send.setText(remaining > 0 ? remaining + " 秒后重试" : "获取短信验证码");
    }
}
