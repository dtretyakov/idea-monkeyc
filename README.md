# idea-monkeyc

An IntelliJ IDEA plugin for Garmin Connect IQ development: Monkey C editing, building, running in
the Connect IQ simulator, and debugging.

## The idea

The plugin carries no model of the Monkey C language. Everything that has to understand the
language is a Java program Garmin already ships in the Connect IQ SDK, and the plugin's job is to
find those programs and speak their protocols:

| Concern | What runs it | How the plugin talks to it |
|---|---|---|
| Code intelligence | `bin/LanguageServer.jar` | LSP over stdio, through [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) |
| Building | `bin/monkeybrains.jar` | command line, output parsed into the Build tool window |
| Running | `bin/ConnectIQ.app` + `MonkeyDoDeux` | command line |
| Debugging | `bin/LanguageServer.jar` | DAP over stdio, through LSP4IJ's DAP client |

Nothing from Garmin is redistributed: the plugin finds the SDK the user installed with the SDK
Manager and launches its jars.

That is the whole design argument. Monkey C changes with every SDK release — new types, new
annotations, new `.mss` properties — and a parser maintained in this plugin would be behind from the
day it shipped. The language server *is* the compiler's front end, so it is never behind. What the
plugin does implement is a lexer, which is what colouring, commenting and bracket matching need and
all they need.

The debug adapter is worth a note, because the received wisdom is that it lives only inside the
official VS Code extension. It does not: `com.garmin.monkeybrains.monkeydodo.DebugAdapterProtocol`
is in the SDK. It has to be started from `LanguageServer.jar` rather than `monkeybrains.jar` —
that one has the adapter and lsp4j but no gson between them, and dies on `NoClassDefFoundError`
before it can answer `initialize`.

## What works

* **Starting** — a Connect IQ entry in the New Project dialog, built from the SDK's own templates.
  The signing key can be generated from the settings page: it is a 4096-bit RSA key in PKCS#8 DER,
  which the JVM can write, so no openssl is needed.
* **The manifest** — a form tab beside the XML for the parts that are lists of identifiers nobody
  remembers: devices, permissions, language codes. It edits the file as text rather than through a
  DOM, so the comments and the formatting of a file being edited by hand in the next tab survive.
* **Editing** — completion over the whole Toybox API, diagnostics, go-to-definition, hover with
  documentation, parameter info, rename, find usages, document and workspace symbols, the file
  structure popup, breadcrumbs, folding, type and call hierarchies. Syntax highlighting,
  commenting, bracket matching and self-closing quotes for `.mc`, `.jungle` and `.mss`.
* **Building** — in the Build tool window, with the compiler's errors as something to click, and
  the compiler skipped entirely when the output already matches the sources and the flags.
* **Running** — six run configurations: the app, its unit tests, a build that runs nothing, an
  export to `.iq`, a barrel, and a barrel's tests. The build that runs nothing can target the watch
  rather than the simulator, which is the only way to get a `.prg` to copy to `GARMIN/APPS`. The
  device is chosen beside the Run button and defaults to the first product the manifest declares.
* **Tests** — in a test tree, with a green arrow beside every `(:test)` function, the tests of one
  file or directory from its context menu, and every test in the project from its root.
* **Debugging** — breakpoints, stepping, variables, evaluate-on-hover, and a complication pair: a
  second project run alongside the first.
* **The simulator** — started when a run needs one, and stopped or restarted from
  `Tools | Connect IQ Simulator` when it wedges, which after a few hours it does.

### What the SDK's own tools do that a client has to work around

All of it was confirmed against SDK 9.1.0, and most of it has a live test that will go red when
Garmin fixes it:

* The language server unboxes booleans the protocol says are optional. Seventeen
  `dynamicRegistration` flags and `foldingRange.lineFoldingOnly`, all absent from what LSP4IJ sends,
  each an NPE inside `initialize` — which is to say the server never starts and nothing works.
  `RequiredFieldsFilter` fills them in on the wire, along with the `signatureHelp` context the
  server dereferences without checking.
* `textDocument/definition` answers with `file:/abs/path` — one slash, no authority — so nothing
  downstream resolves it. Repaired in `MonkeyCFileUriSupport`.
* The server matches an open document against the files the compiler resolved, and the compiler
  resolves through symlinks. A project reached by another name gets `Could not find file context`
  for everything — no completion, no navigation, and no error anywhere. `CanonicalPaths` resolves
  symlinks out of both the workspace root and the document URIs, which is what it takes.
* `barreltest` refuses the `_sim` device suffix that `monkeyc` requires, though the `.prg` it
  produces is the one the simulator runs; and `barrelbuild` refuses anything outside the barrel's
  own namespace, so a top-level `(:test)` function breaks the barrel rather than merely failing to
  run.
* Unit tests cannot be debugged by anything. The adapter's `entry` reads
  `if (!mRunTests && isForegroundApp) mClient.initialized();`, and a DAP client registers
  breakpoints only after `initialized` — so a test run has no moment at which a breakpoint can be
  set, and `stopAtLaunch` is ignored there too. A test configuration therefore does not offer
  Debug, and a live test asserts the absence of that event so the refusal can be lifted the day it
  arrives.

## Requirements

* The Connect IQ SDK, installed with Garmin's SDK Manager. Code intelligence needs 8.1.0 or newer —
  that is when `LanguageServer.jar` first shipped; building and running work with anything older.
* IntelliJ IDEA 2025.2 or newer
* [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij), which the IDE installs alongside this
  plugin

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

## Layout

```
sdk/       finding the SDK, reading the device catalogue, locating a JVM — no IDE API
project/   settings, project layout, manifest and jungle conventions
lang/      lexers, file types, colouring, commenting, bracket matching
lsp/       the language server client and the workarounds it needs
build/     the compiler, and its output turned into build events
run/       run configurations, the simulator, monkeydo
run/test/  the test runner's output, turned into a test tree
dap/       the debug adapter client
ui/        settings, the device selector, export, the wizard, the manifest form, the self-check
```
