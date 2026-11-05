# Changelog

## 1.1.0

- Migrated from the legacy XposedBridge API (`de.robv.android.xposed:api:82`) to **libxposed API 101** (`io.github.libxposed:api:101.0.1`).
- Replaced `assets/xposed_init` and legacy manifest metadata with the modern module layout:
  - `META-INF/xposed/java_init.list`
  - `META-INF/xposed/module.prop`
  - `META-INF/xposed/scope.list`
- Migrated settings to the libxposed service and remote preferences.
- Added support for Telegram 13.1's `updateDownloadByteBudget()`:
  - The boost is no longer silently capped by the new byte-budget logic.
  - Added a `start()` fallback for builds where `updateDownloadByteBudget()` is inlined.
- Updated the boost values to match the latest Nekogram 13.1.1 implementation.
- Added `tw.nekomimi.nekogram` and `tw.nekomimi.nekogram.beta` to the default scope.
- Added an option to follow Nekogram's built-in download speed setting.
- Raised `minSdk` to 26 as required by libxposed API 101.
- Updated to AGP 8.13.2, Gradle 8.14.6, compileSdk/targetSdk 36.
