# Monkey C for IntelliJ IDEA

Garmin Connect IQ development in IntelliJ IDEA: writing Monkey C, building, running in the Connect
IQ simulator, and debugging — for watch faces, watch apps, data fields, widgets and barrels.

![Trail Pace stopped on a breakpoint in IntelliJ IDEA: the frame in onUpdate, its locals with real values, and the same values inline in the editor](docs/images/marketplace/02-debugger.png)

## Getting started

Five minutes, assuming you have IntelliJ IDEA.

1. **Install the Connect IQ SDK** with [Garmin's SDK Manager](https://developer.garmin.com/connect-iq/sdk/),
   and download at least one device with it. The plugin finds the SDK itself; nothing from Garmin is
   bundled here.
2. **Install this plugin.** The IDE installs [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij)
   alongside it, which is what carries code intelligence.
3. **New Project | Connect IQ**, or open a folder that already has a `monkey.jungle` in it.
4. **Generate a developer key** in *Settings | Languages & Frameworks | Monkey C*. It signs every
   build, and the plugin writes it without needing openssl.
5. **Run.** The watch is chosen in the chip beside the Run button.

If a step is missing, the settings page says so on the control that fixes it: which SDK is in use
and how many devices came with it, which developer key is signing, whether this SDK even ships the
language server.

## What it does

**Writing.** Completion over the whole Toybox API, diagnostics, go to definition, hover
documentation, parameter info, rename, find usages, symbols, folding, and type and call hierarchies
— all from the language server Garmin ships in the SDK, so none of it falls behind the SDK you have
installed. Syntax highlighting, commenting, bracket matching, self-closing quotes and Enter-and-
indent for `.mc` (and `.mcgen`, and the `.mb` inside a compiled barrel), `.jungle` and `.mss`.
Shift+F1 opens Garmin's own page for the symbol under the caret, offline.

**Reading the API.** The SDK's `bin/api.mir` is twenty-seven thousand lines and gets a viewer of its
own: a structure view to browse it as a tree, folding, and its `//!` comments rendered in place the
way the IDE renders javadoc. A `.barrel` opens as what it is — a zip of Monkey C — so a barrel in
External Libraries is source you can read rather than one opaque binary file.

**The manifest.** A form beside the XML, for the parts that are lists of identifiers nobody
remembers. Devices are a sortable table — screen, colour depth, panel, input and the memory this
kind of app gets on each — so the device that will actually constrain the app can be found by
sorting rather than by looking each one up on Garmin's website. Under it, what the selection commits
you to: how many resource families have to be drawn, the smallest memory budget the code now has to
fit, how many devices have no touchscreen, and the poorest colour depth the artwork has to survive.

**Building and running.** Run configurations for the app, its unit tests, a build that runs nothing,
an export to `.iq`, a barrel and a barrel's tests, and a green arrow in the gutter beside the class
the app starts from. Every build reports what it took of the target device's memory and what is
left — a figure that ranges from 64 KB to 2304 KB across the devices Garmin ships, and is the
constraint that shapes Connect IQ development. Compiler errors land in the Build tool window as
something to click.

**On the watch.** A build for the hardware rather than the simulator, with an offer to install the
`.prg` over USB — over MTP for current devices, which appear under no volume at all, and as a file
copy for older ones that mount as a disk.

**Publishing.** An export says what it is about to leave out before it spends minutes doing it:
devices that are declared but not downloaded, devices below the manifest's minimum API level, and
languages the declared devices do not support — the last of which is invisible everywhere else,
though the data ships with every device. It also remembers the developer key and the application id
it went out as, and refuses a later export that would change either: a different key cannot update
a store listing at all, and a different id updates somebody else's.

**Debugging.** Breakpoints, stepping, variables and expression evaluation, through the debug adapter
in the SDK — which is not, as is widely believed, only inside the official VS Code extension.

**Tests.** A test tree, a green arrow beside every `(:test)` function, the tests of one file or
directory from its context menu, and every test in the project from its root.

**Settings.** Type-check level, optimization level, debug log level, compiler warnings and any extra
compiler flags, per project — the things `monkeyc` takes on the command line, in the place you would
look for them. The SDK and the `java` that runs it can be pinned too, per project and per machine.

## Requirements

* IntelliJ IDEA 2026.1 or newer. The plugin uses only platform API, so it loads in the other
  IntelliJ-based IDEs and in Android Studio as well — but IDEA is what it is developed and tested
  against, and in an IDE with no Build menu those three build commands live only in Search
  Everywhere.
* Windows, macOS or Linux, wherever the Connect IQ SDK runs. Development happens on macOS and CI
  runs on Linux without an SDK, so the Windows and Linux paths get less exercise than the macOS
  ones; a bug report from either is welcome.
* The Connect IQ SDK, installed with Garmin's SDK Manager. Code intelligence needs SDK 8.1.0 or
  newer — that is when `LanguageServer.jar` first shipped; building and running work with anything
  older.
* [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij), which the IDE installs alongside this
  plugin

## More

* [Architecture](docs/architecture.md) — why the plugin implements no Monkey C parser of its own,
  and what it runs instead
* [Notes on the Connect IQ SDK](docs/sdk-notes.md) — what its own tools do that a client has to work
  around, none of it documented anywhere else
* [Developing](docs/developing.md) — building the plugin, and the two kinds of test
* [Contributing](CONTRIBUTING.md) — what a useful bug report has in it, and the checks a pull
  request should pass
* [Security](SECURITY.md) — how to report a vulnerability, and what the plugin touches at all
* [Publishing](PUBLISHING.md) — releases, signing, the Marketplace listing
* [Changelog](CHANGELOG.md)

## Licence

Apache License 2.0; see [LICENSE](LICENSE).

## Trademarks

Garmin, Connect IQ and Monkey C are trademarks of Garmin Ltd. or its subsidiaries. They are used
here only to name the language this plugin supports and the devices it builds for, which is a
statement of compatibility and nothing more.

This is an independent, open-source project. It is not affiliated with, endorsed by, or supported by
Garmin, and it redistributes nothing of Garmin's — no SDK, no templates, no device data, no API
documentation. It finds the SDK the user installed and runs the programs already in it.
