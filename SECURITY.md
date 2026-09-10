# Security

## Reporting a vulnerability

Report privately, not as a public issue: open a
[security advisory](https://github.com/dtretyakov/idea-monkeyc/security/advisories/new), or email
<dtretyakov@gmail.com>.

Please include what an attacker would be able to do and the smallest steps that show it. You should
get a first reply within a week. This is a single-maintainer project, so a fix may take longer than
that, and you will be told where it stands rather than left waiting.

## What this plugin touches

Worth knowing when judging whether something is a vulnerability at all:

* **It makes no network requests.** There is no telemetry, no analytics, no update check, and no
  HTTP client. The only URL it ever opens is Garmin's SDK download page, in the user's browser, and
  only when the user clicks a button. The only sockets are loopback connections to the Connect IQ
  simulator's own shell on ports 1234–1238.
* **It bundles nothing of Garmin's.** The compiler, language server, debug adapter, simulator,
  device data and API documentation are all read from the SDK the user installed, at the path
  Garmin's SDK Manager records.
* **It runs the SDK's programs**, always as an argument list and never through a shell.
* **It writes** the developer key where the user chose in a save dialog — created `rw-------` where
  the filesystem supports it — build output under the project, a stamp beside that output, and,
  when the user asks, a `.prg` onto an attached watch.
* **It stores no secret.** The developer key is referenced by *path* in project settings; the key
  bytes are never copied, and the only thing derived from it that is stored is a SHA-256 of the
  **public** key, used to notice that an export would go out under a different identity.

## Supported versions

The most recent release. Fixes go out as a new version through the Marketplace.
