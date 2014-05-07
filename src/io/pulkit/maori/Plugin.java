package io.pulkit.maori;

import android.util.Log;

import com.aware.utils.Aware_Sensor;

public class Plugin extends Aware_Sensor {
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("maori-sample-app", "Created");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i("maori-sample-app", "Destroyed");
    }
}
