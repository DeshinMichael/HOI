import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Local integration check against a running KeyServer. */
public final class IntegrationCheck {
    private record Batch(byte[] first, byte[] second) { }

    private static byte[] readResponse(Socket socket) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        if (input.readUnsignedByte() != 0) throw new AssertionError("Server error");
        int keyLength = input.readInt();
        if (keyLength < 1000 || keyLength > 100_000) throw new AssertionError("Bad key length");
        byte[] key = new byte[keyLength];
        input.readFully(key);
        int certLength = input.readInt();
        if (certLength < 1000 || certLength > 100_000) throw new AssertionError("Bad certificate length");
        byte[] cert = new byte[certLength];
        input.readFully(cert);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(key);
        return digest.digest(cert);
    }

    private static byte[] request(String host, int port, String name, boolean abort, boolean slow) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 10_000);
            socket.setSoTimeout(180_000);
            socket.getOutputStream().write((name + "\0").getBytes(StandardCharsets.US_ASCII));
            if (abort) return null;
            if (slow) Thread.sleep(2_000);
            return readResponse(socket);
        }
    }

    private static void checkPartialRequests(String host, int port, byte[] expected) throws Exception {
        List<Socket> sockets = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++) {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 10_000);
                socket.setSoTimeout(180_000);
                sockets.add(socket);
                socket.getOutputStream().write("Concur".getBytes(StandardCharsets.US_ASCII));
            }
            Thread.sleep(200);
            for (Socket socket : sockets) {
                socket.getOutputStream().write("rentA\0".getBytes(StandardCharsets.US_ASCII));
            }
            for (Socket socket : sockets) {
                if (!Arrays.equals(expected, readResponse(socket))) {
                    throw new AssertionError("Partial request changed material");
                }
            }
        } finally {
            for (Socket socket : sockets) socket.close();
        }
    }

    private static Batch checkBatch(String host, int port, boolean slowReaders) throws Exception {
        int clients = 100;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<byte[]>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            for (int i = 0; i < clients; i++) {
                final int id = i;
                results.add(pool.submit(() -> {
                    start.await();
                    String name = id < 55 ? "ConcurrentA" : "ConcurrentB";
                    boolean slow = slowReaders && (id >= 10 && id < 20 || id >= 55 && id < 65);
                    return request(host, port, name, id < 10, slow);
                }));
            }
            start.countDown();
            byte[] first = null;
            byte[] second = null;
            for (int i = 0; i < results.size(); i++) {
                byte[] result = results.get(i).get();
                if (result == null) continue;
                if (i < 55) {
                    if (first == null) first = result;
                    else if (!Arrays.equals(first, result)) throw new AssertionError("ConcurrentA changed material");
                } else {
                    if (second == null) second = result;
                    else if (!Arrays.equals(second, result)) throw new AssertionError("ConcurrentB changed material");
                }
            }
            if (first == null || second == null || Arrays.equals(first, second)) {
                throw new AssertionError("Distinct names must have distinct material");
            }
            return new Batch(first, second);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: java IntegrationCheck host port");
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        Batch cold = checkBatch(host, port, false);
        checkPartialRequests(host, port, cold.first);
        Batch warm = checkBatch(host, port, true);
        if (!Arrays.equals(cold.first, warm.first) || !Arrays.equals(cold.second, warm.second)) {
            throw new AssertionError("Cached names changed material");
        }
        byte[] different = request(host, port, "L".repeat(256), false, false);
        if (Arrays.equals(cold.first, different) || Arrays.equals(cold.second, different)) {
            throw new AssertionError("Long name got existing material");
        }
        System.out.println("PASS: 100 cold, 100 partial-request, and 100 cached concurrent clients,");
        System.out.println("      two names, 20 slow readers, aborted connections, and a 256-byte ASCII name");
    }
}
