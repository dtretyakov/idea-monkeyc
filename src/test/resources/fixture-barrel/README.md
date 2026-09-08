A barrel, which is a Connect IQ library rather than an app.

It exists so the barrel branches of the build can be exercised for real. They are a different
compiler entry point with different rules — `barreltest` refuses the `_sim` device suffix that
`monkeyc` requires, which is the sort of thing only a live build finds.
