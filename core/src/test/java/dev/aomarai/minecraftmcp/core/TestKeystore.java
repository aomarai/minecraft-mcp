package dev.aomarai.minecraftmcp.core;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;

/** A throwaway PKCS12 keystore with a self-signed certificate for localhost, made with the JDK's own keytool. */
final class TestKeystore {

    static final String PASSWORD = "changeit";
    private static final String ALIAS = "mcp";

    private final Path file;

    private TestKeystore(Path file) {
        this.file = file;
    }

    static TestKeystore create(Path dir) throws Exception {
        Path file = dir.resolve("keystore.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD)
                .redirectErrorStream(true).start();
        try (InputStream out = process.getInputStream()) {
            out.readAllBytes();
        }
        if (process.waitFor() != 0 || !Files.isRegularFile(file)) {
            throw new IOException("keytool could not make a keystore");
        }
        return new TestKeystore(file);
    }

    Path file() {
        return file;
    }

    Tls tls() {
        return new Tls(file, PASSWORD);
    }

    /** A TLS context that trusts exactly this keystore's certificate, as a client that pinned it would. */
    SSLContext clientContext() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, PASSWORD.toCharArray());
        }
        Certificate certificate = store.getCertificate(ALIAS);
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("pinned", certificate);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }
}
