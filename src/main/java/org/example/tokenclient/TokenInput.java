package org.example.tokenclient;

/** JWT and its originating device ID are separate credentials, never a combined API token. */
record TokenInput(String token, String deviceId) {
    TokenInput {
        if (token == null || token.isBlank() || token.length() > 8192
                || token.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("Token 中不能包含空格或换行，请检查后重试。");
        if (deviceId == null || (!deviceId.isEmpty() && !validDeviceId(deviceId)))
            throw new IllegalArgumentException("did 格式不合法，请粘贴与 Token 配套的设备标识。");
    }

    static TokenInput parse(String input) {
        if (input == null || input.length() > 16384) throw new IllegalArgumentException("请输入有效的 Token 或 token, did。");
        String value = input.strip().replaceFirst("(?i)^添加token\\s+", "");
        String[] parts = value.split("[,，]", -1);
        if (parts.length > 2 || parts.length == 2 && parts[1].isBlank())
            throw new IllegalArgumentException("请使用 token, did 格式，并保留完整设备标识。");
        return new TokenInput(parts[0].strip(), parts.length == 2 ? parts[1].strip() : "");
    }

    static boolean validDeviceId(String value) {
        return value != null && (value.matches("(?i)[a-f0-9]{40}")
                || value.matches("(?i)[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"));
    }

    @Override public String toString() { return "TokenInput[credentials redacted]"; }
}
