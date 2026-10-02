/**
 * Removes a directory tree, used by the {@code dist} Maven profile before it
 * runs {@code jpackage}.
 *
 * <p>Exists because {@code jpackage} will not write into a directory that
 * already exists, so without this the second {@code mvnw -Pdist package} on a
 * machine fails with "Application destination directory ...". That is a poor
 * first experience for the one command this project asks a user to run.</p>
 *
 * <p>Three other ways of doing this were tried and rejected:</p>
 * <ul>
 *   <li>{@code maven-antrun-plugin} needs Ant and plexus-utils, which are not
 *       present in every local Maven repository, so it breaks an offline
 *       build - and this project's builds run offline.</li>
 *   <li>The JDK's {@code rm} is absent from some JDK builds, including the
 *       JDK 25 this project is developed against.</li>
 *   <li>Running PowerShell would work on Windows and fail on macOS and Linux,
 *       and the application is built on all three.</li>
 * </ul>
 *
 * <p>The JDK's single-file source launcher needs nothing but {@code java}
 * itself and behaves identically everywhere, which is why it is used here.</p>
 *
 * <p>Two modes:</p>
 * <ul>
 *   <li>Given a directory, deletes the tree. Used to clear a previous
 *       application image, because jpackage will not write into a directory
 *       that already exists.</li>
 *   <li>Given the flag first, repairs a jpackage runtime image on a JDK whose
 *       native layout does not match what jlink expects - see
 *       {@link #repairJvmLibrary}.</li>
 * </ul>
 *
 * <p>A missing directory is not an error: that is the ordinary first-build
 * case, not a failure.</p>
 */
public class ClearPreviousImage {

    private ClearPreviousImage() {
        // static entry point
    }

    public static void main(String[] args) throws Exception {
        // --repair-jvm <image-dir> adds the JVM library a jpackage runtime
        // image is missing on some JDKs. Separate from --unlock-only because
        // it needs to know the JDK home, which is taken from the java.home
        // system property so the caller does not have to pass it.
        if (args.length > 0 && "--repair-jvm".equals(args[0])) {
            if (args.length != 2) {
                System.err.println("usage: ClearPreviousImage --repair-jvm <image-dir>");
                System.exit(2);
            }
            java.nio.file.Path image = java.nio.file.Path.of(args[1]);
            if (!java.nio.file.Files.exists(image)) {
                return;
            }
            repairJvmLibrary(image, java.nio.file.Path.of(System.getProperty("java.home")));
            return;
        }

        // --unlock-only clears the read-only attribute and stops. jpackage
        // writes the launcher read-only, which is why 'mvn clean' used to
        // leave the whole image behind; clearing it at the end of the build
        // that produced it is enough.
        boolean unlockOnly = args.length > 0 && "--unlock-only".equals(args[0]);
        int first = unlockOnly ? 1 : 0;
        if (args.length - first != 1) {
            System.err.println("usage: ClearPreviousImage [--unlock-only] <directory-or-file>");
            System.exit(2);
        }
        java.nio.file.Path root = java.nio.file.Path.of(args[first]);
        if (!java.nio.file.Files.exists(root)) {
            return;
        }

        if (unlockOnly) {
            unlock(root);
            System.out.println("unlocked " + root);
            return;
        }

        // Collect every path first, then close the stream, and only then
        // delete. Deleting while Files.walk is still streaming holds the
        // directories open, and Windows then refuses every delete - which
        // this class swallowed silently the first time, so the clear-up
        // appeared to do nothing and the second build failed in exactly the
        // same place as the first.
        java.util.List<java.nio.file.Path> paths;
        try (java.util.stream.Stream<java.nio.file.Path> walk =
                java.nio.file.Files.walk(root)) {
            paths = walk.sorted(java.util.Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList());
        }

        // Sorted deepest-first, so a directory is always emptied before it is
        // itself removed.
        //
        // Retried, because on Windows a freshly written file can be briefly
        // locked by the virus scanner or the indexer. The first delete attempt
        // throws AccessDeniedException on FHIRViewer.exe while nothing is
        // running it, and PowerShell can delete the same file moments later -
        // so it is a transient lock, not a real one, and retrying briefly
        // turns a build failure into a slightly slower build.
        int failed = 0;
        for (java.nio.file.Path path : paths) {
            if (!deleteWithRetry(path)) {
                failed++;
                System.err.println("could not delete " + path
                        + " - is anything still running from that folder?");
            }
        }

        if (failed > 0) {
            System.err.println(failed + " path(s) under " + root + " could not be removed."
                    + " Close anything running from that folder and build again.");
            System.exit(1);
        }
        System.out.println("cleared " + root);
    }

    /** Clears the read-only attribute on one file, quietly if unsupported. */
    private static void unlock(java.nio.file.Path path) {
        try {
            java.nio.file.Files.setAttribute(path, "dos:readonly", Boolean.FALSE);
        } catch (java.io.IOException | UnsupportedOperationException ignored) {
            // Not a DOS-like filesystem, or already unlocked. Nothing to do.
        }
    }

    /**
     * Puts the JVM library into a jpackage runtime image under the name the
     * launcher looks for.
     *
     * <p>Needed on Microsoft's JDK, whose {@code bin/server} holds
     * {@code jvm.dll} rather than the conventional {@code server.jvm.dll}.
     * jlink then produces an image with no {@code lib/server} directory at
     * all, and the launcher fails at startup with:</p>
     *
     * <pre>Failed to start JVM</pre>
     *
     * <p>which says nothing about the cause. The image looks complete - the
     * runtime is tens of megabytes, {@code modules} is present - so this is
     * not obvious from inspecting it. Reproduced with a bare
     * {@code jlink --add-modules java.base}, so it is the JDK's layout rather
     * than anything this project configures.</p>
     *
     * <p>A no-op when the file is already there, which is the case on a
     * conventional JDK. Nothing is removed if the copy cannot be made.</p>
     *
     * @param image the jpackage application image directory
     * @param jdkHome the JDK that built it
     */
    private static void repairJvmLibrary(java.nio.file.Path image, java.nio.file.Path jdkHome) {
        java.nio.file.Path target = image.resolve("runtime").resolve("lib")
                .resolve("server").resolve("server.jvm.dll");
        if (java.nio.file.Files.exists(target)) {
            return;
        }
        java.nio.file.Path source = jdkHome.resolve("bin").resolve("server").resolve("jvm.dll");
        if (!java.nio.file.Files.exists(source)) {
            // A JDK with neither layout: jlink produced a working image, so
            // there is nothing to do.
            return;
        }
        try {
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.copy(source, target);
            System.out.println("added " + target.getParent().getFileName()
                    + "\\server.jvm.dll - this JDK names the JVM library differently");
        } catch (java.io.IOException e) {
            System.err.println("could not add server.jvm.dll to " + target
                    + ": " + e.getMessage()
                    + " - the packaged application will fail with 'Failed to start JVM'");
            System.exit(1);
        }
    }

    /** Deletes one path, retrying briefly. True when it is gone afterwards. */
    private static boolean deleteWithRetry(java.nio.file.Path path) {
        for (int attempt = 0; attempt < 10; attempt++) {
            // jpackage marks the launcher read-only, and a read-only file cannot
            // be deleted on Windows. Without clearing it here the second build
            // fails every time, and it is the single most likely thing to go
            // wrong here: nothing is running, so the error looks inexplicable.
            // PowerShell's Remove-Item -Force copes; java.nio does not.
            if (!java.nio.file.Files.exists(path)) {
                return true;
            }
            unlock(path);
            try {
                java.nio.file.Files.deleteIfExists(path);
                return !java.nio.file.Files.exists(path);
            } catch (java.io.IOException e) {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return !java.nio.file.Files.exists(path);
    }
}