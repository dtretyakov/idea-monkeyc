# Monkey C for IntelliJ IDEA

Garmin Connect IQ development in IntelliJ IDEA: writing Monkey C, building, running in the Connect
IQ simulator, and debugging — for watch faces, watch apps, data fields, widgets and barrels.

![Trail Pace stopped on a breakpoint in IntelliJ IDEA: the frame in onUpdate, its locals with real values, and the same values inline in the editor](docs/images/marketplace/02-debugger.png)

## Getting started

Five minutes, assuming you have IntelliJ IDEA.

1. **Install the Connect IQ SDK** with [Garmin's SDK Manager](https://developer.garmin.com/connect-iq/sdk/),
   and download at least one device with it. The plugin finds it on its own.
2. **Install [this plugin](https://plugins.jetbrains.com/plugin/34203-monkey-c)** from
   *Settings | Plugins | Marketplace* — search for Monkey C.
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

| | |
|---|---|
| **Write code** | Completion, diagnostics, go to definition and rename across the whole Toybox API. |
| **Look up API** | Garmin's own documentation for the symbol under the caret, offline. |
| **Run** | Builds the app and starts it in the Connect IQ simulator. |
| **Debug** | Breakpoints, stepping, variables and expression evaluation in the simulator. |
| **Test** | Runs one `(:test)` function, one file or the whole project, results as a tree. |
| **Target a watch** | The device chip beside Run that every build, run and debug follows. |
| **Watch memory** | Every build reports what it took of that watch's memory and what is left. |
| **Edit manifest** | Devices, permissions and languages as a form instead of hand-written XML. |
| **Sideload** | Builds for the watch on the desk and installs it over USB — see [Installing on a watch](#installing-on-a-watch). |
| **Publish** | Exports a signed `.iq`, naming the devices and languages it will drop before it starts. |
| **Start a project** | A new project from the SDK's own templates, and a developer key without openssl. |

## Requirements

* IntelliJ IDEA 2026.1 or newer, or Android Studio 2026.1 (Quail) or newer. It loads in the other
  IntelliJ-based IDEs too; where there is no Build menu, the build commands are in Search
  Everywhere.
* Windows, macOS or Linux — wherever the Connect IQ SDK runs.
* The Connect IQ SDK, installed with Garmin's SDK Manager. Code intelligence needs SDK 8.1.0 or
  newer; building, running and debugging work with older ones.
* [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij), installed alongside this plugin
* To install on a watch over USB, see below.

### Installing on a watch

Connect the watch with its USB cable, pick it in the chip beside Run, and press Run. The plugin
builds for that watch and copies the app to it; unplug the cable and open the app on the watch.
The `.prg` disappears from `GARMIN/APPS` once the watch is unplugged — that is the install
working.

A current watch connects over MTP, not as a drive, and what that takes depends on the system:

| | Needs |
|---|---|
| **macOS** | [mtp-rs](https://github.com/vdavid/mtp-rs). macOS has no MTP of its own: Finder never shows the watch. |
| **Windows** | [mtp-rs](https://github.com/vdavid/mtp-rs). It uses Windows' own MTP support, so no driver is installed. |
| **Linux, GNOME and most desktops** | Nothing. The desktop mounts the watch, and the plugin copies through that mount with `gio`. |
| **Linux, KDE or no desktop** | [mtp-rs](https://github.com/vdavid/mtp-rs), and permission to open the watch without root (a udev rule; most desktop distributions ship one). |

Where mtp-rs is needed and missing, the plugin says so in the watch list beside Run, in
*Settings | Languages & Frameworks | Monkey C*, and after a build for a watch — each with an
**Install mtp-rs** button. The button downloads mtp-rs 0.9.1 for your system from its GitHub
release, checks it against a checksum built into the plugin, and keeps it in JetBrains' shared
data directory; nothing else is needed. An older mtp-rs is offered the same button as an update.
If you would rather install it yourself (`brew install vdavid/tap/mtp-rs`, or the
[other ways](https://github.com/vdavid/mtp-rs/tree/main/crates/mtp-rs-cli#install)), the plugin
finds it; a path set in the settings always wins.

Older watches that connect as a drive need none of this on any system.

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
