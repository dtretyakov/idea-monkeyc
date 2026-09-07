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

* **Starting** — a Connect IQ entry in the New Project dialog, built from the SDK's own templates,
  and an action to add or remove the devices a project targets. The signing key can be generated
  from the settings page: it is a 4096-bit RSA key in PKCS#8 DER, which the JVM can write, so no
  openssl is needed.
* **Editing** — completion over the whole Toybox API, diagnostics, go-to-definition, hover with
  documentation, rename, find usages, document and workspace symbols, folding, type and call
  hierarchies. Syntax highlighting, commenting and bracket matching for `.mc`, `.jungle` and `.mss`.
* **Building** — in the Build tool window, with the compiler's errors as something to click.
* **Running** — Run and Debug configurations for the app and for its unit tests, a target device in
  the status bar, and an Export action that produces the `.iq` for the store.
* **Debugging** — breakpoints, stepping, variables, and evaluate-on-hover.

### Three things the server does that a client has to work around

All three were confirmed against SDK 9.1.0, and each has a live test that will go red when Garmin
fixes it:

* `textDocument/definition` answers with `file:/abs/path` — one slash, no authority — so nothing
  downstream resolves it. Repaired in `MonkeyCFileUriSupport`.
* `textDocument/signatureHelp` dereferences a context the protocol says is optional, and throws an
  NPE without one. LSP4IJ builds that request and exposes no hook for altering it, so the context is
  added on the wire by `SignatureHelpContextFilter`.
* The server matches an open document against the files the compiler resolved, and the compiler
  resolves through symlinks. A project reached by another name gets `Could not find file context`
  for everything — no completion, no navigation, and no error anywhere. `CanonicalPaths` resolves
  symlinks out of both the workspace root and the document URIs, which is what it takes.

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
./gradlew verifyPlugin   # compatibility check (add -PverifyAgainst=<unpacked IDE> to skip its download)
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

## Layout

```
sdk/       finding the SDK, reading the device catalogue, locating a JVM — no IDE API
project/   settings, project layout, manifest and jungle conventions
lang/      lexers, file types, colouring, commenting, bracket matching
lsp/       the language server client and the workarounds it needs
build/     the compiler, and its output turned into build events
run/       run configurations, the simulator, monkeydo
dap/       the debug adapter client
ui/        settings, the device widget, export, products, the wizard, the self-check
```
