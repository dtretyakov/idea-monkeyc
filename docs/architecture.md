# Architecture

How the plugin is put together, and why it is put together that way.

## The idea

The plugin carries no model of the Monkey C language. Everything that has to understand the
language is a Java program Garmin already ships in the Connect IQ SDK, and the plugin's job is to
find those programs and speak their protocols:

| Concern | What runs it | How the plugin talks to it |
|---|---|---|
| Code intelligence | `bin/LanguageServer.jar` | LSP over stdio, through [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) |
| Building | `bin/monkeybrains.jar` | command line, output parsed into the Build tool window |
| Running | the simulator — `bin/ConnectIQ.app` on macOS, `bin/simulator.exe` on Windows, `bin/simulator` on Linux — and `MonkeyDoDeux` | command line, plus a socket to the simulator's own shell |
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

## Layout

```
sdk/          finding the SDK, reading the device catalogue, locating a JVM — no IDE API
project/      settings, project layout, manifest and jungle conventions
lang/         lexers, file types, colouring, commenting, bracket matching, the api.mir viewer
lsp/          the language server client and the workarounds it needs
navigation/   go to definition, Go to Symbol and Go to Class over the Toybox API
library/      the SDK and the project's barrels, under External Libraries
build/        the compiler, and its output turned into build events
run/          run configurations, the simulator, monkeydo, installing over USB
run/session/  one connection to the simulator's shell, and the helper JVM that holds it
run/test/     the test runner's output, turned into a test tree
dap/          the debug adapter client
ui/           settings, the device selector, export, the self-check
ui/manifest/  the manifest form editor and its product table
ui/wizard/    the new project wizard
```

`run/session/` is the largest single piece here and the least obvious. The simulator's shell carries
one client at a time, so a run, a stop and a debugger cannot each open their own connection — they
share one, held by a helper JVM that reaches Garmin's own classes reflectively and cannot outlive the IDE that started it.
[Notes on the Connect IQ SDK](sdk-notes.md) has the reasons.
