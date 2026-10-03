# Release APK workflow

`.github/workflows/release.yml` builds a signed release APK on demand. Run it from **Actions → Release APK → Run workflow**. Leaving the tag input empty uploads the APK as a workflow artifact; entering a tag name (e.g. `v0.2.11`) also creates a GitHub Release with the APK and `SHA256SUMS.txt` attached.

## Required secrets

Set these in **Settings → Secrets and variables → Actions**. The workflow fails early with a clear error if any is missing.

| Secret | Contents |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` of your signing keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore store password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |
| `MFI_IDENTITY_PK8_BASE64` | `base64 -w0 offline-mfi/identity.pk8` |
| `MFI_CERTIFICATE_P7B_BASE64` | `base64 -w0 offline-mfi/certificate.p7b` |

The offline-mfi pair is the accessory identity: extract it from the published release APK (`unzip DiPlay-*.apk "assets/offline-mfi/*"`). It ships inside release APKs by design; keep the files out of git (`check_public_tree.py` rejects them).

> Signing note: an APK signed with a different certificate cannot update an existing installation in place — users must uninstall first. To keep seamless updates with an already-shipped APK, reuse the same keystore that signed it.

## Local equivalent

```sh
export DIPLAY_AUTH_ASSETS_DIR=/path/to/auth-assets   # contains offline-mfi/{identity.pk8,certificate.p7b}
export ANDROID_KEYSTORE_PATH=... ANDROID_KEYSTORE_PASSWORD=...
export ANDROID_KEY_ALIAS=... ANDROID_KEY_PASSWORD=...
./gradlew :mobile:assembleRelease   # mobile/build/outputs/apk/release/mobile-release.apk
```
