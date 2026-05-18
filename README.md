# hec-dss-migrator

Self-contained Java library for migrating HEC-DSS files from version 6 to version 7,
designed to work inside a host JVM that may already have a different `javaHeclib` version
loaded.

The conversion runs in an isolated child-first classloader with its own bundled copy of
`hec-monolith` and `javaHeclib` 7-IU-0 natives, so the host application's classpath is
never touched.

## Installation

Resolved from HEC Nexus:

```gradle
repositories {
    maven { url 'https://www.hec.usace.army.mil/nexus/repository/maven-public' }
}

dependencies {
    implementation 'mil.army.usace.hec:hec-dss-migrator:0.1.1'
}
```

```xml
<repositories>
    <repository>
        <id>hec-nexus</id>
        <url>https://www.hec.usace.army.mil/nexus/repository/maven-public</url>
    </repository>
</repositories>

<dependency>
    <groupId>mil.army.usace.hec</groupId>
    <artifactId>hec-dss-migrator</artifactId>
    <version>0.1.1</version>
</dependency>
```

Requires Java 11+. The jar is self-contained — no additional dependencies needed.

## Usage

### Migrate a DSS 6 file to DSS 7

```java
// Build once and reuse — the first call extracts natives and initialises the
// isolated classloader. Subsequent calls are lightweight.
private static final Dss7Migrator migrator = Dss7Migrator.usingDefaultCache();

MigrationResult result = migrator.migrate(Path.of("/data/project.dss"));

switch (result) {
    case MIGRATED:            // converted from v6 to v7
    case ALREADY_UP_TO_DATE:  // already v7, nothing to do
    case FAILED:              // check logs for details
}
```

### Repair v6-format grids inside a v7 file

Some v7 files contain grid records that were not upgraded during file conversion.
`Dss7GridFixer` finds and rewrites them:

```java
private static final Dss7GridFixer fixer = Dss7GridFixer.usingDefaultCache();

if (fixer.hasVersion6Grids(path)) {
    fixer.fixVersion6Grids(path);
}
```

`Dss7Migrator` and `Dss7GridFixer` share the same underlying classloader — constructing
both costs only one native extraction.

## Cache directory

Natives are extracted once to a per-version subdirectory of the user cache:

| Platform | Default path                              |
|----------|-------------------------------------------|
| Windows  | `%LOCALAPPDATA%\hec-dss-migrator\`        |
| macOS    | `~/Library/Caches/hec-dss-migrator/`      |
| Linux    | `~/.cache/hec-dss-migrator/`              |

Override via system property or environment variable (applies to `usingDefaultCache()` only):

```
-Dhec.dss.migrator.cacheDir=/path/to/cache
HEC_DSS_MIGRATOR_CACHE_DIR=/path/to/cache
```

Or pass a path directly to the factory:

```java
Dss7Migrator.usingCache(Path.of("/opt/hec-cache"))
```

## Supported platforms

| Platform | Architecture | Notes              |
|----------|--------------|--------------------|
| Windows  | x86_64       |                    |
| Linux    | x86_64       |                    |
| macOS    | x86_64       |                    |
| macOS    | arm64        | Rosetta 2 only     |

## Building from source

```bash
./gradlew build
```

Output: `build/libs/hec-dss-migrator-<version>.jar`

## License

Licensed under the [MIT License](LICENSE.md).
