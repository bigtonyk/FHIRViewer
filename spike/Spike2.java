import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import jdk.jshell.JShell;
import jdk.jshell.SnippetEvent;

public class Spike2 {
    public static void main(String[] args) throws Exception {
        // Output streams given to JShell at build time: route into a buffer.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        PrintStream realOut = System.out;

        JShell shell = JShell.builder()
                .out(capture)
                .err(capture)
                .executionEngine("local")
                .build();

        // 1. Output capture through Builder.out/err with the local engine
        System.out.println("--- capture via Builder.out ---");
        shell.eval("System.out.println(\"hello from snippet\");");
        shell.eval("System.err.println(\"to err\");");
        shell.eval("System.out.print(\"no newline\"); System.out.println();");
        realOut.println("captured: [" + buffer.toString(StandardCharsets.UTF_8).strip() + "]");
        realOut.println("console untouched check: System.out still real = "
                + (System.out == realOut));

        // 2. App prints during a run do not leak into the capture
        buffer.reset();
        shell.eval("System.out.println(\"only this\");");
        realOut.println("second capture: [" + buffer.toString(StandardCharsets.UTF_8).strip() + "]");

        // 3. Stop with the local engine
        System.out.println("--- stop with local engine ---");
        long start = System.nanoTime();
        Thread worker = new Thread(() -> evalQuietly(shell, "Thread.sleep(30000);"));
        worker.start();
        Thread.sleep(500);
        shell.stop();
        worker.join(10000);
        realOut.println("elapsed ms=" + (System.nanoTime() - start) / 1_000_000
                + " workerAlive=" + worker.isAlive());

        // 4. Exception message shape
        System.out.println("--- exception shape ---");
        for (SnippetEvent e : shell.eval("\"abc\".substring(10)")) {
            realOut.println("ex class=" + e.exception().getClass().getName()
                    + " message=[" + e.exception().getMessage() + "]");
        }

        // 5. Value of a println statement + expression value
        System.out.println("--- values ---");
        for (SnippetEvent e : shell.eval("40 + 2")) {
            realOut.println("value=[" + e.value() + "]");
        }

        // 6. Stop flag interplay: statement interrupted reports what?
        System.out.println("--- interrupted sleep result ---");
        Thread t2 = new Thread(() -> {
            for (SnippetEvent e : evalQuietly(shell, "Thread.sleep(10000);")) {
                realOut.println("stopped-sleep event status=" + e.status()
                        + " value=" + e.value()
                        + " ex=" + (e.exception() == null ? "none"
                                : e.exception().getClass().getSimpleName() + ":" + e.exception().getMessage()));
            }
        });
        t2.start();
        Thread.sleep(300);
        shell.stop();
        t2.join(10000);

        shell.close();
        realOut.println("done");
    }

    static java.util.List<SnippetEvent> evalQuietly(JShell shell, String source) {
        try {
            return shell.eval(source);
        } catch (Throwable t) {
            System.err.println("eval failed: " + t);
            return java.util.List.of();
        }
    }
}
