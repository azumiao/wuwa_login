package org.example.tokenclient;

import java.util.Objects;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;

public final class AccountView extends BorderPane {
    private final AuthService auth;
    private final boolean demo;
    private final Consumer<String> copyToken;
    private CredentialStore credentials = CredentialStore.NONE;
    private CaptchaProvider captcha = new GeetestCaptcha();
    private final Button retrySaved = new Button("重试本地登录");
    private final Button clearSaved = new Button("清除本地登录");
    private ScanLoginDialog scanDialog;
    private final TextField phone = new TextField();
    private final PasswordField hiddenToken = new PasswordField();
    private final TextField visibleToken = new TextField();
    private final ToggleButton reveal = new ToggleButton("显示");
    private final Button phoneLogin = new Button("手机号登录");
    private final Button tokenLogin = new Button("Token 登录");
    private final TextField room = new TextField();
    private final Button monitor = new Button("开始监视");
    private final Label monitorStatus = new Label("等待中");
    private final Label feedback = new Label("请选择一种方式登录。");
    private final Label status = new Label("●  未登录");
    private final VBox card = new VBox(22);
    private final Label accountName = new Label();
    private final Label previewTitle = new Label("等待直播画面");
    private AuthService.Session session;
    private PhoneLoginDialog phoneDialog;
    private Dialog<Void> tokenDialog;
    private boolean busy;
    private boolean monitoring;
    private boolean credentialsSaved;
    private long generation;

    public AccountView(AuthService auth, boolean demo) {
        this(auth, demo, token -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(token);
            if (!Clipboard.getSystemClipboard().setContent(content)) {
                throw new IllegalStateException("Clipboard unavailable");
            }
        });
    }

    AccountView(AuthService auth, CredentialStore credentials) {
        this(auth, false);
        this.credentials = credentials;
    }

    AccountView(AuthService auth, CredentialStore credentials, CaptchaProvider captcha, Consumer<String> copyToken) {
        this(auth, false, copyToken);
        this.credentials = credentials;
        this.captcha = captcha;
    }

    AccountView(AuthService auth, boolean demo, Consumer<String> copyToken) {
        this.auth = Objects.requireNonNull(auth);
        this.demo = demo;
        this.copyToken = Objects.requireNonNull(copyToken);
        retrySaved.setId("retry-saved");
        retrySaved.setOnAction(event -> restoreSession());
        clearSaved.setId("clear-saved");
        clearSaved.setOnAction(event -> {
            generation++;
            setBusy(false);
            if (clearCredentials()) message("本地登录已清除。", false);
        });
        getStylesheets().add(Objects.requireNonNull(getClass().getResource("account.css")).toExternalForm());
        getStyleClass().add("app");
        setLeft(sidebar());
        HBox topbar = new HBox(label("工作空间  /  账号", "muted"), spacer(), label("PHONE / TOKEN", "eyebrow"));
        topbar.getStyleClass().add("topbar");
        topbar.setAlignment(Pos.CENTER_LEFT);
        HBox title = row(new VBox(8, label("wuwa_login", "page-title"),
                label("连接账号，监视你的直播间。", "muted")), spacer(), label("ACCOUNT / 01", "eyebrow"));
        card.getStyleClass().add("card");
        card.setMaxWidth(Double.MAX_VALUE);
        feedback.setId("feedback");
        feedback.setWrapText(true);
        feedback.getStyleClass().add("feedback");
        feedback.setMinHeight(28);
        status.setId("login-status");
        status.getStyleClass().add("status");
        phone.setId("phone-input");
        phone.setPromptText("输入手机号");
        phone.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().matches("[0-9]{0,11}") ? change : null));
        phoneLogin.setId("phone-login");
        phoneLogin.getStyleClass().add("primary");
        phoneLogin.setOnAction(event -> openPhoneLogin());
        phone.setOnAction(event -> phoneLogin.fire());
        hiddenToken.setId("token-input");
        visibleToken.setId("token-visible");
        hiddenToken.setPromptText("粘贴已有的 Access Token");
        visibleToken.setPromptText("粘贴已有的 Access Token");
        visibleToken.textProperty().bindBidirectional(hiddenToken.textProperty());
        showToken(false);
        reveal.setId("reveal-token");
        reveal.getStyleClass().add("reveal");
        reveal.setAccessibleText("显示或隐藏 Token");
        reveal.setOnAction(event -> showToken(reveal.isSelected()));
        tokenLogin.setId("login-button");
        tokenLogin.getStyleClass().add("secondary");
        tokenLogin.setOnAction(event -> loginWithToken());
        hiddenToken.setOnAction(event -> tokenLogin.fire());
        visibleToken.setOnAction(event -> tokenLogin.fire());
        room.setId("room-input");
        room.setPromptText("输入直播间账号 / 房间号");
        monitor.setId("monitor-button");
        monitor.getStyleClass().add("primary");
        monitor.setOnAction(event -> toggleMonitor());
        room.setOnAction(event -> monitor.fire());
        monitorStatus.setId("monitor-status");
        monitorStatus.getStyleClass().add("status");
        accountName.setId("current-account");
        accountName.getStyleClass().add("account-name");
        previewTitle.getStyleClass().add("preview-title");
        VBox body = new VBox(22, title);
        body.setPadding(new Insets(28));
        if (demo) {
            Label banner = label("界面演示模式 · 不发送真实短信，不验证真实账号或连接直播流。", "demo-banner");
            banner.setId("demo-banner");
            banner.setWrapText(true);
            banner.setMaxWidth(Double.MAX_VALUE);
            body.getChildren().add(banner);
        }
        body.getChildren().add(card);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        BorderPane content = new BorderPane(scroll);
        content.setTop(topbar);
        setCenter(content);
        showLogin();
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (oldScene != null && newScene == null) dispose();
        });
    }

    private void showLogin() {
        status.setText("●  未登录");
        status.getStyleClass().remove("connected");
        phoneLogin.setDefaultButton(true);
        HBox phoneRow = row(phone, phoneLogin);
        HBox.setHgrow(phone, Priority.ALWAYS);
        StackPane tokenInput = new StackPane(hiddenToken, visibleToken);
        HBox.setHgrow(tokenInput, Priority.ALWAYS);
        Label phoneLabel = label("手机号", "field-label");
        phoneLabel.setLabelFor(phone);
        Label tokenLabel = label("已有 Token", "field-label");
        tokenLabel.setLabelFor(hiddenToken);
        Separator beforeToken = new Separator();
        Separator afterToken = new Separator();
        HBox.setHgrow(beforeToken, Priority.ALWAYS);
        HBox.setHgrow(afterToken, Priority.ALWAYS);
        card.getChildren().setAll(
                row(badge("◎"), new VBox(5, label("手机号登录", "section-title"),
                        label("使用验证码登录，或直接使用已有 Token。", "muted")), spacer(), status),
                new Separator(), new VBox(10, phoneLabel, phoneRow),
                row(beforeToken, label("或使用 Token 登录", "muted"), afterToken),
                new VBox(10, tokenLabel, row(tokenInput, reveal, tokenLogin)), row(retrySaved, clearSaved), feedback);
        retrySaved.setVisible(false);
        retrySaved.setManaged(false);
        clearSaved.setVisible(false);
        clearSaved.setManaged(false);
    }

    private void openPhoneLogin() {
        if (busy || session != null || phoneDialog != null) return;
        String number = phone.getText();
        if (!number.matches("1[3-9][0-9]{9}")) {
            message("请输入正确的 11 位手机号。", true);
            phone.requestFocus();
            return;
        }
        PhoneLoginDialog dialog = new PhoneLoginDialog(getScene() == null ? null : getScene().getWindow(), number, auth, demo, captcha);
        phoneDialog = dialog;
        dialog.setOnHidden(event -> {
            phoneDialog = null;
            if (dialog.getResult() != null) enterSession(dialog.getResult());
        });
        dialog.show();
    }

    private void loginWithToken() {
        if (busy || session != null || phoneDialog != null) return;
        String token = hiddenToken.getText().strip();
        if (token.isEmpty()) {
            message("请先输入 Token。", true);
            return;
        }
        if (token.chars().anyMatch(Character::isWhitespace)) {
            message("Token 中不能包含空格或换行，请检查后重试。", true);
            return;
        }
        validateToken(token, false);
    }

    /** Called once after opening the window; cached data is never an authenticated session. */
    public void restoreSession() {
        if (demo || busy || session != null || phoneDialog != null) return;
        try {
            var saved = credentials.load();
            if (saved.isPresent()) {
                showSavedActions(true);
                validateToken(saved.get().token(), true);
            }
        } catch (java.io.IOException error) {
            showSavedActions(true);
            message("本地登录信息无法读取，请清除后重新登录。", true);
        }
    }

    private void validateToken(String token, boolean restoring) {
        setBusy(true);
        long request = ++generation;
        message("正在验证 Token…", false);
        try {
            auth.loginWithToken(token).whenComplete((result, error) -> Platform.runLater(() -> {
                if (request != generation) return;
                setBusy(false);
                if (error != null || result == null) {
                    message("登录失败，请检查网络或稍后重试。", true);
                } else if (result.success()) {
                    enterSession(result.session());
                } else {
                    if (restoring && result.invalidToken()) {
                        boolean cleared = clearCredentials();
                        if (cleared) message(result.message(), true);
                    } else message(result.message(), true);
                }
            }));
        } catch (RuntimeException error) {
            setBusy(false);
            message("登录服务暂不可用，请稍后重试。", true);
        }
    }

    private void enterSession(AuthService.Session value) {
        generation++;
        session = value;
        hiddenToken.clear();
        phone.clear();
        showToken(false);
        phoneLogin.setDefaultButton(false);
        status.setText(demo ? "●  演示登录" : "●  已登录");
        status.getStyleClass().add("connected");
        accountName.setText(maskPhone(value.account()));
        Button logout = new Button("退出登录");
        logout.setId("logout-button");
        logout.getStyleClass().add("secondary");
        logout.setOnAction(event -> logout());
        Button getToken = new Button("获取 Token");
        getToken.setId("get-token");
        getToken.getStyleClass().add("secondary");
        getToken.setOnAction(event -> openToken());
        HBox roomRow = row(room, monitor);
        HBox.setHgrow(room, Priority.ALWAYS);
        Label roomLabel = label("直播间账号", "field-label");
        roomLabel.setLabelFor(room);
        VBox previewCopy = new VBox(14, label("▣", "preview-icon"), previewTitle,
                label(demo ? "演示预览 · 暂无直播画面" : "开始监视后在这里显示直播画面", "muted"));
        previewCopy.setAlignment(Pos.CENTER);
        StackPane preview = new StackPane(previewCopy);
        preview.setId("stream-preview");
        preview.getStyleClass().add("preview");
        preview.setMinHeight(220);
        preview.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(preview, Priority.ALWAYS);
        VBox details = new VBox(16, label("运行状态", "field-label"), monitorStatus,
                new Separator(), label("识别耗时", "muted"), label("— ms", "metric"));
        details.setMinWidth(140);
        Button scan = new Button("扫码登录游戏");
        scan.setId("scan-login");
        scan.getStyleClass().add("secondary");
        scan.setDisable(!(auth instanceof ScanService));
        scan.setOnAction(event -> openScan());
        card.getChildren().setAll(
                row(badge("◎"), new VBox(5, label("当前账号", "muted"), accountName), spacer(), status, logout, getToken),
                new Separator(), scan, new VBox(10, roomLabel, roomRow), row(preview, details), feedback);
        message(demo ? "已进入演示账号，可获取 Token 或预览监视交互。" : "登录成功，请输入直播间账号。", false);
        if (!demo) {
            credentialsSaved = false;
            try { credentials.save(value); credentialsSaved = true; }
            catch (java.io.IOException error) { message("登录成功，但本地保存失败；下次打开需重新登录。", true); }
        }
    }

    private void openScan() {
        if (session == null || scanDialog != null || !(auth instanceof ScanService scanner)) return;
        ScanLoginDialog dialog = new ScanLoginDialog(getScene().getWindow(), session, scanner, captcha, () -> {
            logout();
            message("Token 已失效，请重新登录。", true);
        });
        scanDialog = dialog;
        dialog.setOnHidden(event -> scanDialog = null);
        dialog.show();
    }

    private void showSavedActions(boolean show) {
        for (Button button : new Button[]{retrySaved, clearSaved}) { button.setVisible(show); button.setManaged(show); }
    }

    private boolean clearCredentials() {
        try { credentials.clear(); showSavedActions(false); return true; }
        catch (java.io.IOException error) { showSavedActions(true); message("无法清除本地登录文件，请检查文件权限。", true); return false; }
    }

    private void openToken() {
        if (session == null || tokenDialog != null) return;
        Dialog<Void> dialog = new Dialog<>();
        tokenDialog = dialog;
        if (getScene() != null && getScene().getWindow() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle("当前账号的 Token");
        dialog.setResultConverter(button -> null);
        dialog.getDialogPane().setId("token-dialog");
        dialog.getDialogPane().getStylesheets().add(getStylesheets().getFirst());
        dialog.getDialogPane().getStyleClass().add("app");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CLOSE)).setText("关闭");
        PasswordField secret = new PasswordField();
        secret.setText(session.token());
        secret.setId("export-token");
        secret.setEditable(false);
        TextField plain = new TextField();
        plain.setText(session.token());
        plain.setId("export-token-visible");
        plain.setEditable(false);
        plain.setVisible(false);
        plain.setManaged(false);
        StackPane input = new StackPane(secret, plain);
        HBox.setHgrow(input, Priority.ALWAYS);
        ToggleButton toggle = new ToggleButton("显示");
        toggle.setId("export-reveal");
        toggle.getStyleClass().add("reveal");
        toggle.setOnAction(event -> {
            boolean show = toggle.isSelected();
            secret.setVisible(!show);
            secret.setManaged(!show);
            plain.setVisible(show);
            plain.setManaged(show);
            toggle.setText(show ? "隐藏" : "显示");
        });
        Button copy = new Button("复制 Token");
        copy.setId("copy-token");
        copy.getStyleClass().add("primary");
        Label tip = label(demo ? "演示 Token 仅保留在内存中。" : credentialsSaved
                ? "Token 已保存在本地，退出登录后清除。请勿分享给他人。"
                : "Token 未能保存到本地，请勿分享给他人。", "muted");
        tip.setId("copy-feedback");
        tip.setWrapText(true);
        copy.setOnAction(event -> {
            try {
                if (session == null) return;
                copyToken.accept(session.token());
                tip.setText("已复制到剪贴板。");
            } catch (RuntimeException error) {
                tip.setText("复制失败，请显示 Token 后手动复制。");
            }
        });
        VBox body = new VBox(18, label(demo ? "演示账号 Token" : "当前账号 Token", "section-title"), row(input, toggle), copy, tip);
        body.setPadding(new Insets(20));
        body.setPrefWidth(520);
        dialog.getDialogPane().setContent(body);
        dialog.setOnHidden(event -> { secret.clear(); plain.clear(); tokenDialog = null; });
        dialog.show();
    }

    private void toggleMonitor() {
        if (session == null) return;
        if (!monitoring && room.getText().isBlank()) {
            message("请先输入直播间账号或房间号。", true);
            return;
        }
        if (!demo) {
            message("直播流获取将在后续阶段接入，可使用上方按钮手动扫码登录游戏。", true);
            return;
        }
        monitoring = !monitoring;
        room.setDisable(monitoring);
        monitor.setText(monitoring ? "停止监视" : "开始监视");
        monitorStatus.setText(monitoring ? "演示监视中" : "等待中");
        monitorStatus.getStyleClass().remove("connected");
        if (monitoring) monitorStatus.getStyleClass().add("connected");
        previewTitle.setText(monitoring ? "正在演示监视交互" : "等待直播画面");
        message(monitoring ? "当前为界面演示，尚未连接直播流。" : "已停止演示监视。", false);
    }

    private void logout() {
        dispose();
        room.clear();
        room.setDisable(false);
        monitoring = false;
        monitor.setText("开始监视");
        monitorStatus.setText("等待中");
        monitorStatus.getStyleClass().remove("connected");
        previewTitle.setText("等待直播画面");
        accountName.setText("");
        showLogin();
        message("已退出登录，当前会话已清除。", false);
        if (!demo) clearCredentials();
    }

    public void dispose() {
        generation++;
        if (phoneDialog != null) phoneDialog.close();
        if (tokenDialog != null) tokenDialog.close();
        if (scanDialog != null) scanDialog.close();
        captcha.cancel();
        session = null;
        credentialsSaved = false;
        hiddenToken.clear();
        phone.clear();
        setBusy(false);
        showToken(false);
    }

    private void setBusy(boolean value) {
        busy = value;
        for (Control control : new Control[]{phone, phoneLogin, hiddenToken, visibleToken, reveal, tokenLogin, retrySaved, clearSaved}) {
            control.setDisable(value);
        }
        tokenLogin.setText(value ? "验证中…" : "Token 登录");
    }

    private void showToken(boolean show) {
        reveal.setSelected(show);
        reveal.setText(show ? "隐藏" : "显示");
        hiddenToken.setVisible(!show);
        hiddenToken.setManaged(!show);
        visibleToken.setVisible(show);
        visibleToken.setManaged(show);
    }

    private void message(String text, boolean error) {
        feedback.setText(text);
        feedback.getStyleClass().remove("error");
        if (error) feedback.getStyleClass().add("error");
    }

    static String maskPhone(String account) {
        return account.matches("1[3-9][0-9]{9}") ? account.substring(0, 3) + "****" + account.substring(7) : account;
    }

    private static Node sidebar() {
        HBox logo = row(badge("W"), label("wuwa_login", "brand-name"));
        VBox side = new VBox(28, logo, label("工作空间", "nav-caption"), label("◎    账号", "nav-active"));
        Region space = new Region();
        VBox.setVgrow(space, Priority.ALWAYS);
        side.getChildren().addAll(space, label("桌面客户端", "muted"), label("JavaFX  /  v1.0", "sidebar-version"));
        side.getStyleClass().add("sidebar");
        side.setPrefWidth(196);
        return side;
    }

    private static HBox row(Node... nodes) {
        HBox row = new HBox(12, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label badge(String text) {
        Label badge = label(text, "account-icon");
        badge.setAlignment(Pos.CENTER);
        return badge;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
