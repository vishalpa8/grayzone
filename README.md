# Grayzone

Grayzone is an open-source Android digital wellbeing application designed to help users break endless scrolling habits and reduce doomscrolling. Unlike traditional app blockers that immediately kick you out, Grayzone uses a progressive friction mechanism by combining grayscale visualization, mindful session timers, a strict lockout penalty, and a built-in DNS-level ad/adult content blocker to discourage mindless app usage.

## Features

- **Mindful Active Sessions**: Start a timed session (e.g., 10 minutes) when you open a distracting app. 
- **True System Grayscale**: Once an active session begins, the entire screen progressively turns black-and-white using Android's hardware Daltonizer to visually break the dopamine feedback loop and reduce the appeal of colorful media feeds.
- **Strict Lockouts**: When a session expires, you are forcibly locked out of the app. A strict cooldown timer (e.g., 30 minutes) begins, during which the app cannot be accessed.
- **Friction Overlay**: Deliberately slowing you down by making you wait 8 seconds before you can start a new active session.
- **Pausable Sessions**: If you close a monitored app before your session expires, the timer pauses. When you return, you bypass the friction screen and instantly resume your remaining time. A full reset is only earned after you drain your time and serve a full lockout.
- **Built-in DNS Blocker**: Uses a local VPN to intercept and filter DNS requests, blocking ~800k known ad/tracking domains and ~160k adult content domains.
- **Anti-Bypass Protection**: The Settings UI and master monitoring toggle are strictly disabled and locked if any monitored app is currently active, paused, or locked out. Additionally, known DNS-over-HTTPS (DoH) fallback domains are hard-blocked to prevent apps like Chrome from bypassing the DNS filter.
- **Real-Time Limits Tab**: Keep track of the active session limits, paused times, and lockout timers for all of your monitored apps in one central dashboard.

## Architecture

Grayzone leverages Android's Accessibility Services, System Alert Windows, and VpnService to monitor app usage and enforce limits securely and reliably.

- **`GrayzoneAccessibilityService`**: Monitors foreground window transitions. Detects when you open or leave a monitored app and enforces session timers and grayscale modes.
- **`OverlayService`**: Uses `WindowManager` with `TYPE_APPLICATION_OVERLAY` to draw the friction wait screen and lockout screens on top of blocked apps.
- **`AdBlockVpnService` & `GrayzoneBloomFilter`**: A lightweight local VPN that intercepts port 53 UDP traffic. It uses a custom, highly-optimized binary Bloom filter (~2MB RAM) to match domains against nearly 1 million blocklist entries in `O(1)` time with zero disk I/O per query.
- **`MainActivity`**: A modern Jetpack Compose-based UI for managing your monitored apps and viewing your live dashboard.

## Requirements

- Android 10 (API Level 29) or higher
- Permissions Required:
  - **Accessibility Service**: To monitor which apps are currently active on screen.
  - **Display over other apps**: To draw the lockout and friction overlays.
  - **VPN Service**: To route and filter DNS traffic (requested at runtime).
  - **Write Secure Settings**: To toggle Android's true hardware grayscale daltonizer (`WRITE_SECURE_SETTINGS` granted via ADB).

## Privacy and Persistence

Grayzone keeps usage history, monitored app settings, schedules, custom prompts, and VPN restore intent locally on the device. Android cloud backup and device-transfer extraction are disabled for the app, and backup rules explicitly exclude Grayzone SharedPreferences and the Room usage database.

If the DNS blocker was successfully enabled, Grayzone persists that restore intent so the VPN can be restarted after reboot. Explicitly turning the VPN off clears that intent; ordinary service teardown does not.

## Setup & Build Instructions

1. **Clone the repository:**
   ```bash
   git clone <your-repo-url>
   cd Grayzone
   ```

2. **Generate the Bloom Filters (Windows):**
   The blocklists are bundled as compact `.bin` files inside the APK. Before building, you must generate them by running the PowerShell tool:
   ```powershell
   .\scripts\blocklist_tools.ps1
   ```
   *Note: This downloads from multiple online sources (StevenBlack, Hagezi, AdGuard) and merges them using inline C#.*

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
