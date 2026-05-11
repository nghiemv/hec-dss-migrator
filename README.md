# hec-dss-migrator

[![CI](https://github.com/HydrologicEngineeringCenter/hec-dss-migrator/actions/workflows/ci.yml/badge.svg)](https://github.com/HydrologicEngineeringCenter/hec-dss-migrator/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache_2.0-blue.svg)](LICENSE)

Self-contained Java library for migrating HEC-DSS files from version 6 to version 7
inside a host JVM that may already have a different `javaHeclib` version loaded.

## Why this exists

Applications that use `hec-monolith` typically load a specific version of `javaHeclib`
natives into the JVM. The JVM only allows one copy of a native library per process, so
upgrading DSS files with a different `javaHeclib` version would normally conflict with
the one already loaded.

`hec-dss-migrator` solves this by running the conversion inside a **child-first
classloader** with its own bundled copy of `hec-monolith` (slim) and `javaHeclib`
7-IU-0 natives. The host application's classpath is never touched.

## Installation

```gradle
dependencies {
    implementation 'mil.army.usace.hec:hec-dss-migrator:0.1.0'
}
```

```xml
<dependency>
    <groupId>mil.army.usace.hec</groupId>
    <artifactId>hec-dss-migrator</artifactId>
    <version>0.1.0</version>
</dependency>
```

Requires Java 11 or newer.

## How it works

1. On first use, extracts platform-specific natives into a versioned per-platform
   subdirectory of the user cache directory (e.g. `~/.cache/hec-dss-migrator/<version>/<platform>/`
   on Linux, `~/Library/Caches/hec-dss-migrator/...` on macOS, `%LOCALAPPDATA%\hec-dss-migrator\...`
   on Windows). Extraction is idempotent and skipped on subsequent runs.
2. Creates an `IsolatedClassLoader` (child-first) that loads `hec-monolith` classes from
   embedded jars (streamed from the published jar in memory) and resolves `javaHeclib`
   from the extracted natives.
3. Drives conversion through `HecDSSUtilities.convertVersion` via reflection inside the
   isolated classloader.

The migration handles several cases automatically:

| Input                  | Behavior                                                  | Result               |
|------------------------|-----------------------------------------------------------|----------------------|
| Already version 7      | No-op                                                     | `ALREADY_UP_TO_DATE` |
| Version 6, empty       | Deletes and recreates as empty v7 file                    | `MIGRATED`           |
| Version 6, with data   | Converts to temp file, then atomically replaces original  | `MIGRATED`           |
| Version < 6 or missing | Logs warning                                              | `FAILED`             |

## Usage

### Migrating v6 files to v7

`Dss7Migrator` is designed for singleton usage — build it once and reuse:

```java
private static final Dss7Migrator migrator = Dss7Migrator.usingDefaultCache();

MigrationResult result = migrator.migrate(Path.of("/data/example.dss"));

switch (result) {
    case MIGRATED:            // v6 → v7 conversion succeeded
    case ALREADY_UP_TO_DATE:  // already v7, nothing to do
    case FAILED:              // check logs for details
}
```

The first call extracts natives and sets up the isolated classloader, so the factory is
the most expensive operation. `migrate()` itself is lightweight and can be called
repeatedly.

### Repairing v6-format grids inside v7 files

Some v7 files were produced by tools that did not upgrade embedded grid records when the
containing file was upgraded — they open as v7 but downstream v7-only consumers may
reject the v6-format grids. `Dss7GridFixer` is the dedicated path for finding and
rewriting those files. It is intentionally **separate** from `Dss7Migrator`: the migrator
handles file-level v6→v7 conversion; the fixer handles the v6-grid-inside-v7-file repair.

```java
private static final Dss7GridFixer fixer = Dss7GridFixer.usingDefaultCache();

if (fixer.hasVersion6Grids(path)) {
    fixer.fixVersion6Grids(path);
}
```

`fixVersion6Grids` rejects non-v7 input with `FAILED` and short-circuits to
`ALREADY_UP_TO_DATE` for v7 files that have no v6-format grids.

The two classes share the same isolated classloader (cached in `IsolatedRuntime`), so
constructing both in the same JVM costs only one extraction.

## Long paths

Heclib uses the C runtime for file I/O, which imposes path-length limits on every
platform (260 on Windows, 1024 on macOS, 4096 on Linux). Java NIO is not subject to
these limits. When the user-supplied path exceeds 240 characters the migrator copies
the source into a short-named stage under the cache directory, runs the conversion
against the stage, then atomically moves the result back over the original. UNC sources,
deep share trees, and long basenames all work transparently.

Set `-Dhec.dss.migrator.forceStaging=true` to always stage regardless of path length —
useful as an escape hatch if the 240-char threshold misses an edge case.

## Cache directory

The cache root defaults to `%LOCALAPPDATA%\hec-dss-migrator\` (Windows),
`~/Library/Caches/hec-dss-migrator/` (macOS), or `$XDG_CACHE_HOME` /
`~/.cache/hec-dss-migrator/` (Linux), falling back to `java.io.tmpdir` when those
are unwritable.

For host applications that need to put the cache somewhere specific — typically sites
where AppLocker / WDAC blocks DLL execution out of `%LOCALAPPDATA%` — pass the directory
directly to the factory:

```java
private static final Dss7Migrator migrator =
        Dss7Migrator.usingCache(Path.of("C:\\ProgramData\\hec-dss-migrator"));
```

The directory is created if missing, write-probed, and canonicalized so two factories
called with the same path share one underlying classloader. Throws
`DssMigrationException` if the path isn't writable.

For deployment-level configuration (no code change), the same effect can be had via
system property or env var — used only by `usingDefaultCache()`:

```
-Dhec.dss.migrator.cacheDir=C:\ProgramData\hec-dss-migrator
HEC_DSS_MIGRATOR_CACHE_DIR=C:\ProgramData\hec-dss-migrator
```

## Building

```bash
./gradlew build
```

The output jar is at `build/libs/hec-dss-migrator-<version>.jar`. The jar is
**self-contained** — everything needed for migration is packed inside it:

| Resource path                                              | Contents                                                                       |
|------------------------------------------------------------|--------------------------------------------------------------------------------|
| `mil/army/usace/hec/dss/migrator/isolated/`                | Slim hec-monolith jar, flogger, hec-nucleus-metadata, hec-nucleus-data, lookup |
| `mil/army/usace/hec/dss/migrator/native/win-x86_64/`       | Windows javaHeclib 7-IU-0 natives                                              |
| `mil/army/usace/hec/dss/migrator/native/linux-x86_64/`     | Linux javaHeclib 7-IU-0 natives                                                |
| `mil/army/usace/hec/dss/migrator/native/macOS-x86_64/`     | macOS javaHeclib 7-IU-0 natives                                                |
| `mil/army/usace/hec/dss/migrator/manifest.properties`      | Build-time index of resources with SHA-256 hashes and version                  |

The uniquely-prefixed resource namespace (`mil/army/usace/hec/dss/migrator/...`) makes
the library safe to shade into a host fat jar — shade plugins won't collide these
resources with other libraries' assets the way generic `META-INF/` paths would. All
resource access at runtime goes through `ClassLoader.getResourceAsStream`, so the
library works identically regardless of packaging (standalone, Spring Boot nested,
OSGi bundle, shaded host jar, or exploded classpath).

## Supported platforms

| Platform        | Architecture | Status         |
|-----------------|--------------|----------------|
| Windows         | x86_64       | Supported      |
| Linux           | x86_64       | Supported      |
| macOS           | x86_64       | Supported      |
| macOS           | arm64        | Via Rosetta 2  |
| Linux           | arm64        | Not supported  |
| Windows         | arm64        | Not supported  |

On unsupported platforms the library fails fast with a clear `DssMigrationException`
naming the platform rather than an opaque `UnsatisfiedLinkError`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). All contributions are accepted under the
[DCO](https://developercertificate.org/) sign-off.

## Security

See [SECURITY.md](SECURITY.md) for how to report vulnerabilities.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

Works of the United States federal government are not subject to copyright protection
in the United States under 17 U.S.C. § 105. The Apache 2.0 license applies in
jurisdictions where copyright does apply and provides explicit patent and attribution
terms for redistribution.
