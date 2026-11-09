# TeleSpeed

Speed up your Telegram download. 提升你的 Telegram 下载速度。

TeleSpeed is a modern Xposed module that ports **Nekogram's built-in download speed boost** to Telegram and other third-party Telegram clients. It raises the download chunk size and the number of parallel requests used by `org.telegram.messenger.FileLoadOperation`.

This version is migrated to **libxposed API 101** and follows the modern module layout:

- `META-INF/xposed/java_init.list` — Java entry point
- `META-INF/xposed/module.prop` — API version and module configuration
- `META-INF/xposed/scope.list` — target packages
- No legacy `assets/xposed_init` or `xposedmodule` manifest metadata

## Boost levels

| Level | Chunk size | Parallel requests |
|---|---:|---:|
| Disabled | 128 KB | 4 |
| Enabled | 512 KB | 8 |
| Extreme | 1 MB | 12 |

The values match Nekogram's built-in `Download speed boost` setting.

## Compatibility with Telegram 13.1+

Telegram 13.1 introduced `FileLoadOperation.updateDownloadByteBudget()`, which runs after `updateParams()` and can overwrite the configured request count. TeleSpeed 1.1.0 hooks that method as well: when a boost level is enabled, it disables the new byte budget for that operation and restores the selected request count. When the boost is disabled, Telegram's default behavior is left untouched.

Tested against the Nekogram 13.1.1 source tree, the Telegram 13.2.0 beta (versionCode 72879), the Telegram 13.2.0 web build (versionCode 72809), and the Telegram 13.2.1 web build (versionCode 72919).

Telegram 13.2.x ships an obfuscated `FileLoadOperation` as `org.telegram.messenger.i7` in the beta, `org.telegram.messenger.h7` in the 13.2.0 web build, and `org.telegram.messenger.d7` in the 13.2.1 web build. TeleSpeed 1.2.0 keeps matching hardcoded profiles as a fast path and adds a **DexKit-based generic fallback**: if no profile matches, it scans the target APK for `FileLoadOperation` by diagnostic strings, identifies the parameter methods by the fields they write, and adjusts those fields by their runtime values. This means future Telegram builds normally do not need a new hardcoded profile.

Older Telegram versions without `updateDownloadByteBudget()` are still supported through the original `updateParams()` hook.

## Nekogram

Nekogram already includes this feature. On Nekogram, open **Neko Settings → Other → Download speed boost** and choose **On** or **Extreme**; installing TeleSpeed is optional.

If TeleSpeed is installed and scoped to Nekogram, its own setting takes precedence by default. Enable **Follow Nekogram's built-in setting** in TeleSpeed to use Nekogram's value instead.

## Requirements

- Android 8.0+ (minSdk 26, required by libxposed API 101)
- LSPosed with libxposed API 101+ support
- The target Telegram client must be selected in the module scope; the default `scope.list` covers Telegram, Nekogram, NekoX, NekoLite, Plus Messenger, iMe, BifToGram, ForkClient, Aka and Ellipi

## Settings

The settings screen writes the module configuration through the libxposed service (`io.github.libxposed:service:101.0.0`). The hooked app reads the same configuration with `getRemotePreferences("conf")`.

If the service is not connected, TeleSpeed falls back to the module's local preferences and shows a short message. Reopen the settings screen after LSPosed connects to apply the values.

After updating from the legacy version, open the settings screen once. Existing local values are migrated to the remote store; until then the module uses its default (`Enabled`).

## Build

```bash
# JDK 17 and Android SDK 36 are required
./gradlew assembleRelease
```

To sign a release build, create `keystore.properties` in the project root:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

The file is ignored by Git. Without it, the release build falls back to the debug signing key.

## Notes

- The boost increases concurrency and chunk size; it does not bypass Telegram's server-side speed limits for free accounts.
- Extreme mode may increase CPU, memory, and battery usage. Start with **Enabled**.
- The DexKit fallback adds native libraries, so the APK is about 1.6 MB instead of 44 KB. The first scan of a new Telegram build takes a fraction of a second; the hardcoded profiles remain the fast path.
- This project is a fork of the original TeleSpeed module by tehcneko, updated for current Telegram/Nekogram versions and migrated to libxposed API 101.

## Third-party components

- [DexKit](https://github.com/LuckyPray/DexKit) (Apache-2.0) is used for generic hook discovery.

## License

GPL-3.0. See [LICENSE](LICENSE).
