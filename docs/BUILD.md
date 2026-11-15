# Build

## Requirements

| Tool | Version (checked) |
|---|---|
| JDK | 21 (Temurin 21.0.13); at least 17 |
| Gradle | via wrapper, 9.9.1 |
| Android SDK | platform `android-37.0`, build tools 37.0.0 (AGP may also fetch 36.0.0), platform tools |
| Android Gradle Plugin | 9.5.1 |
| Kotlin | 2.4.21 |

Create `local.properties` (not in the repository) with `sdk.dir=/path/to/android-sdk`, or set
`ANDROID_HOME`.

## Commands

```sh
./gradlew :core:test            # core tests (JVM, no Android device needed)
./gradlew :app:assembleDebug    # debug APK
./gradlew :app:lintRelease      # Android lint
./gradlew :app:assembleRelease  # release APK (R8), signed only if the signing variables are set
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk` – package `io.github.jenziner.nisaba.debug`,
  debug signature, not minified (about 32 MB).
- `app/build/outputs/apk/release/app-release.apk` (signed) or `app-release-unsigned.apk` –
  package `io.github.jenziner.nisaba`, minified with R8 and resource shrinking (about 3 MB).
- Test report: `core/build/reports/tests/test/index.html`.

## Release signing

The signing key is read only from environment variables; nothing secret is stored in the
repository:

| Variable | Meaning |
|---|---|
| `TIINY_KEYSTORE_FILE` | path to the keystore (`.jks`/PKCS12) |
| `TIINY_KEYSTORE_PASSWORD` | keystore password |
| `TIINY_KEY_ALIAS` | optional, default `tiiny-chat` |
| `TIINY_KEY_PASSWORD` | optional, default = keystore password |

How to create the key and how the GitHub release workflow uses it: [RELEASE.md](RELEASE.md).

## CI

- `.github/workflows/ci.yml`: on every push to `main` and every pull request: core tests, debug
  build, lint. No secrets.
- `.github/workflows/release.yml`: on a tag `v*`: tests, signed release build, signature check,
  GitHub release with APK, SHA-256 file and the changelog section.

## Notes

- Test names deliberately contain no umlauts: without a UTF-8 locale, the Kotlin compiler would
  fail on the class file names.
- R8 rules: `app/proguard-rules.pro`. kotlinx.serialization, OkHttp, coroutines and Compose
  bring their own consumer rules.
- The app has no Google, Firebase or analytics libraries; the dependency list is in
  `gradle/libs.versions.toml`. Dependency metadata for Google Play is not embedded.
