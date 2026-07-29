# Grayzone

Grayzone is an open-source Android digital wellbeing application designed to help users break endless scrolling habits and reduce doomscrolling. Unlike traditional app blockers that immediately kick you out, Grayzone uses a progressive friction mechanism by combining mindful session timers, a strict lockout penalty, and a built-in DNS-level ad/adult content blocker to discourage mindless app usage.

## Features

- **Mindful Active Sessions**: Start a timed session (e.g., 10 minutes) when you open a distracting app. 
- **Strict Lockouts**: When a session expires, you are forcibly locked out of the app. A strict cooldown timer (e.g., 30 minutes) begins, during which the app cannot be accessed.
- **Friction Overlay**: Deliberately slowing you down by making you wait 8 seconds before you can start a new active session.
- **Pausable Sessions**: If you close a monitored app before your session expires, the timer pauses. When you return, you bypass the friction screen and instantly resume your remaining time. A full reset is only earned after you drain your time and serve a full lockout.
- **Live Session Notification**: While a session is running, an ongoing notification shows the monitored app's name with a live countdown of the remaining time on the right, so you always know how long you have left.
- **Built-in DNS Blocker**: Uses a local VPN to intercept and filter DNS requests, blocking ~800k known ad/tracking domains and ~160k adult content domains. Payment/banking and DRM-heavy streaming apps are excluded from the tunnel by default (and the exclusion list is user-extendable) so they keep working normally.
- **Anti-Bypass Protection**: The Settings UI and master monitoring toggle are strictly disabled and locked if any monitored app is currently active, paused, or locked out. Additionally, known DNS-over-HTTPS (DoH) fallback domains are hard-blocked to prevent apps like Chrome from bypassing the DNS filter.
- **Daily Break**: A single one-hour "break" per calendar day during which nothing is locked — no friction screens, lockouts, or schedule blocks. Usage is still recorded so daily budgets stay accurate, ending the break early does not restore the allowance, and it resets at midnight.
- **Real-Time Limits Tab**: Keep track of the active session limits, paused times, and lockout timers for all of your monitored apps in one central dashboard.
- **AdBlock VPN Tab**: Toggle the local DNS blocker from the main bottom navigation, with live query stats. Once enabled, VPN stays on for 24 hours and then turns off automatically.

## Architecture

Grayzone leverages Android's Accessibility Services, System Alert Windows, and VpnService to monitor app usage and enforce limits securely and reliably.

- **`GrayzoneAccessibilityService`**: Monitors foreground window transitions. Detects when you open or leave a monitored app and enforces session timers.
- **`OverlayService`**: Uses `WindowManager` with `TYPE_APPLICATION_OVERLAY` to draw the friction wait screen and lockout screens on top of blocked apps. Each overlay mode (friction pause, session lockout, daily-budget lockout, and schedule/focus lockout) is described by a single `OverlayPresentation` strategy in `service/overlay/`, so per-mode title, body, countdown, and completion behavior live in one place and a new screen type is added as a new strategy rather than by editing scattered `when`/`if` branches.
- **`AdBlockVpnService` & `GrayzoneBloomFilter`**: A lightweight local VPN that intercepts port 53 UDP traffic. It uses a custom, highly-optimized binary Bloom filter (~6 MB ads + ~4 MB adult held in RAM) to match domains against 4.6 million blocklist entries in `O(1)` time with zero disk I/O per query. DNS lookups are served from a bounded, reusable `DnsResolverPool`, and a default bypass list keeps payment/streaming apps outside the tunnel.
- **`AbusedTldList`**: The ~430 "most abused" top-level domains (`.zip`, `.mov`, `.surf`, …) are matched as an exact set against a hostname's final label rather than being folded into a Bloom filter. A Bloom false positive on a bare TLD would blackhole everything under it, so `DomainZoneMatcher` never queries one; this list is the deliberate, collision-free exception.
- **`RuntimeSessionReset`**: Central "new day" reset that clears runtime session state (active sessions, lockouts, paused remainders) once per calendar day while preserving usage stats. It runs on process start (`GrayzoneApplication`) and via a midnight loop plus a per-transition safety net in `OverlayService`, so stale locks never survive midnight.
- **`MainActivity`**: A modern Jetpack Compose-based UI with bottom tabs for Home, Apps, VPN, Limits, and Settings.

## Requirements

- Android 10 (API Level 29) or higher
- Permissions Required:
  - **Accessibility Service**: To monitor which apps are currently active on screen.
  - **Display over other apps**: To draw the lockout and friction overlays.
  - **VPN Service**: To route and filter DNS traffic (requested at runtime).

## Privacy and Persistence

Grayzone keeps usage history, monitored app settings, schedules, custom prompts, and VPN restore intent locally on the device. Android cloud backup and device-transfer extraction are disabled for the app, and backup rules explicitly exclude Grayzone SharedPreferences and the Room usage database.

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
   - Click the **Run** button (green play icon) in Android Studio, or use Gradle:
   ```bash
   ./gradlew installDebug
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
