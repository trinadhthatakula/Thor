# Security Policy

## Supported versions

Only the latest release of Thor receives security fixes. Please update to the newest version
(GitHub Releases / IzzyOnDroid / Play Store) before reporting.

| Version          | Supported |
|------------------|-----------|
| Latest release   | ✅        |
| Older versions   | ❌        |

## Reporting a vulnerability

**Please do not open a public issue for security vulnerabilities.**

Report privately through GitHub's **Report a vulnerability** button on the
[Security → Advisories page](https://github.com/trinadhthatakula/Thor/security/advisories/new).
This opens a private advisory visible only to you and the maintainer.

Because Thor performs privileged operations (Root / Shizuku / Dhizuku, and shell commands such as
`pm` and `am`), a good report includes:

- The **privilege mode** and **Thor version**.
- Steps to reproduce and the **impact** — e.g. privilege escalation, unauthorized package/data
  access, or arbitrary command execution.
- Any proof-of-concept, logs, or affected code paths.

## What to expect

- Acknowledgement of your report as soon as reasonably possible.
- An assessment and, if valid, a fix in a subsequent release — with credit, unless you prefer to
  remain anonymous.

Please give us reasonable time to ship a fix before any public disclosure. Thank you for helping
keep Thor and its users safe.
