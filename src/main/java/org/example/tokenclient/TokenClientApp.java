package org.example.tokenclient;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class TokenClientApp extends Application {
    @Override
    public void start(Stage stage) {
        var view = new AccountView(PlatformConfig.fromSystemProperties(),
                TokenAuthService.unconfigured(), uri -> getHostServices().showDocument(uri.toString()));
        stage.setTitle("平台客户端 · 账号");
        stage.setScene(new Scene(view, 1100, 800));
        stage.setMinWidth(920);
        stage.setMinHeight(680);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
