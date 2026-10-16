# Test plan: install and update through Obtainium

Checks that a phone with no developer tooling can install BT Courier from GitHub Releases and
receive the next release as an update. Needs two published releases (v0.0.1 and v0.0.2 exist).
Nothing here touches a dongle; run it on the test phone before the hardware plans so later cases
start from the installed release build.

## Preconditions

- [ ] Obtainium (`dev.imranr.obtainium`) installed from its own GitHub release or F-Droid.
- [ ] No BT Courier build on the phone (`adb uninstall app.btcourier` if a debug build is present:
  a debug build is signed with another key, and Android refuses the release APK over it).
- [ ] The two releases show on github.com/babraham123/bt-courier-android/releases with
  `bt-courier-v<version>.apk` and `SHA256SUMS`.

## 1. Add the app

- [ ] Obtainium → Add app → URL `https://github.com/babraham123/bt-courier-android`.
- [ ] Turn on "Include prereleases" (0.x releases are published as pre-releases) and leave the
  APK filter empty; Obtainium should list exactly one APK per release.
- [ ] The latest version shown equals the newest tag without the `v`.
- [ ] Pin to the older release first: Obtainium → the app → version → pick v0.0.1 (or install
  `bt-courier-v0.0.1.apk` by hand from the release page), so the update step below has
  something to update from.

## 2. Install

- [ ] Install → Android's unknown-sources prompt once for Obtainium → installer sheet → Install.
- [ ] Play Protect may warn about an unknown developer; "Install anyway". Capture the wording.
- [ ] App opens; Android Settings → Apps → BT Courier shows the installed version (the app has no About screen).
- [ ] Verify the download: `sha256sum` of the APK Obtainium cached (or re-downloaded from the
  release page) matches the line in `SHA256SUMS`.
- [ ] `apksigner verify --print-certs` (or an on-device app such as AppVerifier) shows a single
  v3 signature, CN=BT Courier. Record the certificate SHA-256 here once, as the pin for every
  later release: `<fill in>`.

## 3. Update

- [ ] Obtainium → Check for updates. The newer release is offered with its fastlane changelog
  text (`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`).
- [ ] Update installs without an uninstall; the app keeps its paired device, settings and sink
  credentials (Keystore entries survive), and the status screen still shows the last sync.
- [ ] Background updates: leave Obtainium's periodic check on for a day; the next release (or a
  re-pinned older one) is picked up without opening Obtainium.

## 4. Downgrade and reinstall

- [ ] Obtainium cannot downgrade (versionCode goes down); it should say so instead of failing
  mid-install. Uninstall, install the older APK, then update again to confirm the path is
  repeatable.

## Capture

Obtainium's version list screenshot, the installer and Play Protect prompts, the app-info version
before and after update, and the certificate SHA-256. No logs are needed; none hold PII.
