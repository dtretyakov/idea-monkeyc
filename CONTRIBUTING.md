# Contributing

Bug reports and pull requests are both welcome. This file is the short version of what the
repository expects; [docs/developing.md](docs/developing.md) is how to build and test it.

## Reporting a bug

The two things that make a Connect IQ bug reproducible are the ones nobody thinks to include:

* **The SDK version**, from *Settings | Languages & Frameworks | Monkey C*, and the device you were
  building for.
* **Your operating system.** Development happens on macOS; Windows and Linux get less exercise, and
  a bug on either is more likely to be real than a duplicate.

If the IDE said something, `Help | Show Log in Finder` has the whole of it, and the LSP console
(*View | Tool Windows | Language Servers*) has what the language server was asked and what it
answered.

## Before opening a pull request

```bash
./gradlew build          # compile and run the unit tests
./gradlew runSelfCheck   # is the plugin whole, registered, and in the right menus?
./gradlew verifyPlugin -PverifySince
```

The last one is the one people forget. The plugin compiles against the newest IDE and promises to
run in an older one, and that check is the only thing that can tell you the promise is still true.

If you have a Connect IQ SDK installed, `./gradlew test -PliveTests` runs the other half of the
suite against the real compiler, simulator, language server and debug adapter. It is opt-in so a
machine without the SDK still goes green.

## Conventions

* **No per-file licence header.** The Apache 2.0 text at the root covers the work; a header repeated
  across 170 files is 170 places for it to drift.
* **User-visible text is US English** — `Analyze`, `Colors`, `Optimization` — because that is what
  the platform's own labels use. Comments and KDoc are the author's own English and are left alone.
* **Comments say why, not what.** The code in this repository is unusually heavily commented, and
  what is commented is the reasoning: a workaround for something Garmin's tools do, a threading
  constraint, a shape that was tried and did not work. If a comment would restate the line under it,
  it is not worth writing.
* **Nothing of Garmin's is committed.** No SDK files, no templates, no device data, no `api.mir`
  excerpts, no documentation prose. Test fixtures are written for the test, in the SDK's *formats*.
  This is a claim the README and the plugin description both make, and it has to stay true.
* **No `resource-bundle`.** Every string lives in the source that shows it. Revisit if a translation
  is ever offered.

## Commit messages

A subject line that says what changed, in the present tense, and a body that says why if the why is
not obvious. No attribution trailers.
