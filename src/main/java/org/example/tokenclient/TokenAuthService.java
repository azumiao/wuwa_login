package org.example.tokenclient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Implement this with the platform API when its authentication contract is available. */
@FunctionalInterface
public interface TokenAuthService {
    CompletionStage<LoginResult> login(String token);

    record LoginResult(boolean success, String message) {}

    static TokenAuthService unconfigured() {
        return token -> CompletableFuture.completedFuture(
                new LoginResult(false, "尚未接入平台登录接口，请在接入后重试。"));
    }
}
