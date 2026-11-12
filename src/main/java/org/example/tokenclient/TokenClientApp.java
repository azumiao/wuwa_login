package org.example.tokenclient;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class TokenClientApp extends Application {
    @Override
    public void start(Stage stage) {
        boolean demo = Boolean.parseBoolean(System.getProperty("app.demo", "true"));
        AuthService auth = demo ? new DemoAuthService() : new AuthService() {};
        var view = new AccountView(auth, demo);
        stage.setTitle("wuwa_login · 账号" + (demo ? " · 界面演示" : ""));
        stage.setScene(new Scene(view, 1100, 800));
        stage.setMinWidth(920);
        stage.setMinHeight(680);
        stage.setOnHidden(event -> view.dispose());
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
