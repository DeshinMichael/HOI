import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPairGenerator;

/** Creates the signing key once, before starting the server. */
public final class IssuerKeyTool {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java IssuerKeyTool issuer.pk8");
            System.exit(2);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(4096);
        Path file = Path.of(args[0]);
        Files.write(file, generator.generateKeyPair().getPrivate().getEncoded(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("Created signing key: " + file);
    }
}
