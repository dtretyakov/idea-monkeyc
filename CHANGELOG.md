# Changelog

## Unreleased

First release.

### Language

- Monkey C, jungle and MSS file types, with syntax highlighting, commenting, brace matching,
  quotes that close themselves and a colour settings page.
- Code intelligence from the language server Garmin ships in the Connect IQ SDK: completion,
  diagnostics, go to definition, hover, rename, symbols, folding, and type and call hierarchies.
  No language model of the plugin's own, so it stays in step with the installed SDK.
- Parameter info, the file structure popup, breadcrumbs, and moving the caret to the start or end
  of a block.
- The Connect IQ SDK and the project's barrels under External Libraries, searchable and openable.
- Go to definition, Go to Symbol and Go to Class reach the whole Toybox API, which the SDK's own
  language server declines to resolve.
- Shift+F1 opens Garmin's page for the symbol under the caret, from the installed SDK, offline.
- Source, resource and output directories marked in the project tree; file-type icons.

### Building and running

- Run configurations for the app, its unit tests, a build that runs nothing, an export to `.iq`,
  a barrel, and a barrel's tests.
- A build for the watch rather than the simulator, producing a `.prg` to copy to `GARMIN/APPS`.
- Builds are skipped when the output already matches the sources and the compiler flags.
- The target device is chosen beside the Run button and defaults to the first product the
  manifest declares.
- Compiler diagnostics in the Build tool window, with clickable locations.

### Tests

- Unit tests run in a test tree, with a green arrow beside every `(:test)` function, the tests of
  one file or directory from its context menu, and every test in the project from its root.
- Tests cannot be debugged: the SDK's adapter never enters configuration mode for a test run, so
  no breakpoint is ever registered. Debug is not offered for them.

### Debugging

- Breakpoints, stepping, variables and expression evaluation through the debug adapter in the SDK.
- A complication pair: a second Connect IQ project run alongside the first.

### Around it

- A new project wizard built on the SDK's own templates.
- Developer key generation, without needing openssl.
- A form editor for `manifest.xml`, with products, permissions and languages.
- Stopping and restarting the Connect IQ simulator.
