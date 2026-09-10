## What this changes, and why

<!-- The why is the part that is hard to recover later. -->

## Checks

- [ ] `./gradlew build`
- [ ] `./gradlew runSelfCheck`
- [ ] `./gradlew verifyPlugin -PverifySince` — the promise `sinceBuild` makes to users on an older
      IDE; nothing else can tell you it still holds
- [ ] `./gradlew test -PliveTests`, if you have an SDK installed and touched anything that drives it
- [ ] No Garmin SDK content added to the repository — see CONTRIBUTING.md
