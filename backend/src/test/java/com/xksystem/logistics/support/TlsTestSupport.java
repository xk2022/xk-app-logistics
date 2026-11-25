package com.xksystem.logistics.support;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Real local TLS; the generated test key is never committed or included in the application jar. */
public final class TlsTestSupport {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final Path KEYSTORE = generateKeyStore();

    private TlsTestSupport() {
    }

    public static void register(DynamicPropertyRegistry properties) {
        properties.add("server.ssl.enabled", () -> true);
        properties.add("server.ssl.key-store", () -> KEYSTORE.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> PASSWORD);
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
    }

    public static SSLContext trustedContext() {
        try (InputStream stream = Files.newInputStream(KEYSTORE)) {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            keys.load(stream, PASSWORD.toCharArray());
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(keys);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), new SecureRandom());
            return context;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not trust the temporary test certificate", failure);
        }
    }

    private static Path generateKeyStore() {
        try {
            Path directory = Files.createTempDirectory("xk-upms-tls-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path file = directory.resolve("test.p12");
            ProcessBuilder builder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", "xk-test", "-keyalg", "RSA", "-keysize", "2048",
                    "-storetype", "PKCS12", "-keystore", file.toString(),
                    "-storepass:env", "XK_TEST_TLS_PASSWORD", "-keypass:env", "XK_TEST_TLS_PASSWORD",
                    "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                    "-validity", "1", "-noprompt");
            builder.environment().put("XK_TEST_TLS_PASSWORD", PASSWORD);
            Process process = builder.redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("Temporary TLS certificate generation failed: " + output);
            }
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            file.toFile().deleteOnExit();
            directory.toFile().deleteOnExit();
            return file;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not create a temporary test TLS certificate", failure);
        }
    }
}
