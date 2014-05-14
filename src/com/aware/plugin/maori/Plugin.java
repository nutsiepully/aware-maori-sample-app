package com.aware.plugin.maori;

import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.providers.Barometer_Provider.Barometer_Data;
import com.aware.utils.Aware_Sensor;

import io.pulkit.ubicomp.maori.Maori;
import weka.classifiers.AbstractClassifier;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.FastVector;
import weka.core.Instances;

public class Plugin extends Aware_Sensor {

    //A multi-thread handler manager
    private static HandlerThread thread_handler = null;

    //A thread to read data efficiently from the database
    private static Handler thread_sensor = null;

    //Barometer ContextObserver
    private static BarometerObserver barometer_observer = null;

    /**
     * Shared context: the user is going up (altitude is increasing) <br/>
     * Extras:<br/>
     * altitude - current altitude to sea level (approximate) in meters <br/>
     * pressure - current atmospheric pressure (mPa) <br/>
     * speed - speed of altitude change (approximate) in meters per second
     */
    public static final String ACTION_AWARE_INDOOR_DIRECTION_UP = "ACTION_AWARE_INDOOR_DIRECTION_UP";

    /**
     * Shared context: the user is going down (altitude is decreasing) <br/>
     * Extras:<br/>
     * altitude - current altitude to sea level (approximate) in meters <br/>
     * pressure - current atmospheric pressure (mPa) <br/>
     * speed - speed of altitude change (approximate) in meters per second
     */
    public static final String ACTION_AWARE_INDOOR_DIRECTION_DOWN = "ACTION_AWARE_INDOOR_DIRECTION_DOWN";

    /**
     * Shared context: the user is stationary (altitude is neither increasing or decreasing) <br/>
     * Extras:<br/>
     * altitude - current altitude to sea level (approximate) in meters <br/>
     * pressure - current atmospheric pressure (mPa) <br/>
     * speed - speed of altitude change (approximate) in meters per second <br/>
     * pressure_speed = the speed of pressure change in mPa per second
     */
    public static final String ACTION_AWARE_INDOOR_DIRECTION_IDLE = "ACTION_AWARE_INDOOR_DIRECTION_IDLE";

    /**
     * altitude - current altitude to sea level (approximate) in meters
     */
    public static final String EXTRA_ALTITUDE = "altitude";

    /**
     * speed - speed of altitude change (approximate) in meters per second
     */
    public static final String EXTRA_SPEED = "speed";

    /**
     * pressure - current atmospheric pressure (mPa)
     */
    public static final String EXTRA_PRESSURE = "pressure";

    /**
     * pressure_speed = the speed of pressure change in mPa per second
     */
    public static final String EXTRA_PRESSURE_SPEED = "pressure_speed";

    public static final int DIRECTION_UP = 1;
    public static final int DIRECTION_DOWN = 2;
    public static final int DIRECTION_IDLE = 0;

    private static double CURRENT_ALTITUDE = 0;
    private static double CURRENT_SPEED = 0;
    private static double CURRENT_PRESSURE = 0;
    private static double CURRENT_PRESSURE_SPEED = 0;
    private static int CURRENT_DIRECTION = DIRECTION_IDLE;

    public static int SAMPLING_WINDOW = 5000;

    private Instances dataset;
    private AbstractClassifier atmosphereClassifier;

    @Override
    public void onCreate() {
        super.onCreate();

        //Logcat label
        TAG = "maori-sample-app";

        //Use the users' debug preference from AWARE
        DEBUG = Aware.getSetting(getContentResolver(), Aware_Preferences.DEBUG_FLAG).equals("true");

        //Set atmospheric pressure sensor as activated
        Aware.setSetting(getContentResolver(), Aware_Preferences.STATUS_BAROMETER, true);

        //Set the sampling rate to approximately 1Hz, to save battery power
        Aware.setSetting(getContentResolver(), Aware_Preferences.FREQUENCY_BAROMETER, 1000000000);

        //Request AWARE to apply settings
        Intent activate_air = new Intent(Aware.ACTION_AWARE_REFRESH);
        sendBroadcast(activate_air);

        //Initialize the multi-thread handler manager
        thread_handler = new HandlerThread(TAG);
        thread_handler.start();

        //register the sensor handler thread with the multi-thread handler manager
        thread_sensor = new Handler(thread_handler.getLooper());

        //Initialize the context observer with the sensor thread for performance
        barometer_observer = new BarometerObserver(thread_sensor);

        //start listening to changes on the atmospheric pressure (barometer) database
        getContentResolver().registerContentObserver(Barometer_Data.CONTENT_URI, true, barometer_observer);

        //Share the current context automatically when we receive the broadcast: Aware.ACTION_AWARE_CURRENT_CONTEXT
        CONTEXT_PRODUCER = new Aware_Sensor.ContextProducer() {
            @Override
            public void onContext() {
                Intent direction = new Intent();
                switch(CURRENT_DIRECTION) {
                    case DIRECTION_UP:
                        direction.setAction(ACTION_AWARE_INDOOR_DIRECTION_UP);
                        break;
                    case DIRECTION_DOWN:
                        direction.setAction(ACTION_AWARE_INDOOR_DIRECTION_DOWN);
                        break;
                    case DIRECTION_IDLE:
                        direction.setAction(ACTION_AWARE_INDOOR_DIRECTION_IDLE);
                        break;
                }
                direction.putExtra(EXTRA_ALTITUDE, CURRENT_ALTITUDE);
                direction.putExtra(EXTRA_PRESSURE, CURRENT_PRESSURE);
                direction.putExtra(EXTRA_SPEED, CURRENT_SPEED);
                direction.putExtra(EXTRA_PRESSURE_SPEED, CURRENT_PRESSURE_SPEED);
                sendBroadcast(direction);
            }
        };

        Attribute attribute1 = new Attribute("current_speed");
        Attribute attribute2 = new Attribute("current_pressure_speed");
        FastVector states = new FastVector();
        states.addElement("idle");
        states.addElement("up");
        states.addElement("down");
        Attribute classAttribute = new Attribute("class", states);

        FastVector attributes = new FastVector();
        attributes.addElement(attribute1);
        attributes.addElement(attribute2);
        attributes.addElement(classAttribute);
        dataset = new Instances("Barometer", attributes, 3);
        dataset.setClassIndex(2);

        Log.i(TAG, "Plugin Created.");
    }

    protected AbstractClassifier getClassifier() {
        if (atmosphereClassifier == null) {
            Maori maori = new Maori(getApplicationContext());
            maori.refresh();
            atmosphereClassifier = maori.getClassifier("barometer-model.model");
        }
        return atmosphereClassifier;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        //Set atmospheric pressure sensor as deactivated
        Aware.setSetting(getContentResolver(), Aware_Preferences.STATUS_BAROMETER, false);

        //Request AWARE to apply settings
        Intent activate_air = new Intent(Aware.ACTION_AWARE_REFRESH);
        sendBroadcast(activate_air);

        //stop listening to changes in the database
        thread_sensor.removeCallbacksAndMessages(null);
        thread_handler.quit();
        getContentResolver().unregisterContentObserver(barometer_observer);

        Log.i(TAG, "Plugin Destroyed.");
    }

    /**
     * Barometer ContextObserver
     * - Monitors changes in atmospheric pressure
     * - Tracks changes in altitude
     * - Classifies as going up or down
     * - Calculates speed in change of altitude
     * @author denzil
     *
     */
    private class BarometerObserver extends ContentObserver {

        private double last_altitude = 0;
        private double last_pressure = 0;

        public BarometerObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            //Get the latest barometer values from database, within the sampling window
            double now = System.currentTimeMillis();
            double window_start = now-SAMPLING_WINDOW;

            String[] columns = new String[]{
                    "AVG(" + Barometer_Data.AMBIENT_PRESSURE + ") as average_pressure",
                    "MAX(" + Barometer_Data.AMBIENT_PRESSURE + ") as maximum_pressure",
                    "MIN(" + Barometer_Data.AMBIENT_PRESSURE + ") as minimum_pressure"
            };

            Cursor barometer_data = getContentResolver().query(Barometer_Data.CONTENT_URI, columns,
                    Barometer_Data.TIMESTAMP +" BETWEEN " + window_start + " AND " + now, null, Barometer_Data.TIMESTAMP + " ASC");
            if( barometer_data == null || !barometer_data.moveToFirst() ) {
                return;
            }

            double average_pressure = barometer_data.getDouble(barometer_data.getColumnIndex("average_pressure"));
            double average_altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, (float)average_pressure);

            double delta_pressure = (average_pressure-last_pressure);
            double delta_altitude = (average_altitude-last_altitude);

            last_pressure = average_pressure;
            last_altitude = average_altitude;

            CURRENT_ALTITUDE = Double.parseDouble(String.format("%.01f", average_altitude));
            CURRENT_PRESSURE = Double.parseDouble(String.format("%.01f", average_pressure));

            CURRENT_SPEED = Double.parseDouble(String.format("%.03f", delta_altitude/(SAMPLING_WINDOW/1000))); //in meters/second
            CURRENT_PRESSURE_SPEED = Double.parseDouble(String.format("%.03f", delta_pressure/(SAMPLING_WINDOW/1000))); //in mPa/second

            CURRENT_DIRECTION = (int)classifyIndoorLevel();
            Log.d(TAG, "Classified as : " + CURRENT_DIRECTION);

            CONTEXT_PRODUCER.onContext();

            //close the cursor to avoid memory leaks
            if( barometer_data != null && ! barometer_data.isClosed() ) barometer_data.close();
        }
    }

    private double classifyIndoorLevel() {
        double result = 0;
        double[] features = new double[] { CURRENT_SPEED, CURRENT_PRESSURE_SPEED };
        try {
            DenseInstance instance = new DenseInstance(1.0, features);
            instance.setDataset(dataset);
            result = getClassifier().classifyInstance(instance);
        } catch (Exception e) {
            Log.i(TAG, "Exception occurred while trying to classify. Suppressing.", e);
        }
        return result;
    }

}

