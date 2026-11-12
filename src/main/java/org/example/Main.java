package org.example;

import javafx.application.Application;
import org.example.tokenclient.TokenClientApp;

/** Application entry point for IntelliJ IDEA and Maven. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Application.launch(TokenClientApp.class, args);
    }
}
