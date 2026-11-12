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
git tag -a fdroid-v0.24.0 -m "F-Droid candidate 0.24.0 (39)"
git push origin fdroid-v0.24.0
```

Use an `fdroid-vX.Y.Z` tag only for versions that are mature enough for F-Droid.
The tag must point to a commit where `neopen/build.gradle.kts` already has the
matching `versionName` and a new `versionCode`.

## fdroiddata metadata template

The root `.fdroid.yml` is the build recipe. Its current candidate is
**0.24.0 (version code 39)**, selected by `fdroid-v0.24.0`; automated updates
follow only explicit `fdroid-v*` tags. This does not submit the app to F-Droid or create an F-Droid release.
The repository must be publicly accessible before any F-Droid submission;
publishing these files does not change its visibility.

`subdir` selects the app module and `output` is relative to it, following the
[F-Droid metadata reference](https://f-droid.org/en/docs/Build_Metadata_Reference/).
`-Pfdroid` excludes `:live` and ignores all release-signing environment variables,
so the result is unsigned even on a machine configured for Play uploads.
The recipe removes the retired `companion` module, `live` and the shared debug
keystore directory with `rm` before scanning and building the source.

The ready-to-submit recipe is
[`docs/fdroid/net.micapum.neopen.yml`](fdroid/net.micapum.neopen.yml), identical to
[`.fdroid.yml`](../.fdroid.yml). Copy it to
`fdroiddata/metadata/net.micapum.neopen.yml` when proposing inclusion. Descriptions,
screenshots, icon and version changelogs are in `fastlane/metadata/android`.

Submit the metadata through a merge request to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata), or request packaging through
[Requests for Packaging](https://gitlab.com/fdroid/rfp). Submission does not mean
acceptance: maintainers review licensing, dependencies, metadata and the build.
See the [submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/).

The F-Droid APK is built from source and signed by F-Droid, independently of the
GitHub debug APK. Its signing key can differ, so switching distribution channels
may require uninstalling the existing package; export notebooks first.

## Dependencies and network features

The build uses JDK 17 and Gradle 8.9. Runtime dependencies come from Google Maven
and Maven Central: AndroidX/Compose/Room/WorkManager/Glance, Kotlin coroutines,
Retrofit/Moshi/OkHttp, Coil, Commons Net and JSch. The F-Droid variant contains no
ML Kit, Firebase SDK, Google Play Services SDK, advertising SDK or analytics SDK.

The recipe discloses the following network features in `MaintainerNotes`:

- `core/.../pen/PaperLookup.kt` requests notebook geometry XML from NeoLAB's public
  Firebase Storage bucket through ordinary HTTPS/OkHttp. It sends paper
  identifiers, not handwritten strokes. Capture works with fallback geometry
  when that service is unavailable.
- OCR, LanguageTool, server synchronization and storage connectors are optional
  and use endpoints configured by the user. Local capture and notebooks require
  no account or server.

These disclosures let the F-Droid reviewers decide the appropriate AntiFeatures.
A compatible Neo smartpen and Ncode paper are needed to capture handwriting; this
is an independent implementation, not an official NeoLAB application.
