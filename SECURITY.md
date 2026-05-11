# Security Policy

## Supported versions

Security fixes are applied to the latest published minor version. Older versions
are not patched; users are expected to upgrade.

| Version | Supported |
|---------|-----------|
| 0.1.x   | Yes       |

## Reporting a vulnerability

**Do not open a public GitHub issue for security reports.**

Please report vulnerabilities by emailing:

> dll-ceiwr-hec-cots-orders@usace.army.mil

Include:

- A description of the vulnerability and its potential impact
- Steps to reproduce, including a minimal proof of concept if possible
- The version of `hec-dss-migrator` affected
- Whether you would like to be credited in the fix's release notes

You should receive an acknowledgement within 5 business days. We aim to provide
a remediation plan within 30 days of the initial report. Coordinated disclosure
timelines are negotiated case by case.

## Scope

In-scope:

- The published `hec-dss-migrator` jar and any class it loads from its embedded
  resources.
- Native-extraction, cache-directory, and classloader-isolation paths.

Out of scope:

- Vulnerabilities in third-party libraries that are not actively exploitable
  through `hec-dss-migrator`'s usage of them. Report those upstream.
- Vulnerabilities that require the attacker to already have write access to the
  user's cache directory or the host JVM.
