# Building a launchable build

The viewer is a JavaFX application, and there are two ways to get one running.
This document covers the second: a build someone can launch by double-clicking,
with no JDK and no commands to type.

## The short version

```
mvnw -Pdist package
```

Then open:

```
target\dist-image\FHIRViewer\FHIRViewer.exe
```

That folder is the deliverable. Copy the whole `FHIRViewer` folder to someone and
they double-click the `.exe`. They do not need Java installed, Maven installed,
or this repository.

It takes a few minutes, because it packs a Java runtime into the result.

## What you get

| Path | What it is |
|---|---|
| `target\dist-image\FHIRViewer\FHIRViewer.exe` | The launcher. Double-click it. |
| `target\dist-image\FHIRViewer\app\` | The application jars. |
| `target\dist-image\FHIRViewer\runtime\` | A private Java runtime, trimmed to what the application needs. |

About 250 MB in total. That is the cost of not asking the person receiving it to
install anything.

## Requirements

- **A JDK, 21 or newer**, with `jpackage` on it. Every standard JDK from 21
  onwards includes it, so there is nothing extra to install.
- **Maven**, or the bundled `mvnw.cmd` / `./mvnw` wrapper.

Check `jpackage` is available before you start:

```
jpackage --version
```

If that reports "not recognized", your JDK is incomplete or `JAVA_HOME` points
somewhere unexpected.

## Building for other platforms

`jpackage` cannot cross-compile. Each platform's build must be made on that
platform:

| Built on | Produces |
|---|---|
| Windows | `FHIRViewer.exe` |
| macOS | `FHIRViewer.app` |
| Linux | A launcher script |

The same `mvnw -Pdist package` command works everywhere; only the output
differs. A Windows build will not run on Linux, and there is no way around that
with this tool.

## Shipping it to someone

**The application image is enough.** Zip the `FHIRViewer` folder and send it.
The recipient unzips it and double-clicks the `.exe`.

Two things worth saying to them:

- **Windows may warn about an unknown publisher.** That is expected for any build
  not signed with a code-signing certificate. It is a consequence of how the
  build is distributed, not a defect in it.
- **Antivirus may quarantine it.** Also expected for an unsigned executable that
  carries its own runtime.

Neither is fixable in this repository; both need a signing certificate.

## What is *not* included

- **No installer.** See [Why there is no installer](#why-there-is-no-installer).
- **No auto-update.** A new build means a new download.
- **No code signing.** See above.
- **The bundled runtime is not portable across platforms**, for the reason in
  [Building for other platforms](#building-for-other-platforms).

## How it works

`mvnw -Pdist package` adds four steps to the ordinary build. All of them only
run under the `dist` profile, so `mvnw package` on its own is unchanged and
still produces just the jar.

1. **Collect the application jar and its runtime dependencies** into
   `target/dist-input`.
2. **Remove any previous image** from `target/dist-image`, because `jpackage`
   refuses to write into a directory that already exists.
3. **Run `jpackage`** to build the application image.
4. **Clear the read-only attribute** `jpackage` puts on the launcher, so that
   `mvnw clean` can delete it afterwards.

`src/main/dist/clear-previous-image.java` does the file removal in steps 2 and 4.
It is a single-file Java program rather than a shell script so it behaves the
same on all three platforms.

## Troubleshooting

**`mvnw clean` fails with "Failed to delete ...\FHIRViewer.exe"**

Fixed by step 4. If you see it, the build that produced the image did not run
step 4 - most often because the image was built by an older version of this
project. Delete `target\dist-image` by hand once, and it will not recur.

**`Error: Application destination directory ... already exists`**

`jpackage` will not overwrite. `mvnw clean` first, or delete `target\dist-image`
by hand.

**`could not find or load main class`**

The helper in step 2 could not be found. It is read from
`src/main/dist/clear-previous-image.java`, so this means the file is missing or
the build is being run from the wrong directory.

**`Two versions of module okio found`**

A dependency conflict in the collected jars. `okio` and `okio-jvm` are the same
artifact and only `okio-jvm` is kept, which the build already handles. If this
appears, a new dependency has introduced another such pair - check
`target/dist-input` for two jars whose names differ only by an `-jvm` suffix.

**`The configured main jar does not exist`**

The application jar was not collected. This happens if the copy step runs before
the jar is built; if you have moved that step in the pom, move it back to the
`package` phase.

**`Module javafx.base not found`**

The JavaFX jars were not staged for the module path. The build keeps them in
`target/dist-modules` for exactly this reason, and the jpackage step points
there. If you have removed that staging step, the JavaFX modules cannot be
resolved.

**The build is slow**

Expected. Packing a runtime takes minutes. If it is much slower than that,
something is downloading - the build is not running offline.

## Why there is no installer

A `.msi` requires WiX Toolset on the build machine, and its absence would make
`mvnw -Pdist package` fail outright rather than produce the image that actually
works. A build step that fails for want of an optional extra is worse than one
that leaves the extra out.

The application image is fully usable on its own, so the installer was left out
rather than made optional. Adding it later means adding a second `jpackage`
invocation with `--type msi`, which is a small change.