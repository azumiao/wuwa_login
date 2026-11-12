package org.example.tokenclient;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;

final class ApiFeedback {
    static String message(String raw, Collection<String> credentials) {
        String value = raw;
        for (String credential : credentials) {
            if (credential != null && !credential.isEmpty()) {
                value = value.replace(credential, "[已隐藏]")
                        .replace(java.net.URLEncoder.encode(credential, java.nio.charset.StandardCharsets.UTF_8), "[已隐藏]");
            }
        }
        value = value.replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[Token 已隐藏]")
                .replaceAll("(?<![0-9])1[3-9][0-9]{9}(?![0-9])", "[手机号已隐藏]")
                .replaceAll("(?i)G[0-9]+#KURO[^\\s，,;；]*", "[二维码已隐藏]")
                .replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        return value.length() > 180 ? value.substring(0, 180) + "…" : value;
    }

    static String failure(String operation, JsonNode response) {
        String code = response.path("code").asText("未知").replaceAll("[^0-9-]", "");
        if (code.isEmpty()) code = "未知";
        if (code.length() > 12) code = "未知";
        String detail = response.path("msg").asText();
        return operation + "失败（错误码 " + code + "）" + (detail.isBlank() ? "，服务端未提供原因。" : "：" + detail);
    }

    static final class HttpFailure extends RuntimeException {
        private final int status;
        HttpFailure(int status) { super("HTTP " + status); this.status = status; }
    }

    static String unavailable(String operation, Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        if (error instanceof HttpFailure failure) return operation + "失败（HTTP " + failure.status + "），请检查服务访问状态后重试。";
        if (error instanceof java.net.http.HttpTimeoutException) return operation + "请求超时，请重试；本次操作结果尚未确认。";
        return operation + "网络或服务异常，请重试；本次操作结果尚未确认。";
    }

    private ApiFeedback() {}
}
