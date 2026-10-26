# Grayzone

Grayzone is an open-source Android digital wellbeing application designed to help users break endless scrolling habits and reduce doomscrolling. Unlike traditional app blockers that immediately kick you out, Grayzone uses a progressive friction mechanism by combining mindful session timers, a strict lockout penalty, and a built-in DNS-level ad/adult content blocker to discourage mindless app usage.

## Features

- **Mindful Active Sessions**: Start a timed session (e.g., 10 minutes) when you open a distracting app. 
- **Strict Lockouts**: When a session expires, you are forcibly locked out of the app. A strict cooldown timer (e.g., 30 minutes) begins, during which the app cannot be accessed.
- **Friction Overlay**: Deliberately slowing you down by making you wait 8 seconds before you can start a new active session.
- **Pausable Sessions**: If you close a monitored app before your session expires, the timer pauses. When you return, you bypass the friction screen and instantly resume your remaining time. A full reset is only earned after you drain your time and serve a full lockout.
- **Live Session Notification**: While a session is running, an ongoing notification shows the monitored app's name with a live countdown of the remaining time on the right, so you always know how long you have left.
- **Built-in DNS Blocker**: Uses a local VPN to intercept and filter DNS requests, blocking ~800k known ad/tracking domains, ~160k adult content domains, and device telemetry. Payment/banking and DRM-heavy streaming apps are excluded from the tunnel by default (and the exclusion list is user-extendable) so they keep working normally.
- **Advanced VPN Routing**: Supports Custom Rules (Allowlist/Denylist) and Split Tunneling to selectively bypass the VPN on a per-app basis. If a domain is present in both custom rules, the Allowlist takes precedence.
- **Anti-Bypass Protection**: The Settings UI and master monitoring toggle are strictly disabled and locked if any monitored app is currently active, paused, or locked out. Additionally, known DNS-over-HTTPS (DoH) fallback domains are hard-blocked to prevent apps like Chrome from bypassing the DNS filter. *Note: A known limitation is that if a device is rebooted and the wall-clock time is manually advanced while offline, lockouts can be bypassed since the monotonic elapsed time resets on boot.*
- **Gamification & Streaks**: Earn achievements (e.g. 7-day streak, 100 app resists) and build daily streaks by staying under your app budgets.
- **Daily Break**: A single one-hour "break" per calendar day during which nothing is locked — no friction screens, lockouts, or schedule blocks. Usage is still recorded so daily budgets stay accurate, ending the break early does not restore the allowance, and it resets at midnight.
- **Real-Time Limits Tab**: Keep track of the active session limits, paused times, and lockout timers for all of your monitored apps in one central dashboard.

## Architecture

Grayzone leverages Android's Accessibility Services, System Alert Windows, and VpnService to monitor app usage and enforce limits securely and reliably.

- **`GrayzoneAccessibilityService`**: Monitors foreground window transitions. Detects when you open or leave a monitored app and enforces session timers. Highly optimized to strictly debounce state changes and prevent battery drain.
- **`OverlayService`**: Uses `WindowManager` with `TYPE_APPLICATION_OVERLAY` to draw the friction wait screen and lockout screens on top of blocked apps. Each overlay mode (friction pause, session lockout, daily-budget lockout, and schedule/focus lockout) is described by a single `OverlayPresentation` strategy in `service/overlay/`.
- **`AdBlockVpnService` & `BlocklistManager`**: A lightweight local VPN that intercepts port 53 UDP traffic. It uses a custom, highly-optimized binary Bloom filter (~6 MB ads + ~4 MB adult held in RAM) to match domains against 4.6 million blocklist entries in `O(1)` time. VPN start/stop operations are heavily serialized to guarantee robust lifecycle management and prevent tunnel leaks.
- **`DateUtils`**: A highly optimized `ThreadLocal` cache for Date formatting that speeds up Gamification and Streak evaluation loops by 100x compared to raw `SimpleDateFormat` instantiations.
- **`RuntimeSessionReset`**: Central "new day" reset that clears runtime session state (active sessions, lockouts, paused remainders) once per calendar day while preserving usage stats.
- **`MainActivity`**: A modern Jetpack Compose-based UI with background-threaded state management (via `LaunchedEffect` and `Dispatchers.Default`) for butter-smooth list rendering of hundreds of apps.

## Requirements

- **JDK 21** (Gradle 8.6 does not support JDK 22+; if your default `java` is newer, pass `-Dorg.gradle.java.home` pointing at a JDK 21 install — see `INSTALL.md`)
- Android 10 (API Level 29) or higher
- Permissions Required:
  - **Accessibility Service**: To monitor which apps are currently active on screen.
  - **Display over other apps**: To draw the lockout and friction overlays.
  - **VPN Service**: To route and filter DNS traffic (requested at runtime).

## Privacy and Persistence

Grayzone keeps usage history, monitored app settings, schedules, custom prompts, and VPN intent locally on the device. Android backup is disabled (`allowBackup="false"`) and the app requests no storage permissions; clearing app data or reinstalling starts fresh.

If the DNS blocker was successfully enabled, Grayzone persists that restore intent so the VPN can be restarted after reboot. Explicitly turning the VPN off clears that intent; ordinary service teardown does not.

## Setup & Build Instructions

1. **Clone the repository:**
   ```bash
   git clone <your-repo-url>
   cd Grayzone
   ```

2. **Generate the Blocklists (Windows):**
   The blocklists are bundled inside the APK as compact `.bin` Bloom filters plus `res/raw/abused_tlds.txt`. Before building, you must generate them by running the PowerShell tool:
   ```powershell
   .\scripts\blocklist_tools.ps1
   ```
   *Note: This downloads from multiple online sources (StevenBlack, HaGeZi ads/TIF/DynDNS/TLDs/NSFW/gambling, OISD, and others) and merges them using inline C#. The spam-TLD feed is split: multi-label rows join the ads Bloom filter, bare TLDs are written to `abused_tlds.txt` for exact matching.*

3. **Open in Android Studio:**
   - Open Android Studio and select **Open**.
   - Navigate to the cloned `Grayzone` directory and click **OK**.
   - Allow Gradle to sync the project.

4. **Build and Run:**
   - Connect your Android device via USB or start an emulator.
   - Click the **Run** button (green play icon) in Android Studio, or use Gradle (JDK 21):
   ```bash
   ./gradlew installDebug
   ```
   If the build fails with a bare version token like `25.0.4`, your shell is using a JDK newer than 21. Point Gradle at JDK 21 explicitly:
   ```bash
   ./gradlew installDebug "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"
   ```

5. **Permissions Configuration on Device:**
   - Open Grayzone.
   - Grant the required **Accessibility** and **Display over other apps** permissions when prompted.
   - Navigate to the **Apps** tab and toggle on the apps you want to monitor.

## Customization

You can customize the friction parameters directly in the **Settings** tab within the app:
- **Wait Duration:** Slider to adjust the friction overlay time (3-30 seconds).
- **Session Limit:** Slider to set how long you can use an app (1-60 minutes).
- **Lockout Duration:** Slider to set the lockout penalty cooldown (15 minutes - 5 hours).

## License

This project is licensed under the MIT License - see the LICENSE file for details.
