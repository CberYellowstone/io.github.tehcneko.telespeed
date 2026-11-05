package io.github.tehcneko.telespeed;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class App extends Application implements XposedServiceHelper.OnServiceListener {
    public interface ServiceStateListener {
        void onServiceStateChanged(XposedService service);
    }

    private static final CopyOnWriteArraySet<ServiceStateListener> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile XposedService service;

    public static XposedService getService() {
        return service;
    }

    public static void addListener(ServiceStateListener listener) {
        LISTENERS.add(listener);
        listener.onServiceStateChanged(service);
    }

    public static void removeListener(ServiceStateListener listener) {
        LISTENERS.remove(listener);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService service) {
        App.service = service;
        notifyListeners(service);
    }

    @Override
    public void onServiceDied(XposedService service) {
        if (App.service == service) {
            App.service = null;
        }
        notifyListeners(null);
    }

    private static void notifyListeners(XposedService service) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (ServiceStateListener listener : LISTENERS) {
                listener.onServiceStateChanged(service);
            }
        });
    }
}
