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

// ------------------------------------------------------------------
// Phase 2: the FHIRViewer scripting API.
// The names fhir, viewer, validation, server and log are bound before this
// script runs. Nothing here touches a server unless one is selected.

log.info("FHIRViewer scripting API example running.");

var current = server.activeServer();
if (current == null) {
    log.info("No server is selected. Configure one in Tools > FHIR Servers, "
            + "then select it, and read/search become available.");
} else {
    log.info("Connected to " + current.name() + " at " + current.baseUrl()
            + " (FHIR " + current.fhirVersion() + ").");
}

// viewer.* renders to this console; it never touches JavaFX controls directly.
viewer.showText("Ready. Try: var patient = fhir.read(\"Patient\", \"example-id\");");

square(7)
