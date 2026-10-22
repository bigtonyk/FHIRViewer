import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import jdk.jshell.Diag;
import jdk.jshell.JShell;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.SourceCodeAnalysis.CompletionInfo;

public class Spike {
    public static void main(String[] args) throws Exception {
        JShell shell = JShell.builder().build();

        // 1. Default imports: does java.util come in?
        System.out.println("--- default imports test ---");
        print(eval(shell, "List.of(1,2,3).size()"));

        // 2. Output capture: swap System.out around eval
        System.out.println("--- output capture test ---");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = System.out;
        PrintStream capture = new PrintStream(buffer, true, "UTF-8");
        System.setOut(capture);
        try {
            shell.eval("System.out.println(\"hello from snippet\");");
            shell.eval("System.err.println(\"oops\");");
        } finally {
            System.setOut(original);
        }
        System.out.println("captured out+err: [" + buffer.toString("UTF-8").strip() + "]");

        // 3. Multi-snippet splitting via analyzeCompletion
        System.out.println("--- splitting test ---");
        String source = "int a = 2;\na * 21";
        String remaining = source;
        while (!remaining.isBlank()) {
            CompletionInfo info = shell.sourceCodeAnalysis().analyzeCompletion(remaining);
            System.out.println("completeness=" + info.completeness()
                    + " source=[" + info.source().strip() + "] remaining=[" + info.remaining().strip() + "]");
            if (!info.completeness().isComplete() || info.remaining().length() == remaining.length()) {
                break;
            }
            remaining = info.remaining();
            print(eval(shell, info.source()));
        }

        // 4. Incomplete input
        CompletionInfo incomplete = shell.sourceCodeAnalysis().analyzeCompletion("if (true) {");
        System.out.println("incomplete completeness=" + incomplete.completeness()
                + " isComplete=" + incomplete.completeness().isComplete());

        // 5. Compile error diagnostics
        System.out.println("--- diagnostics test ---");
        List<SnippetEvent> bad = shell.eval("int x = ;");
        for (SnippetEvent e : bad) {
            System.out.println("status=" + e.status());
            shell.diagnostics(e.snippet()).forEach(d ->
                    System.out.println("  diag isError=" + d.isError() + " msg=" + d.getMessage(null)));
        }

        // 6. Runtime exception
        System.out.println("--- exception test ---");
        print(eval(shell, "\"abc\".substring(10)"));

        // 7. Stop a sleeping snippet from another thread
        System.out.println("--- stop test ---");
        long start = System.nanoTime();
        Thread worker = new Thread(() -> print(eval(shell, "Thread.sleep(30000);")));
        worker.start();
        Thread.sleep(500);
        shell.stop();
        worker.join(10000);
        System.out.println("elapsed ms=" + (System.nanoTime() - start) / 1_000_000);

        // 8. State persistence + reset (reset = close and recreate)
        System.out.println("--- state test ---");
        print(eval(shell, "int saved = 41;"));
        print(eval(shell, "saved + 1"));
        shell.close();
        JShell fresh = JShell.builder().build();
        List<SnippetEvent> after = fresh.eval("saved + 1");
        for (SnippetEvent e : after) {
            System.out.println("after reset status=" + e.status() + " exception=" + e.exception());
            fresh.diagnostics(e.snippet()).forEach(d ->
                    System.out.println("  diag msg=" + d.getMessage(null)));
        }

        // 9. A method declaration completes in one shot
        CompletionInfo semi = fresh.sourceCodeAnalysis().analyzeCompletion("int m() { return 1; }");
        System.out.println("method completeness=" + semi.completeness() + " isComplete=" + semi.completeness().isComplete());
        fresh.close();
        System.out.println("--- closed; evaluating must fail clearly ---");
        try {
            shell.eval("1+1");
        } catch (IllegalStateException closed) {
            System.out.println("after close: IllegalStateException: " + closed.getMessage());
        }
    }

    static List<SnippetEvent> eval(JShell shell, String source) {
        return shell.eval(source);
    }

    static List<SnippetEvent> print(List<SnippetEvent> events) {
        for (SnippetEvent e : events) {
            System.out.println("  event status=" + e.status() + " value=" + e.value()
                    + " ex=" + (e.exception() == null ? "none" : e.exception().getClass().getSimpleName()));
        }
        return events;
    }
}
