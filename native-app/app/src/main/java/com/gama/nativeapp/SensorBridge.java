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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import java.util.List;

/**
 * Registers Android {@link SensorManager} listeners and publishes the latest readings into the
 * GAMA {@code gama.extension.androidsensor.AndroidSensorBridge}, where the {@code android_sensor}
 * GAML skill reads them. The bridge class lives in the androidsensor *extension*, which since the
 * runtime-plugin move ships as an installable plugin; it is reached reflectively through the
 * plugin's classloader so the app stays compilable (and publish becomes a no-op) without it.
 */
public class SensorBridge {

    private static final String TAG = "SensorBridge";
    private static final String EXTENSION = "gama.extension.androidsensor";

    /** Cached reflection handles into the androidsensor extension's AndroidSensorBridge.Builder. */
    private static final class Bridge {
        final Constructor<?> constructor;
        final Method timestamp;
        final Method accelerometer;
        final Method gyroscope;
        final Method orientation;
        final Method magnetic;
        final Method light;
        final Method proximity;
        final Method pressure;
        final Method temperature;
        final Method humidity;
        final Method battery;
        final Method publish;

        Bridge(Constructor<?> constructor, Method timestamp, Method accelerometer,
               Method gyroscope, Method orientation, Method magnetic, Method light,
               Method proximity, Method pressure, Method temperature, Method humidity,
               Method battery, Method publish) {
            this.constructor = constructor;
            this.timestamp = timestamp;
            this.accelerometer = accelerometer;
            this.gyroscope = gyroscope;
            this.orientation = orientation;
            this.magnetic = magnetic;
            this.light = light;
            this.proximity = proximity;
            this.pressure = pressure;
            this.temperature = temperature;
            this.humidity = humidity;
            this.battery = battery;
            this.publish = publish;
        }

        static Bridge forLoader(ClassLoader loader) {
            try {
                Class<?> builder = Class.forName(
                        "gama.extension.androidsensor.AndroidSensorBridge$Builder", false, loader);
                return new Bridge(
                        builder.getConstructor(),
                        builder.getMethod("withTimestamp", long.class),
                        builder.getMethod("withAccelerometer", float.class, float.class, float.class),
                        builder.getMethod("withGyroscope", float.class, float.class, float.class),
                        builder.getMethod("withOrientation", float.class, float.class, float.class),
                        builder.getMethod("withMagnetic", float.class, float.class, float.class),
                        builder.getMethod("withLight", float.class),
                        builder.getMethod("withProximity", float.class),
                        builder.getMethod("withPressure", float.class),
                        builder.getMethod("withTemperature", float.class),
                        builder.getMethod("withHumidity", float.class),
                        builder.getMethod("withBatteryLevel", float.class),
                        builder.getMethod("publish"));
            } catch (Throwable t) {
                Log.w(TAG, "android_sensor extension not usable: " + t);
                return null;
            }
        }
    }

    private final SensorManager sensorManager;
    private final Context context;
    private volatile Bridge bridge;
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
        Bridge b = bridge;
        if (b == null) {
            b = Bridge.forLoader(PluginManager.classLoaderOf(EXTENSION));
            bridge = b;
            if (b == null) return;
        }
        try {
            Object builder = b.constructor.newInstance();
            b.timestamp.invoke(builder, System.currentTimeMillis());
            b.accelerometer.invoke(builder, accX, accY, accZ);
            b.gyroscope.invoke(builder, gyrX, gyrY, gyrZ);
            b.orientation.invoke(builder, oriX, oriY, oriZ);
            b.magnetic.invoke(builder, magX, magY, magZ);
            b.light.invoke(builder, light);
            b.proximity.invoke(builder, proximity);
            b.pressure.invoke(builder, pressure);
            b.temperature.invoke(builder, temperature);
            b.humidity.invoke(builder, humidity);
            b.battery.invoke(builder, batteryLevel);
            b.publish.invoke(builder);
        } catch (Throwable t) {
            Log.w(TAG, "publish failed: " + t);
            bridge = null;
        }
    }
}
