# Notes on the Connect IQ SDK

What the SDK's own tools do that a client has to work around.

Written down because none of it is documented anywhere and every one of them cost a day to
find. Anyone building a Connect IQ tool will meet the same things.

All of it was confirmed against SDK 9.1.0 and still holds on 9.2.0, and most of it has a live
test that will go red the day Garmin fixes it:

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
* The simulator's shell carries one client at a time. Whoever connects second gets everything and
  whoever was there first is told `shellDisconnected` and then hears nothing more — including the
  end of the app it was watching, so a run displaced by a debugger would simply never finish. The
  plugin holds one connection for the whole IDE (`run/session/`) and hands out channels off it, and
  ends a run on purpose before starting a debugger rather than letting it be cut off.
* Unit tests cannot be debugged by anything. The adapter's `entry` reads
  `if (!mRunTests && isForegroundApp) mClient.initialized();`, and a DAP client registers
  breakpoints only after `initialized` — so a test run has no moment at which a breakpoint can be
  set, and `stopAtLaunch` is ignored there too. A test configuration therefore does not offer
  Debug, and a live test asserts the absence of that event so the refusal can be lifted the day it
  arrives.
