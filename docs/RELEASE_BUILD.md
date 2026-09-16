# Building a signed release APK via GitHub Actions

`.github/workflows/build.yml` always produces a **debug APK** — no setup
required. It's auto-signed with the standard Android debug key, so it
installs straight on a device, but Android treats debug and release builds
as different apps for update purposes, and Play Store doesn't accept debug
builds.

To also get a **signed release APK** from every push, add four repository
secrets once:

## 1. Generate an upload keystore (one time, on your own machine)

```bash
keytool -genkeypair -v \
  -keystore ash-forge-upload.jks \
  -alias ash-forge-upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

You'll be asked for a keystore password, a key password, and some identity
fields (name, org, etc. — anything is fine). **Keep this file and both
passwords somewhere safe** — if you lose them, you can never publish an
update to the same package under Google Play's signing rules, and anyone
with the file can sign an app claiming to be a legitimate update.

## 2. Add the secrets to your GitHub repo

Settings → Secrets and variables → Actions → New repository secret. Add
all four:

| Secret name | Value |
|---|---|
| `MH_UPLOAD_STORE_BASE64` | `base64 -w0 ash-forge-upload.jks` output |
| `MH_UPLOAD_STORE_PASSWORD` | the keystore password |
| `MH_UPLOAD_KEY_ALIAS` | `ash-forge-upload` (or whatever alias you used) |
| `MH_UPLOAD_KEY_PASSWORD` | the key password |

## 3. Push

The next workflow run will decode the keystore, build `assembleRelease`,
and upload it as the `AshForge-release` artifact alongside the debug build.

## Local release builds

The same four values work as environment variables for a local build:

```bash
export MH_UPLOAD_STORE_FILE=/path/to/ash-forge-upload.jks
export MH_UPLOAD_STORE_PASSWORD=...
export MH_UPLOAD_KEY_ALIAS=ash-forge-upload
export MH_UPLOAD_KEY_PASSWORD=...
./gradlew assembleRelease
```

## Before your first build (either path)

The app downloads its Linux runtime from a GitHub Release at
`https://github.com/ashimcodes/AshForge/releases/download/runtime-2026.09.4/...`
(configurable via `-PrenameBaseUrl`). That release doesn't exist yet under
your account — see the note in `app/build.gradle.kts` and
`scripts/runtime-bundles/README.md`. Until you publish it, the app will
install and open, but runtime setup will fail to download on first launch.
