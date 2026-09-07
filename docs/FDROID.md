# F-Droid publishing

The F-Droid build should publish only `:neopen`, not `:live`.

`:live` depends on ML Kit for digital ink recognition, so it must stay out of the
main F-Droid build. Build F-Droid releases with:

```bash
./gradlew -Pfdroid :neopen:assembleRelease
```

## Release policy

Normal GitHub releases keep using tags such as `v0.21.6`.

F-Droid should only follow explicit tags:

```bash
git tag fdroid-v0.22.0
git push origin fdroid-v0.22.0
```

Use an `fdroid-vX.Y.Z` tag only for versions that are mature enough for F-Droid.
The tag must point to a commit where `neopen/build.gradle.kts` already has the
matching `versionName` and a new `versionCode`.

## fdroiddata metadata template

The root `.fdroid.yml` is the build recipe. Its initial candidate points to the
GitHub tag `v0.23.4`; automated updates still follow only explicit `fdroid-v*`
tags. This does not submit the app to F-Droid or create an F-Droid release.
The repository must be publicly accessible before any F-Droid submission;
publishing these files does not change its visibility.

`subdir` selects the app module and `output` is relative to it, following the
[F-Droid metadata reference](https://f-droid.org/en/docs/Build_Metadata_Reference/).
`-Pfdroid` excludes `:live` and ignores all release-signing environment variables,
so the result is unsigned even on a machine configured for Play uploads.

Copy the template below to `fdroiddata/metadata/net.micapum.neopen.yml` and review
the candidate when submitting the app.

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
  - versionName: 0.23.4
    versionCode: 37
    commit: v0.23.4
    subdir: neopen
    gradle:
      - yes
    gradleprops:
      - fdroid
    scandelete:
      - companion
      - live
      - neopen/signing
    output: build/outputs/apk/release/neopen-release-unsigned.apk

AutoUpdateMode: Version
UpdateCheckMode: Tags ^fdroid-v[0-9]+\.[0-9]+\.[0-9]+$
UpdateCheckData: neopen/build.gradle.kts|versionCode = (\d+)|.|versionName = "([^"]+)"
CurrentVersion: 0.23.4
CurrentVersionCode: 37
```
