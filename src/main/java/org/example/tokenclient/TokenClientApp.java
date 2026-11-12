package org.example.tokenclient;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class TokenClientApp extends Application {
    @Override
    public void start(Stage stage) {
        boolean demo = Boolean.parseBoolean(System.getProperty("app.demo", "false"));
        AccountView view;
        if (demo) {
            view = new AccountView(new DemoAuthService(), true);
        } else {
            var credentials = new FileCredentialStore(java.nio.file.Path.of(System.getProperty("user.home"), ".wuwa_login"));
            try {
                view = new AccountView(new KuroAuthService(credentials.deviceId()), credentials);
            } catch (java.io.IOException error) {
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR,
                        "无法初始化本地登录存储，请检查用户目录权限后重试。").showAndWait();
                javafx.application.Platform.exit();
                return;
            }
        }
        stage.setTitle("wuwa_login · 账号" + (demo ? " · 界面演示" : ""));
        stage.setScene(new Scene(view, 1100, 800));
        stage.setMinWidth(920);
        stage.setMinHeight(680);
        stage.setOnHidden(event -> view.dispose());
        stage.show();
        view.restoreSession();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
