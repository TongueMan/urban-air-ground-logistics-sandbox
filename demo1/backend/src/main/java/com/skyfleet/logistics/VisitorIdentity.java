package com.skyfleet.logistics;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class VisitorIdentity {
    private static final String COOKIE = "skyfleet_visitor";
    private final byte[] secret;

    public VisitorIdentity(@Value("${demo.cookie-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public Identity resolve(HttpServletRequest request, HttpServletResponse response) {
        String raw = null;
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) if (COOKIE.equals(cookie.getName())) raw = cookie.getValue();
        String visitorId = verify(raw);
        if (visitorId == null) {
            visitorId = UUID.randomUUID().toString();
            String value = visitorId + "." + sign(visitorId);
            ResponseCookie cookie = ResponseCookie.from(COOKIE, value).httpOnly(true).secure(request.isSecure()).sameSite("Lax")
                    .path("/").maxAge(Duration.ofDays(7)).build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return new Identity(visitorId, sha256(visitorId));
    }

    private String verify(String raw) {
        if (raw == null || !raw.contains(".")) return null;
        int split = raw.lastIndexOf('.');
        String id = raw.substring(0, split);
        String signature = raw.substring(split + 1);
        return MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8), sign(id).getBytes(StandardCharsets.UTF_8)) ? id : null;
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    public record Identity(String visitorId, String visitorHash) {}
}

