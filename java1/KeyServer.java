import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Nonblocking TCP server; one request per connection. */
public final class KeyServer {
    private record Material(byte[] privateKey, byte[] certificate) { }
    private record Completion(SelectionKey key, Material material, Throwable error) { }

    private static final class ClientState {
        final StringBuilder name = new StringBuilder();
        ByteBuffer output;
    }

    private final Selector selector;
    private final ExecutorService generators;
    private final PrivateKey issuerKey;
    private final X500Name issuerName;
    private final ConcurrentHashMap<String, CompletableFuture<Material>> cache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Completion> completed = new ConcurrentLinkedQueue<>();
    private final SecureRandom random = new SecureRandom();
    private volatile boolean running = true;

    private KeyServer(Selector selector, int workers, PrivateKey issuerKey, String issuerCn) {
        this.selector = selector;
        this.generators = Executors.newFixedThreadPool(workers);
        this.issuerKey = issuerKey;
        this.issuerName = cn(issuerCn);
    }

    private static X500Name cn(String value) {
        return new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, value).build();
    }

    private Material generate(String name) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(8192);
        KeyPair pair = generator.generateKeyPair();
        Instant now = Instant.now();
        BigInteger serial = new BigInteger(128, random).setBit(127);
        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                issuerName, serial,
                Date.from(now.minus(1, ChronoUnit.MINUTES)),
                Date.from(now.plus(365, ChronoUnit.DAYS)),
                cn(name), SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey);
        byte[] certificate = builder.build(signer).getEncoded();
        return new Material(pair.getPrivate().getEncoded(), certificate);
    }

    private CompletableFuture<Material> materialFor(String name) {
        CompletableFuture<Material> created = new CompletableFuture<>();
        CompletableFuture<Material> existing = cache.putIfAbsent(name, created);
        if (existing != null) return existing;
        generators.execute(() -> {
            try {
                created.complete(generate(name));
            } catch (Throwable error) {
                created.completeExceptionally(error);
                cache.remove(name, created); // A later request may retry a failed generation.
            }
        });
        return created;
    }

    private void request(SelectionKey key, String name) {
        materialFor(name).whenComplete((material, error) -> {
            completed.add(new Completion(key, material, error));
            selector.wakeup();
        });
    }

    private static ByteBuffer response(Material material, Throwable error) {
        if (error != null) {
            byte[] message = ("generation failed: " + error.getClass().getSimpleName())
                    .getBytes(StandardCharsets.UTF_8);
            return ByteBuffer.allocate(1 + 4 + message.length)
                    .put((byte) 1).putInt(message.length).put(message).flip();
        }
        return ByteBuffer.allocate(1 + 4 + material.privateKey.length + 4 + material.certificate.length)
                .put((byte) 0).putInt(material.privateKey.length).put(material.privateKey)
                .putInt(material.certificate.length).put(material.certificate).flip();
    }

    private void drainCompletions() {
        Completion item;
        while ((item = completed.poll()) != null) {
            if (!item.key.isValid()) continue;
            ClientState state = (ClientState) item.key.attachment();
            state.output = response(item.material, item.error);
            item.key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private void accept(ServerSocketChannel listener) throws IOException {
        SocketChannel channel;
        while ((channel = listener.accept()) != null) {
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_READ, new ClientState());
        }
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientState state = (ClientState) key.attachment();
        ByteBuffer input = ByteBuffer.allocate(512);
        int count;
        while ((count = channel.read(input)) > 0) {
            input.flip();
            while (input.hasRemaining()) {
                int value = input.get() & 0xff;
                if (value == 0) {
                    if (state.name.isEmpty()) throw new IOException("Empty name");
                    key.interestOps(0);
                    request(key, state.name.toString());
                    return;
                }
                if (value > 127 || value < 32) {
                    throw new IOException("Name must contain printable ASCII bytes");
                }
                state.name.append((char) value);
            }
            input.clear();
        }
        if (count < 0) close(key);
    }

    private static void write(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientState state = (ClientState) key.attachment();
        channel.write(state.output); // A partial write remains in the buffer for OP_WRITE.
        if (!state.output.hasRemaining()) close(key);
    }

    private static void close(SelectionKey key) {
        key.cancel();
        try { key.channel().close(); } catch (IOException ignored) { }
    }

    private void run(ServerSocketChannel listener) throws IOException {
        while (running) {
            drainCompletions();
            selector.select();
            drainCompletions();
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();
                if (!key.isValid()) continue;
                try {
                    if (key.isAcceptable()) accept(listener);
                    else if (key.isReadable()) read(key);
                    else if (key.isWritable()) write(key);
                } catch (IOException ex) {
                    System.err.println("Connection: " + ex.getMessage());
                    close(key);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: java KeyServer <port> <generator-workers> <issuer.pk8> <issuer-CN>");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        int workers = Integer.parseInt(args[1]);
        if (port < 1 || port > 65535 || workers < 1 || args[3].isBlank()) {
            throw new IllegalArgumentException("Invalid port, worker count, or issuer name");
        }
        byte[] encoded = Files.readAllBytes(Path.of(args[2]));
        PrivateKey signingKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(encoded));
        try (Selector selector = Selector.open(); ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress("0.0.0.0", port), 512);
            listener.register(selector, SelectionKey.OP_ACCEPT);
            KeyServer server = new KeyServer(selector, workers, signingKey, args[3]);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.running = false;
                selector.wakeup();
                server.generators.shutdownNow();
            }));
            System.out.println("Listening on 0.0.0.0:" + port + " with " + workers + " generators");
            try { server.run(listener); }
            finally {
                server.generators.shutdownNow();
                for (SelectionKey key : selector.keys()) close(key);
            }
        }
    }
}
