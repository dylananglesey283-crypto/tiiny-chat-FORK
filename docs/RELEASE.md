# Release signing and publishing

Release APKs are signed with a private key that only the maintainer holds. Android accepts an
update only if it is signed with the same key, so **the key must never be lost**. It is never
committed to the repository and never shared in chats or tickets.

## 1. Create the key (once, on your own computer)

Requires a JDK (`keytool`). `keytool` asks for the password interactively; do not put it on the
command line.

```sh
keytool -genkeypair -v \
  -keystore tiiny-chat-release.jks \
  -alias tiiny-chat \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Tiiny Chat"
```

The keystore is PKCS12; key and keystore share one password.

## 2. Back it up

Keep `tiiny-chat-release.jks` and its password in at least two places (e.g. password manager
and an offline backup). Without them no further updates of the installed app are possible; users
would have to uninstall and reinstall.

## 3. Store it as GitHub secrets

Run in the folder with the keystore (GitHub CLI, logged in). The second command asks for the
password without echoing it.

```sh
base64 -w0 tiiny-chat-release.jks | gh secret set KEYSTORE_BASE64 -R Jenziner/tiiny-chat
gh secret set KEYSTORE_PASSWORD -R Jenziner/tiiny-chat
```

Alternatively: repository → Settings → Secrets and variables → Actions → New repository secret.
Secrets are encrypted by GitHub and are not visible in logs or to collaborators.

## 4. Publish a release

1. Set `versionCode` / `versionName` in `app/build.gradle.kts` and add a section
   `## <version> – <date>` to `CHANGELOG.md`.
2. Commit, then tag and push:
   ```sh
   git tag v1.0.0
   git push origin v1.0.0
   ```
3. The workflow `.github/workflows/release.yml` runs the tests, builds the signed APK, verifies
   the signature and creates the GitHub release with `tiiny-chat-<version>.apk`, its `.sha256`
   file, the changelog section and the SHA-256 fingerprint of the signing certificate.

## Building a signed release locally (alternative)

```sh
export TIINY_KEYSTORE_FILE=/path/to/tiiny-chat-release.jks
read -rs TIINY_KEYSTORE_PASSWORD && export TIINY_KEYSTORE_PASSWORD
./gradlew :core:test :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Verifying a downloaded APK

```sh
sha256sum -c tiiny-chat-1.0.0.apk.sha256
apksigner verify --print-certs tiiny-chat-1.0.0.apk   # compare the SHA-256 with the release notes
```
