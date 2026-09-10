# Developing the plugin

Building it, and the two kinds of test that catch different failures.

## Build

```bash
./gradlew build          # compile and run the unit tests
./gradlew runSelfCheck   # headless IDE: is the plugin whole, registered, and in the right menus?
./gradlew runIde         # sandbox IDE
./gradlew verifyPlugin   # compatibility check against the IDE the plugin is built on
./gradlew verifyPlugin -PverifySince              # ...and against the oldest IDE sinceBuild promises
./gradlew verifyPlugin -PverifyRecommended        # ...and against every IDE JetBrains recommends
./gradlew verifyPlugin -PverifyAgainst=<unpacked> # ...against an IDE already on disk
./gradlew buildPlugin    # distributable zip
./gradlew test -PshowOutput                       # let the tests print to the console
```

`-PverifySince` is the one worth remembering. The code compiles against the newest IDE, so nothing
else can tell you whether the promise `sinceBuild` makes to users on an older one is still true.

`runIdeWithFixture` opens the sandbox IDE on the test fixture, which is a real Connect IQ project:

```bash
./gradlew runIdeWithFixture
./gradlew runIdeWithFixture -Pfixture=/path/to/project   # ...on a project of your own
```

Without `-Pfixture` it copies the fixture under `build/` and opens the copy, so a run never leaves
the working tree dirty and a stray edit made while trying something out cannot be committed by
accident. `-Pfixture` is the way to work in a project that lasts.

### Verifying against the real SDK

```bash
./gradlew test -PliveTests
```

These build the fixture with the actual compiler, generate a project from an SDK template and
compile that too, drive the actual language server, and shake hands with the actual debug adapter.
They are opt-in so a checkout on a machine without Connect IQ still goes green — a red test there
would be reporting the machine rather than the code.

A Gradle property rather than an environment variable, because a Gradle test JVM inherits the
*daemon's* environment and not the shell's, and getting that wrong looks like the whole live suite
passing when it has in fact skipped.

`runSelfCheck` is the other half, and it catches a different kind of failure: something named in
`plugin.xml` that does not take is silent at runtime. A language server that was never registered
simply never starts, and what the user sees is an editor with no completion and nothing to report.
An `add-to-group` naming a group this IDE has not got is worse still — one line in `idea.log`, and
then a menu item that is simply not there. Both are assertions in `SelfCheckStarter`.

Between the two sit the tests that need a real `Project` — the manifest form is assembled inside
one, because a UI that compiles is not a UI that builds.

## Conventions

No per-file licence header. The Apache 2.0 text at the root covers the work, and a header repeated
across 170 files is 170 places for it to drift. If you are adding a file, do not add one.

User-visible text is US English (`Analyze`, `Colors`, `Optimization`), because that is what the
platform's own labels use and a settings page that mixes the two reads as a mistake. Comments,
KDoc and these documents are the author's own English and are left alone.

No `resource-bundle`: every string is in the source that shows it. That is a decision rather than an
oversight — the plugin ships in English only, and a bundle would put every message a file away from
the code that raises it for no reader's benefit. Revisit it if a translation is ever offered.
