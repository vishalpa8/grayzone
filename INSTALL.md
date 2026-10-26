# Installing Grayzone

The shareable file is **`Grayzone-v1.0.apk`** in the project root. It is a **release-signed** APK
(APK Signature Scheme v2, certificate `CN=Grayzone`). Send this file to the person who wants to
install it (WhatsApp, Drive, email, USB, etc.).

> **Keep the signing key safe.** The app is signed with `grayzone-release.jks` (password in
> `keystore.properties`, both git-ignored). Every future update **must** be signed with this same
> key, or installs will fail with a "signature mismatch" error. Back up `grayzone-release.jks`
> somewhere safe — if you lose it you can never update an already-installed copy; the user has to
> uninstall first.

---

## Why installing can be blocked

Grayzone needs an **Accessibility Service** (that's what lets it detect and block other apps). Google
treats accessibility as a "sensitive" permission, so how the install behaves depends on the
recipient's country and how they open the file:

| Situation | What the recipient sees |
|---|---|
| **Outside India** (US, EU, etc.) | A dismissible "unsafe app" warning with an **Install anyway** / **More details** button. They can install. |
| **In India**, opened from Chrome / WhatsApp / Files | **Hard block**: *"App blocked to protect your device"* with only an **OK** button. This is Google's India fraud-protection filter. See the workaround below. |
| **Installed via ADB** (developer cable/Wi-Fi) | Installs with no block anywhere. |

There is no way to remove this while the app keeps the accessibility permission — it's core to how
Grayzone works.

---

## For the recipient — how to install

### Step 1: Allow installing from this source (all users)
When they tap the APK the first time, Android asks to allow installs from that app (e.g. Files or the
browser). Tap **Settings → allow "Install unknown apps"** for that app, then go back and tap the APK
again.

### Step 2a: If they see a dismissible warning (outside India)
Tap **More details → Install anyway**. Done.

### Step 2b: If they see "App blocked to protect your device" with only OK (India)
This is the fraud-protection block. To install they must briefly turn off Play Protect scanning:

1. Open the **Play Store**.
2. Tap the **profile picture** (top-right).
3. Tap **Play Protect** → the **gear / settings** icon.
4. Turn **OFF** "**Scan apps with Play Protect**".
5. Open the APK again and install it.
6. (Recommended) Turn Play Protect scanning **back ON** afterwards — the installed app keeps working.

### Step 3: Grant the permissions inside the app
After install, open Grayzone and follow its setup to grant:
- **Notifications** (protection status + session countdown)
- **Accessibility** (detect app switches)
- **Display over other apps** (the pause overlay)
- **Unrestricted battery** (keeps protection running with the screen off)

---

## The clean alternative: Google Play Internal Testing

If you'll share with more than a couple of people (especially in India), publishing to the Play Store
**Internal testing** track removes the block entirely — testers install from Play with no warnings and
get automatic updates.

1. One-time: create a [Google Play Console](https://play.google.com/console) account ($25).
2. Build an app bundle: `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
3. Upload it to **Internal testing** (up to 100 testers, live within minutes, no full review).
4. Add each tester's Gmail address and share the opt-in link.

---

## Rebuilding the APK yourself

```powershell
# Signed release APK (the shareable file)
./gradlew assembleRelease "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"
# Output: app/build/outputs/apk/release/app-release.apk

# Install directly to a connected device (never blocked)
adb install -r app/build/outputs/apk/release/app-release.apk
```

Requires `keystore.properties` + `grayzone-release.jks` present in the project root (see
`keystore.properties.example`). Without them, release builds are produced **unsigned** and cannot be
installed.
