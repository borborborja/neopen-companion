# F-Droid publishing

The F-Droid build should publish only `:neopen`, not `:live`.

`:live` depends on ML Kit for digital ink recognition, so it must stay out of the
main F-Droid build. Build F-Droid releases with:

```bash
./gradlew -Pfdroid :neopen:assembleRelease
```

## Release policy

Normal GitHub releases keep using tags such as `v0.21.5`.

F-Droid should only follow explicit tags:

```bash
git tag fdroid-v0.22.0
git push origin fdroid-v0.22.0
```

Use an `fdroid-vX.Y.Z` tag only for versions that are mature enough for F-Droid.
The tag must point to a commit where `neopen/build.gradle.kts` already has the
matching `versionName` and a new `versionCode`.

## fdroiddata metadata template

Copy this file to `fdroiddata/metadata/net.micapum.neopen.yml` and update the
first `Builds` entry when submitting the app.

```yaml
Categories:
  - Writing
License: GPL-3.0-only
AuthorName: neopen contributors
SourceCode: https://github.com/borborborja/neopen-companion
IssueTracker: https://github.com/borborborja/neopen-companion/issues
Changelog: https://github.com/borborborja/neopen-companion/releases

AutoName: neopen
Summary: Capture Neo smartpen handwriting locally or on a neopen server
Description: |-
  neopen is an Android companion for Neo smartpens.

  It captures handwriting over Bluetooth LE, imports the pen's offline memory,
  and stores pages either locally on the phone or on a self-hosted neopen server.

  This is an independent community project and is not affiliated with NeoLAB
  Convergence Inc.

RepoType: git
Repo: https://github.com/borborborja/neopen-companion.git

Builds:
  - versionName: 0.21.5
    versionCode: 29
    commit: fdroid-v0.21.5
    gradle:
      - yes
    gradleprops:
      - fdroid
    scandelete:
      - companion
      - live
      - neopen/signing
    output: neopen/build/outputs/apk/release/neopen-release-unsigned.apk

AutoUpdateMode: Version
UpdateCheckMode: Tags ^fdroid-v[0-9]+\.[0-9]+\.[0-9]+$
UpdateCheckData: neopen/build.gradle.kts|versionCode = (\d+)|.|versionName = "([^"]+)"
CurrentVersion: 0.21.5
CurrentVersionCode: 29
```
