# Grayzone

Grayzone is an open-source Android digital wellbeing application designed to help users break endless scrolling habits and reduce doomscrolling. Unlike traditional app blockers that immediately kick you out, Grayzone uses a progressive friction mechanism by combining grayscale visualization, mindful session timers, and a strict lockout penalty to discourage mindless app usage.

## Features

- **Mindful Active Sessions**: Start a timed session (e.g., 10 minutes) when you open a distracting app. 
- **Grayscale Filter**: Once an active session begins, the entire screen progressively turns black-and-white to visually break the dopamine feedback loop and reduce the appeal of colorful media feeds.
- **Strict Lockouts**: When a session expires, you are forcibly locked out of the app. A strict cooldown timer (e.g., 30 minutes) begins, during which the app cannot be accessed.
- **Friction Overlay**: Deliberately slowing you down by making you wait 8 seconds before you can start a new active session.
- **Real-Time Limits Tab**: Keep track of the active session limits and lockout timers for all of your monitored apps in one central dashboard.
- **Instant Reset**: If you close a monitored app before your session expires, the timers immediately reset, rewarding you for mindful usage.

## Architecture

Grayzone leverages Android's Accessibility Services and System Alert Windows to monitor app usage and enforce limits securely and reliably.

- **`AppAccessibilityService`**: Monitors foreground window transitions. Detects when you open or leave a monitored app and enforces session timers and grayscale modes.
- **`OverlayService`**: Uses `WindowManager` with `TYPE_APPLICATION_OVERLAY` to draw the friction wait screen and lockout screens on top of blocked apps.
- **`MainActivity`**: A modern Jetpack Compose-based UI for managing your monitored apps and viewing your live dashboard (`LimitsScreen`).

## Requirements

- Android 10 (API Level 29) or higher
- Permissions Required:
  - **Accessibility Service**: To monitor which apps are currently active on screen.
  - **Display over other apps**: To draw the lockout and friction overlays.

## Setup & Build Instructions

1. **Clone the repository:**
   ```bash
   git clone <your-repo-url>
   cd Grayzone
   ```

2. **Open in Android Studio:**
   - Open Android Studio and select **Open**.
   - Navigate to the cloned `Grayzone` directory and click **OK**.
   - Allow Gradle to sync the project.

3. **Build and Run:**
   - Connect your Android device via USB or start an emulator.
   - Click the **Run** button (green play icon) in Android Studio, or use Gradle:
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. **Permissions Configuration on Device:**
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
