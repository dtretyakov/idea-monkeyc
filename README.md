# Monkey C for IntelliJ IDEA

Garmin Connect IQ development in IntelliJ IDEA: writing Monkey C, building, running in the Connect
IQ simulator, and debugging — for watch faces, watch apps, data fields, widgets and barrels.

![Trail Pace stopped on a breakpoint in IntelliJ IDEA: the frame in onUpdate, its locals with real values, and the same values inline in the editor](docs/images/marketplace/02-debugger.png)

## Getting started

Five minutes, assuming you have IntelliJ IDEA.

1. **Install the Connect IQ SDK** with [Garmin's SDK Manager](https://developer.garmin.com/connect-iq/sdk/),
   and download at least one device with it. The plugin finds it on its own.
2. **Install this plugin** from *Settings | Plugins*.
   [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) comes with it.
3. **New Project | Connect IQ**, or open a folder that already has a `monkey.jungle` in it.
4. **Generate a developer key** in *Settings | Languages & Frameworks | Monkey C*. It signs every
   build, and you do not need openssl to make one.
5. **Run.** The watch is chosen in the chip beside the Run button.

Miss a step and the settings page says so, on the control that fixes it.

**Installing from disk instead.** Every tagged release carries a signed zip on the
[Releases page](https://github.com/dtretyakov/idea-monkeyc/releases). Install
[LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) from the Marketplace first — a disk
install will not fetch it — then *Settings | Plugins | ⚙ | Install Plugin from Disk*. Signed, so it
installs without the dialog that asks whether to trust it. Pre-release versions are marked as such.

## What it does

**Writing.** Completion over the whole Toybox API, diagnostics as you type, go to definition, hover
documentation, parameter info, rename, find usages, symbols, folding, and type and call hierarchies.
Syntax highlighting, commenting, bracket matching, self-closing quotes and Enter-and-indent for
`.mc`, `.jungle` and `.mss`. Shift+F1 opens Garmin's own page for the symbol under the caret,
offline.

**Reading the API.** The whole Toybox API is browsable as a tree, with Garmin's documentation
rendered where you read it rather than left as comment markers. Barrels you depend on open as
source, not as one binary file.

**The manifest.** A form beside the XML, for the parts that are lists of identifiers nobody
remembers. Devices are a sortable table — screen, colour depth, panel, input and the memory this
kind of app gets on each — so the device that will actually constrain the app is one sort away.
Under it, what the selection commits you to: how many resource families have to be drawn, the
smallest memory budget the code now has to fit, how many devices have no touchscreen, and the
poorest colour depth the artwork has to survive.

**Building and running.** Run configurations for the app, its unit tests, an export to `.iq`, a
barrel and a barrel's tests, and a green arrow in the gutter beside the class the app starts from.
Every build reports what it took of the target device's memory and what is left — a figure that
ranges from 64 KB to 2304 KB across the devices Garmin ships, and the constraint that shapes Connect
IQ development. Compiler errors land in the Build tool window as something to click.

**On the watch.** A build for the hardware rather than the simulator, and an offer to install the
`.prg` over USB when a watch is attached.

**Publishing.** An export says what it is about to leave out before it spends minutes doing it:
devices that are declared but not downloaded, devices below the manifest's minimum API level, and
languages the declared devices cannot render. It also remembers the developer key and the
application id the app went out as, and refuses a later export that would change either — a
different key cannot update a store listing at all, and a different id updates somebody else's.

**Debugging.** Breakpoints, stepping, variables and expression evaluation, in the simulator.

**Tests.** A test tree, a green arrow beside every `(:test)` function, the tests of one file or
directory from its context menu, and every test in the project from its root.

**Settings.** Type-check level, optimization level, debug log level, compiler warnings and any extra
compiler flags, per project. The SDK and the `java` that runs it can be pinned as well.

## Requirements

* IntelliJ IDEA 2026.1 or newer. It loads in the other IntelliJ-based IDEs and in Android Studio
  too; where there is no Build menu, the build commands are in Search Everywhere.
* Windows, macOS or Linux — wherever the Connect IQ SDK runs.
* The Connect IQ SDK, installed with Garmin's SDK Manager. Code intelligence needs SDK 8.1.0 or
  newer; building, running and debugging work with older ones.
* [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij), installed alongside this plugin

## More

* [Architecture](docs/architecture.md) — how it is put together, and why
* [Notes on the Connect IQ SDK](docs/sdk-notes.md) — what its tools do that a client must work around
* [Developing](docs/developing.md) — building the plugin, and its two kinds of test
* [Contributing](CONTRIBUTING.md) — bug reports, and the checks a pull request should pass
* [Security](SECURITY.md) — reporting a vulnerability, and what the plugin touches
* [Publishing](PUBLISHING.md) — releases, signing, the Marketplace listing
* [Changelog](CHANGELOG.md)

## Licence

Apache License 2.0; see [LICENSE](LICENSE).

## Trademarks

Garmin, Connect IQ and Monkey C are trademarks of Garmin Ltd. or its subsidiaries, used here only
to name the language this plugin supports and the devices it builds for.

This is an independent, open-source project, not affiliated with or endorsed by Garmin. It
redistributes nothing of Garmin's: it finds the SDK you installed and runs the programs in it.
