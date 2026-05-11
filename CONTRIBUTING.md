# Contributing to hec-dss-migrator

Thanks for your interest in contributing. This project is maintained by the
US Army Corps of Engineers, Hydrologic Engineering Center (HEC).

## Ground rules

- Be respectful — see [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
- Discuss large changes in an issue first. Small fixes, doc improvements, and
  test additions can go straight to a pull request.
- All commits must be signed off under the
  [Developer Certificate of Origin](https://developercertificate.org/) (DCO).
  Add `-s` to your `git commit` command, or paste the trailer manually:

  ```
  Signed-off-by: Your Name <your.email@example.com>
  ```

  By signing off you certify that you have the right to submit the contribution
  under the project's license. Government-affiliated contributors should sign
  off with the email address associated with their official capacity.

- We do not require a CLA.

## Development setup

Requirements:

- JDK 11 or newer
- Git
- A network connection that can reach `repo.maven.apache.org` and
  `www.hec.usace.army.mil/nexus/repository/maven-public`

Build and test:

```bash
./gradlew build
```

Strict-mode tests (long paths, non-ASCII cache dirs, alternate locale/encoding):

```bash
./gradlew test -PstrictEnv=true
```

## Coding style

- Java 11 baseline (compile target is `--release 11`). Do not use language features
  past 11.
- Public API changes require a documented rationale in the PR description and an
  updated `Automatic-Module-Name`-friendly package layout (avoid splitting types
  across packages).
- Avoid adding new runtime dependencies. The published jar embeds its dependencies,
  so each one inflates the artifact and constrains downstream consumers. New
  test-only dependencies are easier to justify.

## Pull request checklist

- [ ] Tests cover the change. Bug fixes include a regression test that fails
      before the fix.
- [ ] `./gradlew build` passes locally.
- [ ] CI is green on all three platforms (Windows, Linux, macOS).
- [ ] Public API changes are documented in the README or Javadoc.
- [ ] Commits are signed off.

## Reporting bugs

Open an issue with:

- Library version (`build/libs/hec-dss-migrator-<version>.jar`)
- Host OS, architecture, JDK version
- Whether the host application loads its own `javaHeclib`, and which version
- A minimal reproducer (a `.dss` file is ideal; if the data is sensitive,
  describe the structure: record count, types, path patterns)
- The exception or log output

## Security issues

Do **not** open a public issue. See [SECURITY.md](SECURITY.md).
