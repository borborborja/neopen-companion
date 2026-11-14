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
git tag -a fdroid-v0.26.1 -m "F-Droid candidate 0.26.1 (42)"
git push origin fdroid-v0.26.1
```

Use an `fdroid-vX.Y.Z` tag only for versions that are mature enough for F-Droid.
The tag must point to a commit where `neopen/build.gradle.kts` already has the
matching `versionName` and a new `versionCode`.

## fdroiddata metadata template

The root `.fdroid.yml` is the build recipe. Its current candidate is
**0.26.1 (version code 42)**, selected by `fdroid-v0.26.1`; automated updates
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
For the fdroiddata submission, replace the build tag with its full commit SHA and
omit `Summary` and `Description`, which F-Droid imports from Fastlane.

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
- OCR, LanguageTool, server synchronization and storage connectors are optional.
  LanguageTool is disabled by default and offers `api.languagetool.org` as its
  default endpoint; users can configure a self-hosted endpoint instead. Other
  endpoints are configured by the user. Local capture and notebooks require no
  account or server.

The recipe declares `NonFreeNet` for the automatic NeoLAB geometry lookup.
These disclosures let the F-Droid reviewers assess the network features.
A compatible Neo smartpen and Ncode paper are needed to capture handwriting; this
is an independent implementation, not an official NeoLAB application.

## Verification and automated RFP reports

The RFP bot can inspect all applications in the repository without `-Pfdroid`.
Its report for `versionCode=18` describes `net.micapum.neopen.live`, not the
F-Droid candidate `net.micapum.neopen`. A source scan of the unprepared repository
therefore finds ML Kit. Keep the recipe's `rm` exclusions: F-Droid removes those
paths before scanning and building the candidate. Do not suppress the finding
with `scanignore` or remove ML Kit from the separate Live application.

The Gradle wrapper pins the official SHA-256 for `gradle-8.9-bin.zip`. Test this
using an empty Gradle user directory, since an already downloaded distribution
does not exercise download verification:

```bash
GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --version
./gradlew -Pfdroid projects
./gradlew -Pfdroid :neopen:dependencies --configuration releaseRuntimeClasspath
./gradlew :neopen:assembleDebug :live:compileDebugKotlin
```

The F-Droid project list must exclude `:live`, and the release runtime dependency
graph must contain no ML Kit, Firebase SDK or Google Play Services SDK. In a
configured fdroiddata checkout, validate the submitted metadata and build the
exact pinned commit with both source and APK scanning:

```bash
fdroid lint -f net.micapum.neopen
fdroid build --server --test --scan-binary --refresh-scanner net.micapum.neopen:42
```

The resulting package must be `net.micapum.neopen`, version `0.26.1` / code `42`,
and unsigned so F-Droid can sign it. Preserve existing `fdroid-v*` tags; future
candidates must use new tags rather than moving previously submitted releases.
