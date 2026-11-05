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

    private static final String CLASS_FILE_LOAD_OPERATION = "org.telegram.messenger.FileLoadOperation";
    private static final String CLASS_FILE_LOADER = "org.telegram.messenger.FileLoader";
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
        try {
            Class<?> fileLoadOperation = Class.forName(CLASS_FILE_LOAD_OPERATION, false, classLoader);
            Method updateParams = fileLoadOperation.getDeclaredMethod("updateParams");
            hook(updateParams)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        applyBoost(chain.getThisObject(), packageName, classLoader);
                        return result;
                    });

            try {
                Method updateDownloadByteBudget = fileLoadOperation.getDeclaredMethod("updateDownloadByteBudget");
                hook(updateDownloadByteBudget)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            String level = getSpeedLevel(packageName, classLoader);
                            if (!BOOST_NONE.equals(level)) {
                                // Telegram 13.1+ caps currentMaxDownloadRequests here. Restore the
                                // configured concurrency so the boost is not silently undone.
                                setBooleanField(chain.getThisObject(), "useDownloadByteBudget", false);
                                setIntField(chain.getThisObject(), "currentMaxDownloadRequests", maxRequests(level));
                            }
                            return result;
                        });
            } catch (NoSuchMethodException e) {
                log(Log.DEBUG, TAG, "updateDownloadByteBudget() not found in " + packageName);
            }

            // Fallback for builds where updateDownloadByteBudget() is inlined. start() posts the
            // first request to the stage queue, so overriding the fields here still takes effect.
            Method start = findMethod(fileLoadOperation, "start", 3);
            if (start != null) {
                hook(start)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object operation = chain.getThisObject();
                            if (chain.getArg(0) != null
                                    || getBooleanField(operation, "isStream", false)
                                    || getBooleanField(operation, "isPreloadVideoOperation", false)
                                    || getBooleanField(operation, "forceSmallChunk", false)) {
                                return result;
                            }
                            String level = getSpeedLevel(packageName, classLoader);
                            if (!BOOST_NONE.equals(level)) {
                                setBooleanField(operation, "useDownloadByteBudget", false);
                                setIntField(operation, "currentMaxDownloadRequests", maxRequests(level));
                            }
                            return result;
                        });
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Cannot hook " + packageName, t);
            hookUnsupportedToast();
        }
    }

    private void applyBoost(Object operation, String packageName, ClassLoader classLoader) {
        String level = getSpeedLevel(packageName, classLoader);
        int chunkSize = chunkSize(level);
        int requests = maxRequests(level);

        setIntField(operation, "downloadChunkSizeBig", chunkSize);
        setIntField(operation, "maxDownloadRequests", requests);
        setIntField(operation, "maxDownloadRequestsBig", requests);
        setIntField(operation, "maxCdnParts", (int) (getDefaultMaxFileSize(classLoader) / chunkSize));

        if (!BOOST_NONE.equals(level)) {
            setBooleanField(operation, "useDownloadByteBudget", false);
            setIntField(operation, "currentMaxDownloadRequests", requests);
        }

        showBoostToast(operation, level, classLoader);
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
            Class<?> fileLoader = Class.forName(CLASS_FILE_LOADER, false, classLoader);
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

    private void showBoostToast(Object operation, String level, ClassLoader classLoader) {
        if (BOOST_NONE.equals(level)) {
            return;
        }

        long fileSize = getLongField(operation, "totalBytesCount", 0);
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

    private Field findField(Object object, String fieldName) {
        if (object == null) {
            return null;
        }
        Class<?> type = object.getClass();
        while (type != null) {
            try {
                return type.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }
}
