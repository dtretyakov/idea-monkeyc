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

Upload `build/distributions/idea-monkeyc-0.1.0-signed.zip` at
<https://plugins.jetbrains.com/plugin/add>. The archive is named after `rootProject.name` in
`settings.gradle.kts`, not after the plugin's display name — `pluginConfiguration.name` sets
`<name>` inside the descriptor and nothing else.

Then, on the listing page:

- **License** — Apache 2.0, matching `LICENSE`. The Marketplace will not publish without one.
- **EEA trader / non-trader declaration** — mandatory, and it blocks publication until it is
  answered. An individual publishing a free plugin is a non-trader.
- **Tags** — the ones that decide whether anybody finds it. `Languages`, `Build`, `Debugging`,
  `Embedded Development`.
- **Screenshots** — the four in [`docs/images/marketplace/`](docs/images/marketplace), in order.
  All 1280×800, which is the size the guidelines recommend and the ratio they insist be the same
  across every shot. They show the four things nothing else in the ecosystem has:

  | | |
  |---|---|
  | `1-manifest-devices.png` | the manifest form's product table, and under it what the selection costs: *8 selected · 6 resource families · memory 96 KB–2304 KB · 4 without touch · color down to 1-bit* |
  | `2-device-chip.png` | the target chip beside Run, open on the eight devices the manifest declares |
  | `3-build-memory.png` | a build reporting *Memory: 88.7 KB of 768.0 KB (12%) on fēnix® 7 / quatix® 7* |
  | `4-export-preflight.png` | the export preflight naming the languages particular devices will not ship, which is invisible everywhere else |

  They were taken against a real SDK on a demo project — a watch app declaring eight devices across
  six resource families and ten languages — because the summary line and the preflight say nothing
  interesting about the two-device test fixture. Retake them the same way: a 1560×975 window
  (16:10), captured on a Retina display and scaled to 1280×800.
- **What's new** — read the rendered release notes on the listing before publishing. They are
  generated from `CHANGELOG.md`, and the Marketplace sanitises HTML more strictly than the plugin
  descriptor does; `<h4>`, which `### ` becomes, is the tag to look at.
- **Source code** and **Issue tracker** — <https://github.com/dtretyakov/idea-monkeyc> and its
  issues. **The repository has to be public before this is submitted.** Every external link on the
  plugin page is checked for being reachable, and an open-source licence is only accepted with a
  source link behind it — a 404 is a rejection, not a warning.

Review takes a few working days for a first submission. Every later version goes out through the
workflow without review.

### The one thing worth having an answer ready for

*Monkey C* is Garmin's mark, and the approval guidelines forbid third-party trademarked names used
without authorization. The case for it is nominative use: the plugin is named after the language it
supports, exactly as every other language plugin on the Marketplace is, and it says so — the
description carries a Trademarks section naming Garmin as the owner and denying affiliation, and the
plugin displays no Garmin logo or brand element anywhere.

The other thing a reviewer may raise is internal API usage, which the verifier reports and this
build does not fail on. The three, and why each is the only way to do what it does, are at the
bottom of this file.

## Every release after that

1. Write the release's section at the top of `CHANGELOG.md` — it becomes the Marketplace's release
   notes verbatim, through `latestChangeNotes` in `build.gradle.kts`. That converter understands
   `### ` subheadings, `- ` bullets with wrapped continuation lines, and inline backticks, and
   nothing else: a `**bold**` or a markdown link ships to the Marketplace as literal punctuation.
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
  declares is really registered, and every action it declares is really in the menu it names. Both
  are silent at run time when they are wrong: the feature simply never happens, or the menu item
  simply is not there — the second being how an `add-to-group` naming a group this IDE has not got
  behaves. It also prints the platform's verdict on whether the plugin can be unloaded
  without restarting the IDE; that should stay `yes`, and it turns to `no` the moment an extension
  point that is not dynamic is added. A `yes` there does not stop the IDE showing "Failed to unload
  modified plugins" in the development sandbox — that comes from a later step, and LSP4IJ hits it
  too — but it does keep the half that is ours honest.

Two things the verifier always reports, neither of which is a finding:

**Internal API.** Twenty-nine usages, of a short list: the Build tool window's event classes,
`TogglePopupAction` for the toolbar chip, and `ModernApplicationStarter` plus `DynamicPlugins` for
the self-check above. Each is the only way to do what it does, and each is used by plugins JetBrains
ships. The Marketplace's guidelines name internal API as something review looks at, so this is the
paragraph to have ready.

**Deprecated API.** Nine distinct APIs across eighteen call sites, and every one of them is
deliberate: they are the forms that exist in *both* IDEs the plugin supports. `FilePosition(File, …)`
— the one the verifier reports as scheduled for removal — `runReadAction`, `ActionUtil.invokeAction`
and the six Build event constructors were each superseded in 2026.2 by something 2026.1 does not
have, so using the replacement would narrow the supported range to a single IDE. When `sinceBuild`
moves up to 262, these are the calls to modernise, and the verifier's own report is the list:

```bash
./gradlew verifyPlugin
open build/reports/pluginVerifier/*/plugins/com.github.dtretyakov.monkeyc/*/deprecated-usages.txt
```
