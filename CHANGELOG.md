# Changelog

## 1.2.0

- Added a generic hook finder based on **DexKit**. When no hardcoded profile matches,
  TeleSpeed scans the target APK for `FileLoadOperation` by its diagnostic strings,
  identifies `updateParams` / `updateDownloadByteBudget` by the fields they write,
  and adjusts the fields by their runtime values.
- This removes the need to add a new hardcoded profile for every Telegram build.
- The hardcoded 13.1 / 13.2.0 beta / 13.2.0 web / 13.2.1 web profiles remain as a fast path.
- DexKit adds native libraries; the APK is now about 1.6 MB instead of 44 KB.
- Bump version to 1.2.0 (versionCode 12).

## 1.1.3

- Added support for the Telegram 13.2.1 web build (versionCode 72919,
  `org.telegram.messenger.web`):
  - `FileLoadOperation` -> `org.telegram.messenger.d7`
  - `updateParams()` -> `t()`
  - `updateDownloadByteBudget()` -> `s()`
  - `start()` -> `q()`
  - fields `n/o/p/q/k0/f/b0/r0/y/e`
- Kept the 13.2.0 web (`h7`), 13.2.0 beta (`i7`) and 13.1 legacy profiles.
- Bump version to 1.1.3 (versionCode 11).

## 1.1.2

- Added support for the Telegram 13.2.0 web build (versionCode 72809,
  `org.telegram.messenger.web`), which uses a different obfuscation map:
  - `FileLoadOperation` -> `org.telegram.messenger.h7`
  - `updateParams()` -> `t()`
  - `updateDownloadByteBudget()` -> `s()`
  - `start()` -> `q()`
- Kept the 13.2.0 beta (`i7` / `u` / `t` / `r`) and 13.1 legacy profiles.
- Bump version to 1.1.2 (versionCode 10).

## 1.1.1

- Added support for Telegram 13.2.0, where `FileLoadOperation` is shipped as
  `org.telegram.messenger.i7` with obfuscated methods and fields.
- Added a 13.2.0 hook profile (`u` / `t` / `r`, fields `n` / `o` / `p` / `q`),
  while keeping the original 13.1 and older profile.
- Added a fallback profile for builds that keep the `FileLoadOperation` class name
  but rename its members.
- Improved profile detection so an unsupported build no longer reports a false
  "unsupported version" when one of the optional hooks fails.

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
