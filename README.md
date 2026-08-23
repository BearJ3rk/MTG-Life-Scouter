# Arcane Ledger — Android V0.1

Local-only Android prototype. Nothing in this project publishes or syncs data.

## V0.1 features

- 2, 3, 4, or 6 player life tracking
- Commander damage per opponent
- Poison/toxic and energy counters
- Editable player names
- Device-local player-card images
- Device-local table background image
- Adjustable image overlay for readability
- Persistent game and appearance settings

Open this folder in Android Studio, allow Gradle sync, and run the `app` configuration on an Android 8.0+ device or emulator.

## Signed builds

The private GitHub repository uses an on-demand Actions workflow. Signing credentials are read only from encrypted repository secrets and are never committed to source control. The signed APK is retained as a workflow artifact for 90 days.

