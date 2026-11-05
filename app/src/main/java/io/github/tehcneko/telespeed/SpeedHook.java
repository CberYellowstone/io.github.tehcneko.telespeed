package io.github.tehcneko.telespeed;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public class SpeedHook extends XposedModule {
    private static final String TAG = "TeleSpeed";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_FOLLOW_NEKO = "follow_neko";

    public static final String BOOST_NONE = "none";
    public static final String BOOST_AVERAGE = "average";
    public static final String BOOST_EXTREME = "extreme";

    private static final String CLASS_NEKO_CONFIG = "tw.nekomimi.nekogram.NekoConfig";
    private static final String CLASS_NOTIFICATION_CENTER = "org.telegram.messenger.NotificationCenter";

    private static final int TYPE_ERROR_SUBTITLE = 4;
    private static final long FALLBACK_MAX_FILE_SIZE = 1024L * 1024L * 2000L;
    private static final long TOAST_MIN_FILE_SIZE = 15L * 1024L * 1024L;
    private static final long TOAST_INTERVAL_MS = 5L * 60L * 1000L;

    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "nekox.messenger",
            "com.cool2645.nekolite",
            "org.telegram.plus",
            "com.iMe.android",
            "org.telegram.BifToGram",
            "ua.itaysonlab.messenger",
            "org.forkclient.messenger.beta",
            "org.aka.messenger",
            "ellipi.messenger",
            "tw.nekomimi.nekogram",
            "tw.nekomimi.nekogram.beta"
    ));

    /**
     * Telegram 13.1 and earlier keep the original FileLoadOperation names. Telegram 13.2.0
     * (beta 72879) ships an obfuscated FileLoadOperation as org.telegram.messenger.i7 with
     * methods u()/t()/r() and short field names.
     */
    private static final HookProfile LEGACY_PROFILE = new HookProfile(
            "org.telegram.messenger.FileLoadOperation",
            "updateParams",
            "updateDownloadByteBudget",
            "start", 3,
            "downloadChunkSizeBig",
            "maxDownloadRequests",
            "maxDownloadRequestsBig",
            "maxCdnParts",
            "currentMaxDownloadRequests",
            "useDownloadByteBudget",
            "totalBytesCount",
            "isStream",
            "isPreloadVideoOperation",
            "forceSmallChunk"
    );

    private static final HookProfile TELEGRAM_132_PROFILE = new HookProfile(
            "org.telegram.messenger.i7",
            "u",
            "t",
            "r", 0,
            "n",
            "o",
            "p",
            "q",
            "k0",
            "f",
            "b0",
            "r0",
            "y",
            "e"
    );

    /**
     * Covers builds that keep the FileLoadOperation class name but rename its members.
     */
    /**
     * Telegram 13.2.0 web build (versionCode 72809) uses a different obfuscation map:
     * FileLoadOperation -> h7, updateParams() -> t(), updateDownloadByteBudget() -> s(),
     * start() -> q(). The field names are the same short names as the beta build.
     */
    private static final HookProfile TELEGRAM_132_WEB_PROFILE = new HookProfile(
            "org.telegram.messenger.h7",
            "t",
            "s",
            "q", 0,
            "n",
            "o",
            "p",
            "q",
            "k0",
            "f",
            "b0",
            "r0",
            "y",
            "e"
    );

    private static final HookProfile FILE_LOAD_OPERATION_132_PROFILE = new HookProfile(
            "org.telegram.messenger.FileLoadOperation",
            "u",
            "t",
            "r", 0,
            "n",
            "o",
            "p",
            "q",
            "k0",
            "f",
            "b0",
            "r0",
            "y",
            "e"
    );

    private static final HookProfile[] PROFILES = {
            LEGACY_PROFILE,
            TELEGRAM_132_PROFILE,
            TELEGRAM_132_WEB_PROFILE,
            FILE_LOAD_OPERATION_132_PROFILE
    };

    private volatile SharedPreferences prefs;
    private volatile long speedUpShown = 0;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "TeleSpeed " + BuildConfig.VERSION_NAME + " loaded in "
                + param.getProcessName() + " (framework " + getFrameworkName() + " "
                + getFrameworkVersion() + ", API " + getApiVersion() + ")");

        if ((getFrameworkProperties() & PROP_CAP_REMOTE) == 0) {
            log(Log.WARN, TAG, "Remote preferences are not supported by this framework");
            return;
        }
        prefs = openPrefs();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage()) {
            return;
        }
        String packageName = param.getPackageName();
        if (!TARGET_PACKAGES.contains(packageName)) {
            return;
        }

        ClassLoader classLoader = param.getClassLoader();
        for (HookProfile profile : PROFILES) {
            if (tryHook(profile, packageName, classLoader)) {
                log(Log.INFO, TAG, "Hooked " + packageName + " via " + profile.className);
                return;
            }
        }

        log(Log.ERROR, TAG, "No hook profile matched " + packageName);
        hookUnsupportedToast();
    }

    private boolean tryHook(HookProfile profile, String packageName, ClassLoader classLoader) {
        final Class<?> fileLoadOperation;
        try {
            fileLoadOperation = Class.forName(profile.className, false, classLoader);
        } catch (Throwable t) {
            return false;
        }

        if (!hasField(fileLoadOperation, profile.chunkSizeBig)
                || !hasField(fileLoadOperation, profile.maxRequests)
                || !hasField(fileLoadOperation, profile.maxRequestsBig)
                || !hasField(fileLoadOperation, profile.maxCdnParts)
                || !hasField(fileLoadOperation, profile.currentMaxRequests)
                || !hasField(fileLoadOperation, profile.useByteBudget)) {
            return false;
        }

        final Method updateParams;
        try {
            updateParams = fileLoadOperation.getDeclaredMethod(profile.updateParamsMethod);
        } catch (Throwable t) {
            return false;
        }

        try {
            hook(updateParams)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        applyBoost(chain.getThisObject(), packageName, classLoader, profile);
                        return result;
                    });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Cannot hook " + profile.className + "." + profile.updateParamsMethod, t);
            return false;
        }

        try {
            Method updateDownloadByteBudget = fileLoadOperation.getDeclaredMethod(profile.updateByteBudgetMethod);
            hook(updateDownloadByteBudget)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        String level = getSpeedLevel(packageName, classLoader);
                        if (!BOOST_NONE.equals(level)) {
                            setBooleanField(chain.getThisObject(), profile.useByteBudget, false);
                            setIntField(chain.getThisObject(), profile.currentMaxRequests, maxRequests(level));
                        }
                        return result;
                    });
        } catch (NoSuchMethodException e) {
            log(Log.DEBUG, TAG, profile.updateByteBudgetMethod + "() not found in " + profile.className);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Cannot hook " + profile.className + "." + profile.updateByteBudgetMethod, t);
        }

        try {
            Method start = findMethod(fileLoadOperation, profile.startMethod, profile.startParamCount);
            if (start != null) {
                hook(start)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object operation = chain.getThisObject();
                            if (profile.startParamCount > 0 && chain.getArg(0) != null) {
                                return result;
                            }
                            if (getBooleanField(operation, profile.isStream, false)
                                    || getBooleanField(operation, profile.isPreloadVideo, false)
                                    || getBooleanField(operation, profile.forceSmallChunk, false)) {
                                return result;
                            }
                            String level = getSpeedLevel(packageName, classLoader);
                            if (!BOOST_NONE.equals(level)) {
                                setBooleanField(operation, profile.useByteBudget, false);
                                setIntField(operation, profile.currentMaxRequests, maxRequests(level));
                            }
                            return result;
                        });
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Cannot hook " + profile.className + "." + profile.startMethod, t);
        }

        return true;
    }

    private void applyBoost(Object operation, String packageName, ClassLoader classLoader, HookProfile profile) {
        String level = getSpeedLevel(packageName, classLoader);
        int chunkSize = chunkSize(level);
        int requests = maxRequests(level);

        setIntField(operation, profile.chunkSizeBig, chunkSize);
        setIntField(operation, profile.maxRequests, requests);
        setIntField(operation, profile.maxRequestsBig, requests);
        setIntField(operation, profile.maxCdnParts, (int) (getDefaultMaxFileSize(classLoader) / chunkSize));

        if (!BOOST_NONE.equals(level)) {
            setBooleanField(operation, profile.useByteBudget, false);
            setIntField(operation, profile.currentMaxRequests, requests);
        }

        showBoostToast(operation, level, classLoader, profile);
    }

    private String getSpeedLevel(String packageName, ClassLoader classLoader) {
        SharedPreferences preferences = getPrefs();
        if (preferences == null) {
            return BOOST_NONE;
        }

        try {
            if (isNekogram(packageName) && preferences.getBoolean(KEY_FOLLOW_NEKO, false)) {
                int nekoBoost = getNekogramBoost(classLoader);
                if (nekoBoost == 0) {
                    return BOOST_NONE;
                } else if (nekoBoost == 1) {
                    return BOOST_AVERAGE;
                } else if (nekoBoost == 2) {
                    return BOOST_EXTREME;
                }
            }

            String level = preferences.getString(KEY_SPEED, BOOST_AVERAGE);
            if (!BOOST_NONE.equals(level) && !BOOST_AVERAGE.equals(level) && !BOOST_EXTREME.equals(level)) {
                return BOOST_AVERAGE;
            }
            return level;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Cannot read preferences", t);
            return BOOST_NONE;
        }
    }

    private SharedPreferences getPrefs() {
        if (prefs == null) {
            prefs = openPrefs();
        }
        return prefs;
    }

    private SharedPreferences openPrefs() {
        try {
            SharedPreferences preferences = getRemotePreferences("conf");
            if (preferences != null) {
                // Keep the framework's remote-preferences object live when the settings app writes.
                preferences.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
                });
            }
            return preferences;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Cannot open remote preferences", t);
            return null;
        }
    }

    private boolean isNekogram(String packageName) {
        return "tw.nekomimi.nekogram".equals(packageName)
                || "tw.nekomimi.nekogram.beta".equals(packageName);
    }

    private int getNekogramBoost(ClassLoader classLoader) {
        try {
            Class<?> nekoConfig = Class.forName(CLASS_NEKO_CONFIG, false, classLoader);
            Field field = nekoConfig.getDeclaredField("downloadSpeedBoost");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    private long getDefaultMaxFileSize(ClassLoader classLoader) {
        try {
            Class<?> fileLoader = Class.forName("org.telegram.messenger.FileLoader", false, classLoader);
            Field field = fileLoader.getDeclaredField("DEFAULT_MAX_FILE_SIZE");
            field.setAccessible(true);
            return field.getLong(null);
        } catch (Throwable t) {
            return FALLBACK_MAX_FILE_SIZE;
        }
    }

    private int chunkSize(String level) {
        if (BOOST_EXTREME.equals(level)) {
            return 1024 * 1024;
        } else if (BOOST_AVERAGE.equals(level)) {
            return 1024 * 512;
        } else {
            return 1024 * 128;
        }
    }

    private int maxRequests(String level) {
        if (BOOST_EXTREME.equals(level)) {
            return 12;
        } else if (BOOST_AVERAGE.equals(level)) {
            return 8;
        } else {
            return 4;
        }
    }

    private void showBoostToast(Object operation, String level, ClassLoader classLoader, HookProfile profile) {
        if (BOOST_NONE.equals(level)) {
            return;
        }

        long fileSize = getLongField(operation, profile.totalBytes, 0);
        long now = System.currentTimeMillis();
        if (fileSize <= TOAST_MIN_FILE_SIZE || now - speedUpShown < TOAST_INTERVAL_MS) {
            return;
        }
        speedUpShown = now;

        boolean chinese = isChinese();
        String speedString = BOOST_EXTREME.equals(level)
                ? (chinese ? "极高" : "Extreme")
                : (chinese ? "开启" : "Enabled");
        String title = chinese ? "下载速度已被 TeleSpeed 提升！" : "Download speed boosted by TeleSpeed!";
        String subtitle = (chinese ? "基于 Nekogram · 加速：" : "Based on Nekogram · Boost: ") + speedString;

        try {
            Class<?> notificationCenter = Class.forName(CLASS_NOTIFICATION_CENTER, false, classLoader);
            Method getGlobalInstance = notificationCenter.getDeclaredMethod("getGlobalInstance");
            getGlobalInstance.setAccessible(true);
            Object globalInstance = getGlobalInstance.invoke(null);

            Field showBulletinField = notificationCenter.getDeclaredField("showBulletin");
            showBulletinField.setAccessible(true);
            int showBulletin = showBulletinField.getInt(null);

            Method postNotificationName = notificationCenter.getDeclaredMethod(
                    "postNotificationName", int.class, Object[].class);
            postNotificationName.setAccessible(true);

            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    postNotificationName.invoke(globalInstance, showBulletin,
                            new Object[]{TYPE_ERROR_SUBTITLE, title, subtitle});
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Cannot post boost notification", t);
                }
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Cannot show boost notification", t);
        }
    }

    private void hookUnsupportedToast() {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object thisObject = chain.getThisObject();
                        if (thisObject instanceof Activity) {
                            String message = isChinese()
                                    ? "TeleSpeed: 不支持的 Telegram 版本。"
                                    : "TeleSpeed: Unsupported Telegram version.";
                            Toast.makeText((Activity) thisObject, message, Toast.LENGTH_LONG).show();
                        }
                        return result;
                    });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Cannot hook unsupported toast", t);
        }
    }

    private boolean isChinese() {
        return Locale.getDefault().getLanguage().startsWith("zh");
    }

    private void setIntField(Object object, String fieldName, int value) {
        Field field = findField(object, fieldName);
        if (field == null) {
            return;
        }
        try {
            field.setAccessible(true);
            field.setInt(object, value);
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Cannot set " + fieldName, t);
        }
    }

    private void setBooleanField(Object object, String fieldName, boolean value) {
        Field field = findField(object, fieldName);
        if (field == null) {
            return;
        }
        try {
            field.setAccessible(true);
            field.setBoolean(object, value);
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Cannot set " + fieldName, t);
        }
    }

    private long getLongField(Object object, String fieldName, long defaultValue) {
        Field field = findField(object, fieldName);
        if (field == null) {
            return defaultValue;
        }
        try {
            field.setAccessible(true);
            return field.getLong(object);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private boolean getBooleanField(Object object, String fieldName, boolean defaultValue) {
        Field field = findField(object, fieldName);
        if (field == null) {
            return defaultValue;
        }
        try {
            field.setAccessible(true);
            return field.getBoolean(object);
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        return null;
    }

    private boolean hasField(Class<?> type, String fieldName) {
        return findField(type, fieldName) != null;
    }

    private Field findField(Object object, String fieldName) {
        if (object == null) {
            return null;
        }
        return findField(object.getClass(), fieldName);
    }

    private Field findField(Class<?> type, String fieldName) {
        while (type != null) {
            try {
                return type.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static final class HookProfile {
        final String className;
        final String updateParamsMethod;
        final String updateByteBudgetMethod;
        final String startMethod;
        final int startParamCount;
        final String chunkSizeBig;
        final String maxRequests;
        final String maxRequestsBig;
        final String maxCdnParts;
        final String currentMaxRequests;
        final String useByteBudget;
        final String totalBytes;
        final String isStream;
        final String isPreloadVideo;
        final String forceSmallChunk;

        HookProfile(String className,
                    String updateParamsMethod,
                    String updateByteBudgetMethod,
                    String startMethod,
                    int startParamCount,
                    String chunkSizeBig,
                    String maxRequests,
                    String maxRequestsBig,
                    String maxCdnParts,
                    String currentMaxRequests,
                    String useByteBudget,
                    String totalBytes,
                    String isStream,
                    String isPreloadVideo,
                    String forceSmallChunk) {
            this.className = className;
            this.updateParamsMethod = updateParamsMethod;
            this.updateByteBudgetMethod = updateByteBudgetMethod;
            this.startMethod = startMethod;
            this.startParamCount = startParamCount;
            this.chunkSizeBig = chunkSizeBig;
            this.maxRequests = maxRequests;
            this.maxRequestsBig = maxRequestsBig;
            this.maxCdnParts = maxCdnParts;
            this.currentMaxRequests = currentMaxRequests;
            this.useByteBudget = useByteBudget;
            this.totalBytes = totalBytes;
            this.isStream = isStream;
            this.isPreloadVideo = isPreloadVideo;
            this.forceSmallChunk = forceSmallChunk;
        }
    }
}
