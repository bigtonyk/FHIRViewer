// FHIRViewer scripting — a harmless first script (Phase 1).
// Open it with the Example button in Tools > Scripting Console, then press Run.
// Everything here is arithmetic, text and ordinary methods: nothing touches a
// server, and state persists between runs until you press Reset Session.

// 1. Expressions evaluate and their value is shown.
2 + 2

// 2. Variables keep their values between runs.
int attempts = 3;
attempts * 40

// 3. Strings work as usual.
String greeting = "Hello from FHIRViewer";
greeting + ", script!"

// 4. Methods, once defined, stay defined.
int square(int n) { return n * n; }
square(7)

// 5. Loops and streams are ordinary Java.
System.out.println("countdown:");
for (int n = 3; n >= 1; n--) {
    System.out.println("  " + n);
}
java.util.stream.IntStream.range(1, 6).map(n -> n * n).sum()
