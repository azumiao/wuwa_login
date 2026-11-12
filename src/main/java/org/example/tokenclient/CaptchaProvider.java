package org.example.tokenclient;

import java.util.concurrent.CompletionStage;
import javafx.stage.Window;

@FunctionalInterface
interface CaptchaProvider {
    CompletionStage<String> verify(Window owner);
    default void cancel() {}
}
