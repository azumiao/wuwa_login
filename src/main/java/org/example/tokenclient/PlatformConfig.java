package org.example.tokenclient;

import java.net.URI;
import java.util.Optional;

/** One platform for the whole application; no platform selector. */
public record PlatformConfig(String name, Optional<URI> tokenUrl) {
    public PlatformConfig {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("平台名称不能为空");
        tokenUrl = tokenUrl == null ? Optional.empty() : tokenUrl;
        tokenUrl.ifPresent(uri -> {
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("获取 Token 的地址必须是有效的 HTTPS 网址");
            }
        });
    }

    public static PlatformConfig fromSystemProperties() {
        String url = System.getProperty("platform.tokenUrl", "").strip();
        return new PlatformConfig(System.getProperty("platform.name", "默认平台"),
                url.isEmpty() ? Optional.empty() : Optional.of(URI.create(url)));
    }
}
