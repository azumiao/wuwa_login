package org.example.tokenclient;

import java.util.*;

/** Separate actionable UI errors from bounded, redacted console diagnostics. */
final class ScanFailure {
    enum Phase {
        CAPTURE("直播取流/解码"), QR_INIT("二维码识别初始化"), QR_DECODE("二维码识别");
        final String label;
        Phase(String label) { this.label = label; }
    }

    static String message(Phase phase, Throwable error) {
        List<Throwable> causes = causes(error);
        String types = String.join(" → ", causes.stream().map(e -> e.getClass().getSimpleName()).toList());
        String result = phase.label + "失败（" + phase.name() + "；" + types + "）";
        if (causes.stream().anyMatch(e -> e instanceof LinkageError))
            return result + "：原生库加载失败。请重新加载 Maven，确认 JDK 与原生依赖架构一致（"
                    + platform() + "）。";
        if (phase == Phase.QR_INIT && causes.stream().anyMatch(e -> e instanceof java.io.IOException))
            return result + "：模型或临时目录读取失败，请检查安装文件及目录权限。";
        return result + "，请查看控制台诊断后重试。";
    }

    static String diagnostic(Throwable error) {
        StringBuilder result = new StringBuilder("平台：").append(platform());
        for (Throwable cause : causes(error)) {
            result.append("\n").append(cause.getClass().getName());
            String raw = cause.getMessage();
            if (raw != null) {
                // Native errors can contain signed stream URLs; never log them verbatim.
                raw = raw.substring(0, Math.min(raw.length(), 2048))
                        .replaceAll("(?i)https?://[^\\s\"'<>]+", "[直播地址已隐藏]")
                        .replaceAll("(?i)(token|did|devCode|a_bogus|msToken|sign|signature|ticket|cookie)[=:]\\s*[^\\s,;]+", "$1=[已隐藏]");
                result.append(": ").append(ApiFeedback.message(raw, List.of()));
            }
            StackTraceElement[] stack = cause.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, 6); i++) result.append("\n  at ").append(stack[i]);
        }
        return result.toString();
    }

    private static List<Throwable> causes(Throwable error) {
        List<Throwable> result = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (error != null && result.size() < 6 && seen.add(error)) { result.add(error); error = error.getCause(); }
        return result;
    }
    private static String platform() {
        return System.getProperty("os.name", "unknown") + " / " + System.getProperty("os.arch", "unknown");
    }
    private ScanFailure() { }
}
