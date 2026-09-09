# Publishing

Everything the repository can automate is automated: `.github/workflows/release.yml` builds, tests,
verifies against both ends of the supported IDE range, signs, uploads and cuts a GitHub release.
What is left here is the part that needs an account, a secret, or a human — the first upload, the
signing certificate, and the listing itself.

## Once, before the first release

### 1. A signing certificate

The Marketplace accepts an unsigned upload and signs it with its own key, so this is optional. It
is worth doing anyway: an unsigned plugin installs behind a warning dialog, and that dialog is the
first thing a new user sees.

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
```

Keep `private_encrypted.pem` and its password somewhere that survives a lost laptop. A certificate
that cannot be reproduced means every future release is signed by a different key, which is exactly
what the signature exists to rule out.

The three GitHub secrets the release workflow reads:

| Secret | Contents |
|---|---|
| `PRIVATE_KEY` | the whole of `private_encrypted.pem`, `-----BEGIN` line included |
| `PRIVATE_KEY_PASSWORD` | the password given to `genpkey` |
| `CERTIFICATE_CHAIN` | the whole of `chain.crt` |

Both files are multi-line. GitHub's secret field takes multi-line values as they are; if a tool in
the way does not, base64 them — the Gradle plugin detects and decodes that.

### 2. A Marketplace account and a token

Sign in at <https://plugins.jetbrains.com> with a JetBrains account, then
**Profile | My Tokens | Generate Token**. That token goes into the `PUBLISH_TOKEN` secret. It is
what `publishPlugin` authenticates with, and it is scoped to the account, not to this plugin — a
leak is worth revoking immediately.

### 3. The first upload, by hand

The Marketplace only creates a listing through the web form; `publishPlugin` can update a plugin
that exists but cannot bring one into being. So the first release goes up manually:

```bash
./gradlew clean build
CERTIFICATE_CHAIN="$(cat chain.crt)" \
PRIVATE_KEY="$(cat private_encrypted.pem)" \
PRIVATE_KEY_PASSWORD="…" \
  ./gradlew signPlugin verifyPluginSignature
```

Upload `build/distributions/Monkey C-0.1.0-signed.zip` at
<https://plugins.jetbrains.com/plugin/add>. Then, on the listing page:

- **License** — Apache 2.0, matching `LICENSE`. The Marketplace will not publish without one.
- **Tags** — the ones that decide whether anybody finds it. `Languages`, `Build`, `Debugging`,
  `Embedded Development`.
- **Screenshots** — at least 1200×760. The four worth showing are the ones nothing else in the
  ecosystem has: the manifest form's product table with the summary line under it, the target chip
  beside the Run button, a build reporting what it took of the device's memory, and the export
  preflight naming the languages a device will not ship.
- **Source code** and **Issue tracker** — <https://github.com/dtretyakov/idea-monkeyc> and its
  issues.

Review takes a few working days for a first submission. Every later version goes out through the
workflow without review.

## Every release after that

1. Write the release's section at the top of `CHANGELOG.md` — it becomes the Marketplace's release
   notes verbatim, through `latestChangeNotes` in `build.gradle.kts`.
2. Set `pluginVersion` in `gradle.properties`. It only ever goes up: the Marketplace refuses a
   version it has already seen, including one it rejected.
3. Tag it and push the tag.

```bash
git tag v0.2.0 && git push origin v0.2.0
```

The workflow refuses to publish if the tag and `pluginVersion` disagree, which is the mistake that
otherwise ships one version under another's name.

### Betas

A version with a suffix — `0.2.0-beta.1`, `0.2.0-eap.1` — publishes to a channel of that name
instead of to everybody. Users opt in by adding
`https://plugins.jetbrains.com/plugins/beta/list` under **Settings | Plugins | Manage Plugin
Repositories**. The GitHub release is marked pre-release and carries the same zip, for anyone who
would rather install from disk than add a repository.

## What the checks are for

- `./gradlew verifyPlugin` — against the IDE the plugin is compiled with. The one that has to pass.
- `./gradlew verifyPlugin -PverifySince` — against the *oldest* IDE `sinceBuild` promises. Nothing
  else can tell you the promise is true, because the code compiles against the newest.
- `./gradlew verifyPlugin -PverifyRecommended` — the whole sweep JetBrains recommends. Several
  gigabytes of IDE downloads; run it on a machine with the disk for it, not in CI.
- `./gradlew runSelfCheck` — a headless IDE that starts the plugin and checks every extension it
  declares is really registered. A missing registration is silent at run time: the feature simply
  never happens. It also prints the platform's verdict on whether the plugin can be unloaded
  without restarting the IDE; that should stay `yes`, and it turns to `no` the moment an extension
  point that is not dynamic is added. A `yes` there does not stop the IDE showing "Failed to unload
  modified plugins" in the development sandbox — that comes from a later step, and LSP4IJ hits it
  too — but it does keep the half that is ours honest.

Two things the verifier always reports, neither of which is a finding:

**Internal API.** The Build tool window's event classes, `TogglePopupAction` for the toolbar chip,
and `ModernApplicationStarter` for the self-check above. Each is the only way to do what it does,
and each is used by plugins JetBrains ships.

**Deprecated API.** Eight calls, and all eight are deliberate: they are the forms that exist in
*both* IDEs the plugin supports. `FilePosition(File, …)`, `runReadAction`, and the Build event
constructors were each superseded in 2026.2 by something 2026.1 does not have, so using the
replacement would narrow the supported range to a single IDE. When `sinceBuild` moves up to 262,
these are the calls to modernise, and the verifier's report is the list.
