package org.example.tokenclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Base64;

/** Local rejection only. JWT decoding never establishes authenticity. */
final class TokenFormat {
    private static final ObjectMapper JSON = new ObjectMapper();

    static String userId(String token) {
        try {
            if (token == null || token.length() > 8192) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw new IllegalArgumentException();
            for (String part : parts) {
                if (!part.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            }
            JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode body = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (!"HS256".equals(header.path("alg").asText())
                    || Base64.getUrlDecoder().decode(parts[2]).length != 32) throw new IllegalArgumentException();
            String id = body.path("userId").asText();
            if (!id.matches("[1-9][0-9]{0,19}")) throw new IllegalArgumentException();
            if (body.has("exp") && (!body.get("exp").isIntegralNumber()
                    || body.get("exp").asLong() <= Instant.now().getEpochSecond())) throw new IllegalArgumentException();
            return id;
        } catch (Exception error) {
            throw new IllegalArgumentException("Token 格式不合法或已过期，请重新登录。");
        }
    }

    private TokenFormat() {}
}
