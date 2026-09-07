# idea-monkeyc

An IntelliJ IDEA plugin for Garmin Connect IQ development: Monkey C editing, building, running in
the Connect IQ simulator, and debugging.

## The idea

The plugin carries no model of the Monkey C language. Everything that has to understand the
language is a Java program Garmin already ships in the Connect IQ SDK, and the plugin's job is to
find those programs and speak their protocols:

| Concern | What runs it | How the plugin talks to it |
|---|---|---|
| Code intelligence | `bin/LanguageServer.jar` | LSP over stdio |
| Building | `bin/monkeybrains.jar` | command line, output parsed into Problems |
| Running | `bin/ConnectIQ.app` + `MonkeyDoDeux` | command line |
| Debugging | `bin/LanguageServer.jar` | DAP over stdio |

Nothing from Garmin is redistributed: the plugin locates the SDK the user installed with the SDK
Manager and launches its jars.

That is the whole design argument. Monkey C changes with every SDK release — new types, new
annotations, new `.mss` properties — and a parser maintained in this plugin would be behind from
the day it shipped. The language server *is* the compiler front end, so it is never behind.

## Status

Work in progress. The language layer is in place; building, running and debugging are next.

## Requirements

* The Connect IQ SDK, installed with Garmin's SDK Manager (8.1.0 or newer for code intelligence —
  that is when `LanguageServer.jar` first shipped)
* IntelliJ IDEA 2025.2 or newer
* [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij), which the IDE installs along with
  this plugin

## Build

```bash
./gradlew build          # compile and run the unit tests
./gradlew runIde         # sandbox IDE with the plugin installed
./gradlew verifyPlugin   # plugin compatibility check
./gradlew buildPlugin    # distributable zip
```

Tests that drive the real SDK are opt-in, so a machine without Connect IQ still goes green:

```bash
MONKEYC_LIVE_TESTS=1 ./gradlew test
```

## Layout

```
sdk/       finding the SDK, reading the device catalogue, locating a JVM — no IDE API
project/   settings, project layout, manifest and jungle conventions
lang/      lexers, file types, colouring, commenting, bracket matching
lsp/       the language server client
build/     the compiler, and its output turned into Problems
run/       run configurations: build, simulator, tests, export
dap/       the debug adapter client
```
