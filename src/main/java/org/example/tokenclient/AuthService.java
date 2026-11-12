package org.example.tokenclient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Asynchronous boundary for the real login API in the next implementation stage. */
public interface AuthService {
    record Session(String account, String token) {
        public Session {
            if (account == null || account.isBlank() || token == null || token.isBlank()
                    || token.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("登录结果缺少账号或有效 Token");
            }
        }

        @Override public String toString() { return "Session[credentials redacted]"; }
    }

    record Result(boolean success, String message, Session session) {
        public Result {
            if (success && session == null) throw new IllegalArgumentException("登录成功必须包含会话");
        }
        @Override public String toString() { return "Result[success=" + success + "]"; }
    }

    record CodeResult(boolean success, String message) {}

    default CompletionStage<CodeResult> sendCode(String phone) {
        return CompletableFuture.completedFuture(new CodeResult(false, "短信接口尚未接入。"));
    }

    default CompletionStage<Result> loginWithPhone(String phone, String code) {
        return CompletableFuture.completedFuture(new Result(false, "手机号登录接口尚未接入。", null));
    }

    default CompletionStage<Result> loginWithToken(String token) {
        return CompletableFuture.completedFuture(new Result(false, "Token 登录接口尚未接入。", null));
    }
}
