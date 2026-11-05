package io.github.tehcneko.telespeed;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.view.View;
import android.widget.Toast;

import io.github.libxposed.service.XposedService;

public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, new SettingsFragment()).commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragment implements App.ServiceStateListener {
        private static final String KEY_SPEED = "speed";
        private static final String KEY_FOLLOW_NEKO = "follow_neko";

        private ListPreference speedPreference;
        private CheckBoxPreference followNekoPreference;
        private boolean updating;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getPreferenceManager().setSharedPreferencesName("conf");
            addPreferencesFromResource(R.xml.prefs);

            speedPreference = (ListPreference) findPreference(KEY_SPEED);
            followNekoPreference = (CheckBoxPreference) findPreference(KEY_FOLLOW_NEKO);

            if (speedPreference != null) {
                speedPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    save(KEY_SPEED, newValue);
                    return true;
                });
            }
            if (followNekoPreference != null) {
                followNekoPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    save(KEY_FOLLOW_NEKO, newValue);
                    return true;
                });
            }

            Preference version = findPreference("version");
            if (version != null) {
                version.setSummary(BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            App.addListener(this);
            loadPreferences();
        }

        @Override
        public void onPause() {
            App.removeListener(this);
            super.onPause();
        }

        @Override
        public void onServiceStateChanged(XposedService service) {
            loadPreferences();
        }

        private void loadPreferences() {
            if (speedPreference == null || followNekoPreference == null) {
                return;
            }

            SharedPreferences remote = getRemotePreferences();
            if (remote == null) {
                return;
            }

            updating = true;
            try {
                if (remote.contains(KEY_SPEED)) {
                    speedPreference.setValue(remote.getString(KEY_SPEED, "average"));
                } else {
                    remote.edit().putString(KEY_SPEED, speedPreference.getValue()).apply();
                }

                if (remote.contains(KEY_FOLLOW_NEKO)) {
                    followNekoPreference.setChecked(remote.getBoolean(KEY_FOLLOW_NEKO, false));
                } else {
                    remote.edit().putBoolean(KEY_FOLLOW_NEKO, followNekoPreference.isChecked()).apply();
                }
            } finally {
                updating = false;
            }
        }

        private void save(String key, Object value) {
            if (updating) {
                return;
            }

            SharedPreferences local = getPreferenceManager().getSharedPreferences();
            SharedPreferences.Editor localEditor = local.edit();
            if (value instanceof String) {
                localEditor.putString(key, (String) value);
            } else if (value instanceof Boolean) {
                localEditor.putBoolean(key, (Boolean) value);
            }
            localEditor.apply();

            SharedPreferences remote = getRemotePreferences();
            if (remote == null) {
                if (getActivity() != null) {
                    Toast.makeText(getActivity(), R.string.service_unavailable, Toast.LENGTH_SHORT).show();
                }
                return;
            }

            SharedPreferences.Editor remoteEditor = remote.edit();
            if (value instanceof String) {
                remoteEditor.putString(key, (String) value);
            } else if (value instanceof Boolean) {
                remoteEditor.putBoolean(key, (Boolean) value);
            }
            remoteEditor.apply();
        }

        private SharedPreferences getRemotePreferences() {
            XposedService service = App.getService();
            if (service == null) {
                return null;
            }
            try {
                return service.getRemotePreferences("conf");
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            View list = view.findViewById(android.R.id.list);
            list.setOnApplyWindowInsetsListener((v, insets) -> {
                list.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getStableInsetBottom());
                return insets.consumeSystemWindowInsets();
            });

            super.onViewCreated(view, savedInstanceState);
        }
    }
}
