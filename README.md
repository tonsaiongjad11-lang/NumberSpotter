# NumberSpotter 1-50

Android helper that captures the screen with MediaProjection and highlights the next number in a 5x5 number game.

## v9 behavior
- Waits until a real 5x5 board is visible, so a 1-2-3 countdown is ignored.
- Starts at **1 only**.
- Keeps the current marker locked until that cell changes and the next number is visible.
- Advances strictly 1 → 2 → 3 → ... → 50; it never intentionally skips ahead.
- Vibrates briefly when the target advances.
- Uses an overlay only; it does **not** auto-click.
- Initial board area is calibrated to the supplied Vivo V50 Lite screenshot.

The GitHub Actions workflow builds a standard Android debug APK with Android Gradle Plugin and Java 17.
