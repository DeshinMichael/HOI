import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SortListTest {
    private static String run(String[] arguments, List<String> lines, long pauseMs) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String[] command = new String[arguments.length + 3];
        command[0] = java;
        command[1] = "-cp";
        command[2] = System.getProperty("java.class.path");
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (lines != null) {
            try (var input = process.outputWriter(StandardCharsets.UTF_8)) {
                for (String line : lines) input.write(line + System.lineSeparator());
                input.flush();
                if (pauseMs > 0) Thread.sleep(pauseMs);
                input.write(System.lineSeparator());
            }
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("SortList did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new AssertionError(output);
        return output.replace("\r\n", "\n");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void testInput(String mode) throws Exception {
        String empty = run(new String[] { "SortList", mode, "2", "10000" }, List.of(), 0);
        check(empty.contains("--- 0 records ---\n"), mode + ": empty list");

        String two = run(new String[] { "SortList", mode, "2", "10000" },
                List.of("first", "second"), 0);
        check(two.contains("--- 2 records ---\nsecond\nfirst\n"), mode + ": insertion at head");

        String longLine = "A".repeat(80) + "B";
        String split = run(new String[] { "SortList", mode, "2", "10000" },
                List.of(longLine), 0);
        check(split.contains("--- 2 records ---\nB\n" + "A".repeat(80) + "\n"),
                mode + ": splitting 81 characters");
    }

    private static void testSorting(String mode) throws Exception {
        String output = run(new String[] { "SortList", mode, "4", "1" },
                Arrays.asList("z", "x", "y", "x"), 1000);
        check(output.contains("--- 4 records ---\nx\nx\ny\nz\n"),
                mode + ": sorted order or duplicate preservation");
    }

    private static void testFullSweep() throws Exception {
        List<String> lines = new ArrayList<>();
        StringBuilder expected = new StringBuilder("--- 100 records ---\n");
        for (int i = 0; i < 100; i++) {
            String value = String.format("%03d", i);
            lines.add(value);
            expected.append(value).append('\n');
        }
        String output = run(new String[] { "SortList", "linked", "4", "0" }, lines, 1000);
        check(output.contains(expected), "linked: workers did not sweep the whole list");
    }

    private static void testPromptSnapshot(String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "SortList", mode, "1", "1500").redirectErrorStream(true).start();
        try (var input = process.outputWriter(StandardCharsets.UTF_8);
             var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output.readLine();
            input.write("z" + System.lineSeparator() + "x" + System.lineSeparator());
            input.flush();
            Thread.sleep(1700);
            input.write(System.lineSeparator());
            input.flush();
            long start = System.nanoTime();
            while (!output.ready() && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 900) {
                Thread.sleep(10);
            }
            check(output.ready(), mode + ": printing waits for the sorting delay");
            check("--- 2 records ---".equals(output.readLine()), mode + ": snapshot header");
        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
    }

    public static void main(String[] args) throws Exception {
        for (String mode : List.of("linked", "standard")) {
            testInput(mode);
            testSorting(mode);
            System.out.println("PASS " + mode + ": input, splitting, sorting, duplicates");
        }
        testFullSweep();
        System.out.println("PASS linked: full-list sweep");
        for (String mode : List.of("linked", "standard")) {
            testPromptSnapshot(mode);
            System.out.println("PASS " + mode + ": prompt snapshot during sorting delay");
        }
        String stress = run(new String[] { "SortList", "--verify" }, null, 0);
        check(stress.contains("PASS linked:") && stress.contains("PASS standard:"),
                "Concurrent snapshot check");
        System.out.println("PASS concurrent snapshots");
    }
}
