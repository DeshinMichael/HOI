import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Spider {
    private final URI origin;
    private final HttpClient client;
    private final ExecutorService workers;
    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger pending = new AtomicInteger();
    private final CountDownLatch finished = new CountDownLatch(1);

    private Spider(URI origin, ExecutorService workers) {
        this.origin = origin;
        this.workers = workers;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    private void visit(String path) {
        if (!visited.add(path)) {
            return;
        }
        pending.incrementAndGet();
        workers.execute(() -> {
            try {
                URI uri = origin.resolve(path);
                if (!uri.getScheme().equals(origin.getScheme())
                        || !uri.getAuthority().equals(origin.getAuthority())) {
                    throw new IOException("Successor leaves the server: " + path);
                }
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30)).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + path);
                }
                Page page = Json.readPage(response.body());
                messages.add(page.message());
                for (String successor : page.successors()) {
                    visit(successor);
                }
            } catch (Exception ex) {
                errors.add(path + ": " + ex.getMessage());
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                if (pending.decrementAndGet() == 0) {
                    finished.countDown();
                }
            }
        });
    }

    private void run() throws InterruptedException {
        visit("/");
        finished.await();
        if (!errors.isEmpty()) {
            for (String error : errors) {
                System.err.println(error);
            }
            throw new IllegalStateException("Could not collect every URL (" + errors.size() + " failures)");
        }
        Collections.sort(messages);
        messages.forEach(System.out::println);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java Spider http://host:port/");
            System.exit(2);
        }
        URI origin = URI.create(args[0]);
        if (!"http".equals(origin.getScheme()) && !"https".equals(origin.getScheme())) {
            throw new IllegalArgumentException("Expected an HTTP or HTTPS URL");
        }
        if (origin.getAuthority() == null) {
            throw new IllegalArgumentException("URL must include a host");
        }
        origin = URI.create(origin.getScheme() + "://" + origin.getAuthority() + "/");
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            new Spider(origin, workers).run();
        }
    }

    private record Page(String message, List<String> successors) { }

    private static class Json {
        private final String text;
        private int pos;

        private Json(String text) { this.text = text; }

        static Page readPage(String text) {
            Json json = new Json(text);
            json.expect('{');
            String message = null;
            List<String> successors = null;
            if (!json.take('}')) {
                do {
                    String key = json.string();
                    json.expect(':');
                    if (key.equals("message")) {
                        message = json.string();
                    } else if (key.equals("successors")) {
                        successors = json.stringArray();
                    } else {
                        json.skipValue();
                    }
                } while (json.take(','));
                json.expect('}');
            }
            json.space();
            if (json.pos != text.length() || message == null || successors == null) {
                throw new IllegalArgumentException("Invalid page JSON");
            }
            return new Page(message, successors);
        }

        private List<String> stringArray() {
            expect('[');
            List<String> values = new ArrayList<>();
            if (!take(']')) {
                do { values.add(string()); } while (take(','));
                expect(']');
            }
            return values;
        }

        private void skipValue() {
            space();
            if (take('{')) {
                if (!take('}')) {
                    do { string(); expect(':'); skipValue(); } while (take(','));
                    expect('}');
                }
            } else if (take('[')) {
                if (!take(']')) {
                    do { skipValue(); } while (take(','));
                    expect(']');
                }
            } else if (pos < text.length() && text.charAt(pos) == '"') {
                string();
            } else {
                int start = pos;
                while (pos < text.length() && ",]} \r\n\t".indexOf(text.charAt(pos)) < 0) pos++;
                if (start == pos) throw new IllegalArgumentException("Invalid JSON value");
            }
        }

        private String string() {
            space();
            expect('"');
            StringBuilder result = new StringBuilder();
            while (pos < text.length()) {
                char ch = text.charAt(pos++);
                if (ch == '"') return result.toString();
                if (ch == '\\') {
                    if (pos == text.length()) break;
                    ch = text.charAt(pos++);
                    switch (ch) {
                        case '"', '\\', '/' -> result.append(ch);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) throw new IllegalArgumentException("Short Unicode escape");
                            result.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Invalid JSON escape");
                    }
                } else {
                    if (ch < 0x20) throw new IllegalArgumentException("Control character in JSON string");
                    result.append(ch);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private void space() {
            while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) pos++;
        }

        private boolean take(char expected) {
            space();
            if (pos < text.length() && text.charAt(pos) == expected) { pos++; return true; }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) throw new IllegalArgumentException("Expected '" + expected + "' at " + pos);
        }
    }
}
