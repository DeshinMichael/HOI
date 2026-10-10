import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public final class KeyClient {
    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0) throw new IOException("Invalid response length: " + length);
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static String pem(String type, byte[] data) {
        String base64 = Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(data);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: java KeyClient <name> <host> <port> <output-prefix> [--delay-seconds N] [--abort]");
            System.exit(2);
        }
        String name = args[0];
        if (name.isEmpty() || !StandardCharsets.US_ASCII.newEncoder().canEncode(name)
                || name.chars().anyMatch(c -> c < 32 || c > 127)) {
            throw new IllegalArgumentException("Name must contain printable ASCII bytes");
        }
        int port = Integer.parseInt(args[2]);
        long delaySeconds = 0;
        boolean abort = false;
        for (int i = 4; i < args.length; i++) {
            if (args[i].equals("--abort")) abort = true;
            else if (args[i].equals("--delay-seconds") && i + 1 < args.length) {
                delaySeconds = Long.parseLong(args[++i]);
                if (delaySeconds < 0) throw new IllegalArgumentException("Negative delay");
            } else throw new IllegalArgumentException("Unknown option: " + args[i]);
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(args[1], port), 10_000);
            socket.setSoTimeout(180_000);
            OutputStream output = socket.getOutputStream();
            output.write(name.getBytes(StandardCharsets.US_ASCII));
            output.write(0);
            output.flush();
            Thread.sleep(Math.multiplyExact(delaySeconds, 1000));
            if (abort) {
                System.out.println("Disconnected before reading response");
                return;
            }
            DataInputStream input = new DataInputStream(socket.getInputStream());
            int status = input.readUnsignedByte();
            byte[] first = readBytes(input);
            if (status == 1) throw new IOException(new String(first, StandardCharsets.UTF_8));
            if (status != 0) throw new IOException("Unknown status: " + status);
            byte[] certificateBytes = readBytes(input);
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(first));
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(certificateBytes));
            if (!((RSAPrivateCrtKey) privateKey).getModulus()
                    .equals(((RSAPublicKey) certificate.getPublicKey()).getModulus())) {
                throw new IOException("Certificate and private key do not match");
            }
            Path keyFile = Path.of(args[3] + ".key");
            Path certFile = Path.of(args[3] + ".crt");
            if (Files.exists(keyFile) || Files.exists(certFile)) {
                throw new IOException("Output file already exists");
            }
            boolean keyCreated = false;
            boolean certCreated = false;
            try {
                try (OutputStream file = Files.newOutputStream(keyFile,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    keyCreated = true;
                    file.write(pem("PRIVATE KEY", first).getBytes(StandardCharsets.US_ASCII));
                }
                try (OutputStream file = Files.newOutputStream(certFile,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    certCreated = true;
                    file.write(pem("CERTIFICATE", certificateBytes).getBytes(StandardCharsets.US_ASCII));
                }
            } catch (IOException error) {
                if (certCreated) {
                    try { Files.deleteIfExists(certFile); }
                    catch (IOException cleanup) { error.addSuppressed(cleanup); }
                }
                if (keyCreated) {
                    try { Files.deleteIfExists(keyFile); }
                    catch (IOException cleanup) { error.addSuppressed(cleanup); }
                }
                throw error;
            }
            System.out.println("Saved " + keyFile + " and " + certFile);
        }
    }
}
