import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class SortList {
    private interface StringList extends Iterable<String> {
        void addFirst(String value);
        int size();
        boolean step(int index, long insideDelayMs) throws InterruptedException;
    }

    private static class LinkedStringList implements StringList {
        private static class Node {
            final String value;
            final ReentrantLock lock = new ReentrantLock();
            Node next;
            long linkVersion;
            Node(String value) { this.value = value; }
        }

        private static class Cursor {
            int index = -1;
            int size;
            Node anchor;
            Node previous;
            long anchorVersion;
            long previousVersion;
        }

        private final Node head = new Node(null);
        private final AtomicInteger size = new AtomicInteger();
        private final AtomicLong version = new AtomicLong();
        private final ThreadLocal<Cursor> cursors = ThreadLocal.withInitial(Cursor::new);

        public void addFirst(String value) {
            head.lock.lock();
            try {
                Node node = new Node(value);
                node.next = head.next;
                head.next = node;
                head.linkVersion++;
                size.incrementAndGet();
                version.incrementAndGet();
            } finally {
                head.lock.unlock();
            }
        }

        public int size() { return size.get(); }

        private Node[] pairAt(int index) {
            Node previous = head;
            previous.lock.lock();
            Node current = previous.next;
            if (current == null) {
                previous.lock.unlock();
                return null;
            }
            current.lock.lock();
            for (int i = 0; i < index; i++) {
                Node following = current.next;
                if (following == null) {
                    current.lock.unlock();
                    previous.lock.unlock();
                    return null;
                }
                following.lock.lock();
                previous.lock.unlock();
                previous = current;
                current = following;
            }
            Node following = current.next;
            if (following == null) {
                current.lock.unlock();
                previous.lock.unlock();
                return null;
            }
            following.lock.lock();
            return new Node[] { previous, current, following };
        }

        private static void unlock(Node[] nodes) {
            nodes[2].lock.unlock();
            nodes[1].lock.unlock();
            nodes[0].lock.unlock();
        }

        private Node[] pairAt(int index, Cursor cursor) {
            if (index > 0 && cursor.index == index && cursor.size == size.get()) {
                Node anchor = cursor.anchor;
                Node previous = cursor.previous;
                anchor.lock.lock();
                if (anchor.linkVersion == cursor.anchorVersion && anchor.next == previous) {
                    previous.lock.lock();
                    if (previous.linkVersion == cursor.previousVersion && cursor.size == size.get()) {
                        anchor.lock.unlock();
                        Node current = previous.next;
                        if (current != null) {
                            current.lock.lock();
                            Node following = current.next;
                            if (following != null) {
                                following.lock.lock();
                                return new Node[] { previous, current, following };
                            }
                            current.lock.unlock();
                        }
                        previous.lock.unlock();
                        return pairAt(index);
                    }
                    previous.lock.unlock();
                }
                anchor.lock.unlock();
            }
            return pairAt(index);
        }

        public boolean step(int index, long insideDelayMs) throws InterruptedException {
            if (index < 0 || index + 1 >= size.get()) return false;
            Thread.sleep(insideDelayMs);
            int sizeAtLookup = size.get();
            Cursor cursor = cursors.get();
            Node[] nodes = pairAt(index, cursor);
            if (nodes == null) return false;
            try {
                Node previous = nodes[0], current = nodes[1], following = nodes[2];
                boolean swapped = current.value.compareTo(following.value) > 0;
                if (swapped) {
                    current.next = following.next;
                    current.linkVersion++;
                    following.next = current;
                    following.linkVersion++;
                    previous.next = following;
                    previous.linkVersion++;
                    version.incrementAndGet();
                }
                Node nextPrevious = swapped ? following : current;
                cursor.index = index + 1;
                cursor.size = sizeAtLookup;
                cursor.anchor = previous;
                cursor.previous = nextPrevious;
                cursor.anchorVersion = previous.linkVersion;
                cursor.previousVersion = nextPrevious.linkVersion;
                return true;
            } finally {
                unlock(nodes);
            }
        }

        public Iterator<String> iterator() {
            for (;;) {
                long start = version.get();
                List<String> snapshot = new ArrayList<>();
                Node current = head;
                current.lock.lock();
                try {
                    while (current.next != null) {
                        Node next = current.next;
                        next.lock.lock();
                        if (current != head) snapshot.add(current.value);
                        current.lock.unlock();
                        current = next;
                    }
                    if (current != head) snapshot.add(current.value);
                } finally {
                    current.lock.unlock();
                }
                if (start == version.get() && snapshot.size() == size.get()) {
                    return snapshot.iterator();
                }
                Thread.yield();
            }
        }
    }

    private static class StandardStringList implements StringList {
        private final List<String> list = Collections.synchronizedList(new ArrayList<>());

        public void addFirst(String value) { list.add(0, value); }
        public int size() { return list.size(); }

        public boolean step(int index, long insideDelayMs) throws InterruptedException {
            synchronized (list) {
                if (index < 0 || index + 1 >= list.size()) return false;
            }
            Thread.sleep(insideDelayMs);
            synchronized (list) {
                if (index < 0 || index + 1 >= list.size()) return false;
                String first = list.get(index);
                String second = list.get(index + 1);
                if (first.compareTo(second) > 0) {
                    list.set(index, second);
                    list.set(index + 1, first);
                }
                return true;
            }
        }

        public Iterator<String> iterator() {
            synchronized (list) { return new ArrayList<>(list).iterator(); }
        }
    }

    private static class Workers implements AutoCloseable {
        private final Thread[] threads;
        private final AtomicLong steps = new AtomicLong();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean running = true;

        Workers(StringList list, int count, long delayMs) {
            threads = new Thread[count];
            for (int i = 0; i < count; i++) {
                threads[i] = new Thread(() -> {
                    int index = 0;
                    try {
                        while (running) {
                            Thread.sleep(delayMs);
                            int size = list.size();
                            if (size < 2) continue;
                            if (index >= size - 1) index = 0;
                            if (list.step(index, delayMs)) steps.incrementAndGet();
                            index++;
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException | Error ex) {
                        failure.compareAndSet(null, ex);
                    }
                }, "bubble-" + (i + 1));
                threads[i].start();
            }
        }

        long steps() {
            Throwable error = failure.get();
            if (error != null) throw new IllegalStateException("Sorting worker failed", error);
            return steps.get();
        }

        public void close() {
            running = false;
            for (Thread thread : threads) thread.interrupt();
            for (Thread thread : threads) {
                try { thread.join(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
            }
            Throwable error = failure.get();
            if (error != null) throw new IllegalStateException("Sorting worker failed", error);
        }
    }

    private static StringList list(String mode) {
        return switch (mode) {
            case "linked" -> new LinkedStringList();
            case "standard" -> new StandardStringList();
            default -> throw new IllegalArgumentException("Mode must be linked or standard");
        };
    }

    private static void addLine(StringList list, String line) {
        for (int start = 0; start < line.length(); start += 80) {
            list.addFirst(line.substring(start, Math.min(start + 80, line.length())));
        }
    }

    private static void print(StringList list) {
        System.out.println("--- " + list.size() + " records ---");
        for (String value : list) System.out.println(value);
    }

    private static void benchmark(String mode, int workers, long delay, long duration, int size)
            throws InterruptedException {
        StringList list = list(mode);
        for (int i = 0; i < size; i++) list.addFirst(String.format("%08d", i));
        long actual;
        try (Workers sorters = new Workers(list, workers, delay)) {
            Thread.sleep(duration);
            actual = sorters.steps();
        }
        double theoretical = delay == 0 ? Double.NaN
                : workers * duration / (2.0 * delay);
        System.out.printf("%s: n=%d workers=%d duration=%dms delay=%dms estimate=%.1f actual=%d%n",
                mode, size, workers, duration, delay, theoretical, actual);
    }

    private static void verify(String mode) throws InterruptedException {
        StringList list = list(mode);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String value = String.format("value-%03d", i);
            expected.add(value);
            list.addFirst(value);
        }
        Collections.sort(expected);
        try (Workers ignored = new Workers(list, 4, 0)) {
            for (int i = 0; i < 500; i++) {
                List<String> observed = new ArrayList<>();
                for (String value : list) observed.add(value);
                Collections.sort(observed);
                if (!expected.equals(observed)) throw new AssertionError("Lost, duplicate or changed record");
            }
        }
        System.out.println("PASS " + mode + ": 500 snapshots during sorting preserve all 200 records");

        StringList growing = list(mode);
        List<String> inserted = new ArrayList<>();
        try (Workers ignored = new Workers(growing, 4, 0)) {
            for (int i = 0; i < 100; i++) {
                String value = String.format("new-%03d", i);
                growing.addFirst(value);
                inserted.add(value);
                List<String> observed = new ArrayList<>();
                for (String record : growing) observed.add(record);
                Collections.sort(observed);
                if (!inserted.equals(observed)) throw new AssertionError("Lost, duplicate or changed inserted record");
            }
        }
        System.out.println("PASS " + mode + ": concurrent insertion and snapshots preserve all 100 records");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--verify")) {
            verify("linked");
            verify("standard");
            return;
        }
        if (args.length == 5 && args[0].equals("--benchmark")) {
            int count = Integer.parseInt(args[1]);
            long delay = Long.parseLong(args[2]);
            long duration = Long.parseLong(args[3]);
            int size = Integer.parseInt(args[4]);
            if (count < 1 || delay < 0 || duration <= 0 || size < 2) throw new IllegalArgumentException();
            benchmark("linked", count, delay, duration, size);
            benchmark("standard", count, delay, duration, size);
            return;
        }
        if (args.length != 3) {
            System.err.println("Usage: java SortList linked|standard <workers> <delay-ms>");
            System.err.println("   or: java SortList --benchmark <workers> <delay-ms> <duration-ms> <size>");
            System.exit(2);
        }
        StringList list = list(args[0]);
        int count = Integer.parseInt(args[1]);
        long delay = Long.parseLong(args[2]);
        if (count < 1 || delay < 0) throw new IllegalArgumentException("workers >= 1 and delay >= 0");
        System.out.println("Enter lines; an empty line prints the list; EOF exits.");
        try (Workers sorters = new Workers(list, count, delay);
             BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isEmpty()) print(list);
                else addLine(list, line);
            }
            System.out.println("Total sorting attempts: " + sorters.steps());
        }
    }
}
