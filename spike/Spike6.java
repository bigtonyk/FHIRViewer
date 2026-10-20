import jdk.jshell.JShell;
import jdk.jshell.SourceCodeAnalysis;

public class Spike6 {
    public static void main(String[] args) {
        JShell shell = JShell.builder().executionEngine("local").build();

        show(shell, "int p = 1; int q = 2;\n// then\np + q");
        show(shell, "int p = 1; foo();\n");
        try {
            System.out.println("direct eval of two-var line:");
            shell.eval("int p = 1; int q = 2;").forEach(e ->
                    System.out.println("  event=" + e.status() + " value=" + e.value()));
        } catch (Throwable t) {
            System.out.println("  threw " + t);
        }
        shell.close();
    }

    static void show(JShell shell, String input) {
        System.out.println("INPUT {{" + input.replace("\n", "\\n") + "}}");
        SourceCodeAnalysis.CompletionInfo info = shell.sourceCodeAnalysis().analyzeCompletion(input);
        System.out.println("  completeness=" + info.completeness()
                + " src={" + (info.source() == null ? "null" : info.source().replace("\n", "\\n")) + "}"
                + " rem={" + (info.remaining() == null ? "null" : info.remaining().replace("\n", "\\n")) + "}");
    }
}
