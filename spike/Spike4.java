import jdk.jshell.JShell;

public class Spike4 {
    public static void main(String[] args) throws Exception {
        JShell shell = JShell.builder().executionEngine("local").build();

        // Non-blocking busy loop for ~5 seconds: can stop() cut it short?
        String loop = "long end = System.nanoTime() + 5_000_000_000L; "
                + "while (System.nanoTime() < end) { } ";
        long start = System.nanoTime();
        Thread worker = new Thread(() -> {
            try {
                shell.eval(loop);
                System.out.println("loop eval returned");
            } catch (Throwable t) {
                System.out.println("loop eval threw " + t);
            }
        });
        worker.start();
        Thread.sleep(400);
        shell.stop();
        worker.join(8000);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        System.out.println("busy-loop stop elapsed ms=" + elapsed + " workerAlive=" + worker.isAlive());

        // Is the session still usable after that stop?
        var events = shell.eval("\"still alive\" + \"!\"");
        for (var e : events) {
            System.out.println("reuse status=" + e.status() + " value=" + e.value());
        }
        shell.close();
    }
}
