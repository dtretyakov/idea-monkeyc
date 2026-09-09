# Developing the plugin

Building it, and the two kinds of test that catch different failures.

## Build

```bash
./gradlew build          # compile and run the unit tests
./gradlew runSelfCheck   # headless IDE: is the plugin whole and are its extensions registered?
./gradlew runIde         # sandbox IDE
./gradlew verifyPlugin   # compatibility check against the IDE the plugin is built on
./gradlew verifyPlugin -PverifyRecommended        # ...and against every IDE JetBrains recommends
./gradlew verifyPlugin -PverifyAgainst=<unpacked> # ...against an IDE already on disk
./gradlew buildPlugin    # distributable zip
```

`runIdeWithFixture` opens the sandbox IDE on the test fixture, which is a real Connect IQ project:

```bash
./gradlew runIdeWithFixture
```

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

`runSelfCheck` is the other half, and it catches a different kind of failure: an extension named in
`plugin.xml` that does not register is silent at runtime. A language server that was never
registered simply never starts, and what the user sees is an editor with no completion and nothing
to report.

Between the two sit the tests that need a real `Project` — the manifest form is assembled inside
one, because a UI that compiles is not a UI that builds.
