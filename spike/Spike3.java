import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import jdk.jshell.JShell;

public class Spike3 {
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;

        JShell shell = JShell.builder()
                .out(capture)
                .err(capture)
                .executionEngine("local")
                .build();

        // Local engine + System.out/err swap around eval
        System.out.println("--- local engine + swap ---");
        System.setOut(capture);
        System.setErr(capture);
        try {
            shell.eval("String label = \"one\";");
            shell.eval("System.out.println(label);");
            shell.eval("System.err.println(\"and me\");");
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
        realOut.println("captured: [" + buffer.toString(StandardCharsets.UTF_8).strip() + "]");
        realOut.println("restored out=" + (System.out == realOut) + " err=" + (System.err == realErr));

        // State persists across the swap windows
        buffer.reset();
        System.setOut(capture);
        System.setErr(capture);
        try {
            shell.eval("System.out.println(label + \" two\");");
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
        realOut.println("second run captured: [" + buffer.toString(StandardCharsets.UTF_8).strip() + "]");

        shell.close();
        realOut.println("done");
    }
}
