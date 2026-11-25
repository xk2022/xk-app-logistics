package com.xksystem.logistics.upms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {
    private final Set<String> blocked;
    public PasswordPolicy() throws IOException {
        var resource = new ClassPathResource("upms/passwords/common-passwords-sha256.txt");
        try (var lines = new java.io.BufferedReader(new java.io.InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            blocked = lines.lines().filter(line -> line.matches("[a-f0-9]{64}")).collect(Collectors.toUnmodifiableSet());
        }
        if (blocked.isEmpty()) throw new IllegalStateException("Password blocklist is empty");
    }
    public String normalize(String value) {
        if (value == null) return "";
        if (value.length() > 512) throw UpmsException.field("password", "密碼須為 15 至 128 個字元");
        for (int i=0; i<value.length(); i++) {
            char item=value.charAt(i);
            if (Character.isHighSurrogate(item)) {
                if (i+1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i)))
                    throw UpmsException.field("password", "密碼包含無效的 Unicode 字元");
            } else if (Character.isLowSurrogate(item)) throw UpmsException.field("password", "密碼包含無效的 Unicode 字元");
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }
    public String validate(String value, String field) {
        String normalized;
        try { normalized = normalize(value); }
        catch (UpmsException e) { throw UpmsException.field(field, "密碼須為 15 至 128 個字元"); }
        int count = normalized.codePointCount(0, normalized.length());
        if (count < 15 || count > 128) throw UpmsException.field(field, "密碼須為 15 至 128 個字元");
        if (blocked.contains(sha256(normalized))) throw UpmsException.field(field, "此密碼過於常見，請選擇其他密碼");
        return normalized;
    }
    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
