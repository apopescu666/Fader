# Security Policy

## Reporting a vulnerability

Do not post credentials, private library data, or details of an unpatched vulnerability in a public issue or discussion.

If the repository’s Security tab provides **Report a vulnerability**, use that private reporting channel. If it is unavailable, ask the maintainer for a private reporting channel without publishing the vulnerability details.

Include the affected version/build, macOS version, impact, and minimal reproduction steps. Remove personal file paths, music files, and unrelated user data from attachments.

## Scope and support

Build 57 is the current review baseline. There is no guaranteed response time or long-term security support commitment. Reports concerning older builds may require reproduction on the current baseline.

The distributed build is ad-hoc signed and not notarized by Apple. Obtain release artifacts from the official repository and compare their published SHA-256 checksums. Checksums verify file integrity against the published values; they do not replace a trusted publisher signature.
