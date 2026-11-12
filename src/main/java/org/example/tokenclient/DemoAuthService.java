package org.example.tokenclient;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Local UI demonstration only: no network calls, real SMS, or account validation. */
public final class DemoAuthService implements AuthService {
    @Override public CompletionStage<CodeResult> sendCode(String phone) {
        return CompletableFuture.completedFuture(new CodeResult(true, "演示模式未发送短信，请输入 123456。"));
    }

    @Override public CompletionStage<Result> loginWithPhone(String phone, String code) {
        if (!"123456".equals(code)) {
            return CompletableFuture.completedFuture(new Result(false, "演示验证码为 123456，请重试。", null));
        }
        return CompletableFuture.completedFuture(new Result(true, "演示登录成功",
                new Session(phone, "demo-" + UUID.randomUUID())));
    }

    @Override public CompletionStage<Result> loginWithToken(String token) {
        return CompletableFuture.completedFuture(new Result(true, "演示登录成功",
                new Session("Token 登录账号", token)));
    }
}
