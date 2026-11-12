package org.example.tokenclient;

import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public final class AccountView extends BorderPane {
    private final PasswordField hiddenToken = new PasswordField();
    private final TextField visibleToken = new TextField();
    private final ToggleButton reveal = new ToggleButton("显示");
    private final Button login = new Button("Token 登录");
    private final Label feedback = new Label("Token 仅用于本次登录，不会保存到本地。");
    private final Label status = new Label("●  未登录");
    private boolean busy;

    public AccountView(PlatformConfig config, TokenAuthService auth, Consumer<URI> openBrowser) {
        getStylesheets().add(Objects.requireNonNull(getClass().getResource("account.css")).toExternalForm());
        getStyleClass().add("app");
        setLeft(sidebar());
        BorderPane content = new BorderPane();
        content.setTop(topbar());
        setCenter(content);

        Label heading = label("账号", "page-title");
        Label subtitle = label("连接你的平台账号，开始使用客户端。", "muted");
        HBox title = new HBox(16, new VBox(8, heading, subtitle), spacer(), label("ACCOUNT / 01", "eyebrow"));
        title.setAlignment(Pos.CENTER_LEFT);

        VBox account = new VBox(22);
        account.getStyleClass().add("card");
        HBox accountHeader = new HBox(12, badge("◎", "account-icon"), new VBox(5,
                label("平台账号", "section-title"), label("通过 Token 验证身份", "muted")), spacer(), status);
        accountHeader.setAlignment(Pos.CENTER_LEFT);
        status.getStyleClass().add("status");
        status.setId("login-status");

        HBox platform = new HBox(12, badge("P", "platform-icon"), new VBox(5,
                label(config.name(), "platform-name"), label("当前平台", "muted")), spacer(), label("固定平台", "platform-tag"));
        platform.setAlignment(Pos.CENTER_LEFT);
        platform.getStyleClass().add("platform-row");

        hiddenToken.setId("token-input");
        visibleToken.setId("token-visible");
        hiddenToken.setPromptText("粘贴你的 Access Token");
        visibleToken.setPromptText("粘贴你的 Access Token");
        visibleToken.textProperty().bindBidirectional(hiddenToken.textProperty());
        visibleToken.setVisible(false);
        visibleToken.setManaged(false);
        hiddenToken.setMaxWidth(Double.MAX_VALUE);
        visibleToken.setMaxWidth(Double.MAX_VALUE);
        StackPane input = new StackPane(hiddenToken, visibleToken);
        HBox.setHgrow(input, Priority.ALWAYS);
        reveal.setId("reveal-token");
        reveal.getStyleClass().add("reveal");
        reveal.setAccessibleText("显示或隐藏 Token");
        reveal.setOnAction(event -> {
            boolean show = reveal.isSelected();
            hiddenToken.setVisible(!show);
            hiddenToken.setManaged(!show);
            visibleToken.setVisible(show);
            visibleToken.setManaged(show);
            reveal.setText(show ? "隐藏" : "显示");
            TextField active = show ? visibleToken : hiddenToken;
            active.requestFocus();
            active.positionCaret(active.getLength());
        });
        HBox tokenRow = new HBox(8, input, reveal);
        tokenRow.setAlignment(Pos.CENTER_LEFT);
        Label tokenLabel = label("Access Token", "field-label");
        tokenLabel.setLabelFor(hiddenToken);
        VBox field = new VBox(10, tokenLabel, tokenRow, label("在平台获取 Token 后，粘贴到这里完成登录。", "muted"));

        login.setId("login-button");
        login.getStyleClass().add("primary");
        login.setDefaultButton(true);
        login.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(login, Priority.ALWAYS);
        Button getToken = new Button("获取 Token  ↗");
        getToken.setId("get-token");
        getToken.getStyleClass().add("secondary");
        getToken.setOnAction(event -> {
            if (config.tokenUrl().isEmpty()) {
                message("尚未配置获取 Token 的网址，请先补充平台地址。", true);
                return;
            }
            try {
                openBrowser.accept(config.tokenUrl().get());
                message("已打开平台页面，获取 Token 后请返回此处登录。", false);
            } catch (RuntimeException error) {
                message("无法打开浏览器，请检查系统默认浏览器设置。", true);
            }
        });
        login.setOnAction(event -> submit(auth));
        hiddenToken.setOnAction(event -> login.fire());
        visibleToken.setOnAction(event -> login.fire());
        feedback.setId("feedback");
        feedback.getStyleClass().add("feedback");
        feedback.setWrapText(true);
        feedback.setMinHeight(42);
        account.getChildren().addAll(accountHeader, new Separator(), platform, field,
                new HBox(12, login, getToken), feedback);
        account.setMaxWidth(Double.MAX_VALUE);

        VBox guide = new VBox(23, label("登录指南", "section-title"),
                label("只需三步，连接你的账号。", "muted"),
                step("01", "获取 Token", "点击「获取 Token」，前往平台完成授权。"),
                step("02", "粘贴 Token", "复制平台提供的 Token，填入左侧输入框。"),
                step("03", "登录账号", "点击「Token 登录」，验证后即可使用。"),
                new Separator(), label("妥善保管你的 Token", "field-label"),
                wrapped("Token 是账号访问凭证，请勿分享给他人。", "muted"));
        guide.getStyleClass().add("guide-card");
        guide.setPrefWidth(260);
        guide.setMinWidth(220);
        HBox columns = new HBox(22, account, guide);
        HBox.setHgrow(account, Priority.ALWAYS);
        columns.setAlignment(Pos.TOP_LEFT);

        VBox body = new VBox(28, title, columns,
                label("单一平台 · Token 授权登录", "footer"));
        body.setPadding(new Insets(34));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        content.setCenter(scroll);
    }

    private void submit(TokenAuthService auth) {
        if (busy) return;
        String token = hiddenToken.getText().strip();
        if (token.isEmpty()) {
            message("请先输入 Token。", true);
            hiddenToken.requestFocus();
            return;
        }
        if (token.chars().anyMatch(Character::isWhitespace)) {
            message("Token 中不能包含空格或换行，请检查后重试。", true);
            return;
        }
        setBusy(true);
        message("正在验证 Token…", false);
        try {
            auth.login(token).whenComplete((result, error) -> Platform.runLater(() -> {
                setBusy(false);
                if (error != null || result == null) {
                    // Never display exception messages that may contain credentials.
                    message("登录失败，请检查网络或稍后重试。", true);
                } else {
                    status.setText(result.success() ? "●  已登录" : "●  未登录");
                    status.getStyleClass().remove("connected");
                    if (result.success()) {
                        status.getStyleClass().add("connected");
                        hiddenToken.clear();
                        reveal.setSelected(false);
                        hiddenToken.setVisible(true);
                        hiddenToken.setManaged(true);
                        visibleToken.setVisible(false);
                        visibleToken.setManaged(false);
                        reveal.setText("显示");
                    }
                    message(result.message(), !result.success());
                }
            }));
        } catch (RuntimeException error) {
            setBusy(false);
            message("登录服务暂不可用，请稍后重试。", true);
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        login.setDisable(value);
        hiddenToken.setDisable(value);
        visibleToken.setDisable(value);
        reveal.setDisable(value);
        login.setText(value ? "验证中…" : "Token 登录");
    }

    private void message(String text, boolean error) {
        feedback.setText(text);
        feedback.getStyleClass().remove("error");
        if (error) feedback.getStyleClass().add("error");
    }

    private static Node sidebar() {
        Label brand = label("P", "brand");
        HBox logo = new HBox(10, brand, label("平台客户端", "brand-name"));
        logo.setAlignment(Pos.CENTER_LEFT);
        VBox side = new VBox(34, logo, label("工作空间", "nav-caption"), label("◎    账号", "nav-active"));
        Region space = new Region();
        VBox.setVgrow(space, Priority.ALWAYS);
        side.getChildren().addAll(space, label("桌面客户端", "muted"), label("JavaFX  /  v1.0", "sidebar-version"));
        side.getStyleClass().add("sidebar");
        side.setPrefWidth(196);
        return side;
    }

    private static Node topbar() {
        HBox bar = new HBox(label("工作空间  /  账号", "muted"), spacer(), label("TOKEN ACCESS", "eyebrow"));
        bar.getStyleClass().add("topbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private static Node step(String number, String title, String text) {
        VBox copy = new VBox(7, label(title, "field-label"), wrapped(text, "muted"));
        HBox.setHgrow(copy, Priority.ALWAYS);
        return new HBox(12, badge(number, "step-number"), copy);
    }

    private static Label wrapped(String text, String style) {
        Label label = label(text, style);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinWidth(0);
        return label;
    }

    private static Label badge(String text, String style) {
        Label label = label(text, style);
        label.setAlignment(Pos.CENTER);
        return label;
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
