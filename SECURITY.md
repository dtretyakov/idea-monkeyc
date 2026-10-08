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

* **It makes one network request, and only when asked.** There is no telemetry, no analytics and
  no update check. The one download is [mtp-rs](https://github.com/vdavid/mtp-rs), which installs
  a build on a watch, and it happens only when the user clicks **Install mtp-rs**: one archive for
  their platform from that project's GitHub release, at a version fixed in the plugin, and it is
  installed only if it matches the SHA-256 also fixed in the plugin — anything else is deleted
  unopened. The URLs it opens in the user's browser, again only on a click, are Garmin's SDK
  download page and the API documentation of the installed SDK. The only sockets otherwise are
  loopback connections to the Connect IQ simulator's own shell on ports 1234–1238.
* **It bundles nothing of Garmin's.** The compiler, language server, debug adapter, simulator,
  device data and API documentation are all read from the SDK the user installed, at the path
  Garmin's SDK Manager records.
* **It runs the SDK's programs, and two others to install on a watch**, always as an argument list
  and never through a shell. The two are `mtp-rs` and, on Linux, `gio`. `mtp-rs` is run from the
  path set in the settings if there is one; otherwise from the plugin's own copy, then from
  `PATH`, Homebrew's `bin`, `~/.local/bin` and `~/.cargo/bin`, in that order — directories the user
  can write to, which is worth knowing when judging what running it means. `gio` is run from
  `PATH` or `/usr/bin`.
* **It writes** the developer key where the user chose in a save dialog — created `rw-------` where
  the filesystem supports it — build output under the project, a stamp beside that output, and,
  when the user asks, a `.prg` onto an attached watch and `mtp-rs` into
  `<JetBrains shared data directory>/monkeyc/mtp-rs`.
* **It stores no secret.** The developer key is referenced by *path* in project settings; the key
  bytes are never copied, and the only thing derived from it that is stored is a SHA-256 of the
  **public** key, used to notice that an export would go out under a different identity.

## Supported versions

The most recent release. Fixes go out as a new version through the Marketplace.
