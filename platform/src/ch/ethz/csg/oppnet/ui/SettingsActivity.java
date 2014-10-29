
package ch.ethz.csg.oppnet.ui;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.PreferenceActivity;

import ch.ethz.csg.oppnet.R;

@SuppressWarnings("deprecation")
public class SettingsActivity extends PreferenceActivity implements
        OnSharedPreferenceChangeListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences_main);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Resources res = getResources();
        String foreground_key = res.getString(R.string.pref_foreground_key);

        if (key.equals(foreground_key)) {
            final CheckBoxPreference pref = (CheckBoxPreference) findPreference(foreground_key);

            int summary = R.string.pref_foreground_disabled;
            if (pref.isChecked()) {
                summary = R.string.pref_foreground_enabled;
            }
            pref.setSummary(summary);
        }
    }
}
