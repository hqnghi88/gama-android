package com.gama.nativeapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.util.Log;

import java.util.List;

import gama.extension.androidsensor.AndroidSensorBridge;

/**
 * Registers Android {@link SensorManager} listeners and publishes the latest readings into the
 * GAMA {@link AndroidSensorBridge}, where the {@code android_sensor} GAML skill reads them.
 */
public class SensorBridge {

    private static final String TAG = "SensorBridge";

    private final SensorManager sensorManager;
    private final Context context;
    private final SensorEventListener listener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            switch (event.sensor.getType()) {
                case Sensor.TYPE_ACCELEROMETER -> setAccelerometer(event);
                case Sensor.TYPE_GYROSCOPE -> setGyroscope(event);
                case Sensor.TYPE_ORIENTATION -> setOrientation(event);
                case Sensor.TYPE_MAGNETIC_FIELD -> setMagnetic(event);
                case Sensor.TYPE_LIGHT -> light = event.values[0];
                case Sensor.TYPE_PROXIMITY -> proximity = event.values[0];
                case Sensor.TYPE_PRESSURE -> pressure = event.values[0];
                case Sensor.TYPE_AMBIENT_TEMPERATURE -> temperature = event.values[0];
                case Sensor.TYPE_RELATIVE_HUMIDITY -> humidity = event.values[0];
                default -> { return; }
            }
            publish();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (intent == null || !Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) return;
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            batteryLevel = scale > 0 ? level * 100f / scale : 0f;
            publish();
        }
    };

    private volatile float accX, accY, accZ;
    private volatile float gyrX, gyrY, gyrZ;
    private volatile float oriX, oriY, oriZ;
    private volatile float magX, magY, magZ;
    private volatile float light;
    private volatile float proximity;
    private volatile float pressure;
    private volatile float temperature;
    private volatile float humidity;
    private volatile float batteryLevel;

    public SensorBridge(Context context) {
        this.context = context.getApplicationContext();
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    }

    public void start() {
        List<Integer> types = List.of(
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_ORIENTATION,
                Sensor.TYPE_MAGNETIC_FIELD,
                Sensor.TYPE_LIGHT,
                Sensor.TYPE_PROXIMITY,
                Sensor.TYPE_PRESSURE,
                Sensor.TYPE_AMBIENT_TEMPERATURE,
                Sensor.TYPE_RELATIVE_HUMIDITY);
        int registered = 0;
        for (int type : types) {
            Sensor sensor = sensorManager.getDefaultSensor(type);
            if (sensor != null) {
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME);
                registered++;
            }
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        context.registerReceiver(batteryReceiver, filter);
    }

    public void stop() {
        sensorManager.unregisterListener(listener);
        try {
            context.unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "stop: receiver not registered");
        }
    }

    private void setAccelerometer(SensorEvent event) {
        accX = event.values[0];
        accY = event.values[1];
        accZ = event.values[2];
    }

    private void setGyroscope(SensorEvent event) {
        gyrX = event.values[0];
        gyrY = event.values[1];
        gyrZ = event.values[2];
    }

    private void setOrientation(SensorEvent event) {
        oriX = event.values[0];
        oriY = event.values[1];
        oriZ = event.values[2];
    }

    private void setMagnetic(SensorEvent event) {
        magX = event.values[0];
        magY = event.values[1];
        magZ = event.values[2];
    }

    private void publish() {
        new AndroidSensorBridge.Builder()
                .withTimestamp(System.currentTimeMillis())
                .withAccelerometer(accX, accY, accZ)
                .withGyroscope(gyrX, gyrY, gyrZ)
                .withOrientation(oriX, oriY, oriZ)
                .withMagnetic(magX, magY, magZ)
                .withLight(light)
                .withProximity(proximity)
                .withPressure(pressure)
                .withTemperature(temperature)
                .withHumidity(humidity)
                .withBatteryLevel(batteryLevel)
                .publish();
    }
}
