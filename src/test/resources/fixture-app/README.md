A minimal Connect IQ app, used by the tests that need a real project: one the language server can
index, the compiler can build and the debugger can stop inside.

It is deliberately dull. The counter exists so `onUpdate` has a statement worth a breakpoint, and
`FixtureView` so there is a second class for go-to-definition to find.
