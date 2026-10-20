import jdk.jshell.JShell;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.SourceCodeAnalysis.Completeness;
import jdk.jshell.SourceCodeAnalysis.CompletionInfo;

public class Spike5 {
    public static void main(String[] args) {
        JShell shell = JShell.builder().executionEngine("local").build();
        String[] inputs = {
                "// just a comment",
                "// comment first\n2 + 2",
                "int a = 1;\n// mid comment\nint b = a + 1;\nb * 10",
                "   ",
                "/** javadoc-ish */\nint c = 5;"
        };
        for (String input : inputs) {
            System.out.println("INPUT [" + input.replace("\n", "\\n") + "]");
            String remaining = input;
            int guard = 0;
            while (!remaining.isBlank() && guard++ < 20) {
                CompletionInfo info = shell.sourceCodeAnalysis().analyzeCompletion(remaining);
                Completeness c = info.completeness();
                System.out.println("  completeness=" + c
                        + " source=[" + info.source().replace("\n", "\\n") + "]"
                        + " remaining=[" + info.remaining().replace("\n", "\\n") + "]");
                if (info.remaining().length() == remaining.length()) {
                    System.out.println("  (no progress)");
                    break;
                }
                remaining = info.remaining();
                if (c.isComplete() && !info.source().isBlank()) {
                    shell.eval(info.source());
                }
            }
            System.out.println("  final remaining blank=" + remaining.isBlank());
        }
        shell.close();
    }
}
