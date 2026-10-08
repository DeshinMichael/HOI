import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class SpiderTest {
    private enum Mode { SUCCESS, HTTP_ERROR, BAD_JSON }

    private static final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    private static final AtomicReference<Mode> mode = new AtomicReference<>(Mode.SUCCESS);
    private static CountDownLatch parallel;

    private static void reply(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.computeIfAbsent(path, unused -> new AtomicInteger()).incrementAndGet();
        int status = 200;
        String body;
        if (mode.get() == Mode.HTTP_ERROR) {
            if (path.equals("/")) body = "{\"message\":\"root\",\"successors\":[\"/bad\"]}";
            else { status = 500; body = "failure"; }
        } else if (mode.get() == Mode.BAD_JSON) {
            body = "{\"message\":\"missing successors\"}";
        } else {
            switch (path) {
                case "/" -> body = "{\"message\":\"zeta\",\"successors\":[\"/a\",\"/b\",\"/a\"],\"extra\":{\"x\":[true,2]}}";
                case "/a", "/b" -> {
                    parallel.countDown();
                    try {
                        if (!parallel.await(5, TimeUnit.SECONDS)) throw new IOException("Requests were sequential");
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IOException(ex);
                    }
                    body = path.equals("/a")
                            ? "{\"message\":\"alpha\",\"successors\":[\"/shared\",\"/\"]}"
                            : "{\"message\":\"beta\",\"successors\":[\"/shared\"]}";
                }
                case "/shared" -> body = "{\"message\":\"quote\\\"here\",\"successors\":[]}";
                default -> { status = 404; body = "missing"; }
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private record Result(int exitCode, String output) { }

    private static Result run(int port) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "Spider", "http://127.0.0.1:" + port + "/")
                .redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Spider did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.exitValue(), output.replace("\r\n", "\n"));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext("/", SpiderTest::reply);
        server.start();
        int port = server.getAddress().getPort();
        try {
            parallel = new CountDownLatch(2);
            Result success = run(port);
            check(success.exitCode() == 0, "Successful crawl: " + success.output());
            check(success.output().equals("alpha\nbeta\nquote\"here\nzeta\n"),
                    "Sorted messages and JSON escape: " + success.output());
            for (String path : List.of("/", "/a", "/b", "/shared")) {
                check(requests.get(path).get() == 1, "Repeated request: " + path);
            }
            System.out.println("PASS graph traversal, cycles, duplicate links, parallel requests, sorting");

            mode.set(Mode.HTTP_ERROR);
            Result error = run(port);
            check(error.exitCode() != 0 && error.output().contains("HTTP 500"),
                    "HTTP error was hidden: " + error.output());
            System.out.println("PASS HTTP failure reporting");

            mode.set(Mode.BAD_JSON);
            Result malformed = run(port);
            check(malformed.exitCode() != 0 && malformed.output().contains("Invalid page JSON"),
                    "Malformed JSON was accepted: " + malformed.output());
            System.out.println("PASS malformed JSON reporting");
        } finally {
            server.stop(0);
            handlers.shutdownNow();
        }
    }
}
