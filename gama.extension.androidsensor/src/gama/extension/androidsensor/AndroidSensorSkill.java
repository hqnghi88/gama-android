package gama.extension.androidsensor;

import java.util.List;

import gama.annotations.doc;
import gama.annotations.skill;
import gama.api.exceptions.GamaRuntimeException;
import gama.api.gaml.types.IType;
import gama.api.gaml.types.Types;
import gama.api.kernel.skill.Skill;
import gama.api.runtime.scope.IScope;
import gama.api.types.list.GamaListFactory;

/**
 * AndroidSensorSkill.java, in gama.extension.androidsensor, is part of the source code of the GAMA modeling and
 * simulation platform. Ported to the new GAMA API (gama.api.*) so the app no longer depends on the legacy
 * gama.extension.androidsensor plugin jar, which was dropped from recent GAMA builds.
 *
 * The skill reads live sensor values published into {@link AndroidSensorBridge} by the Android side
 * (com.gama.nativeapp.SensorBridge) and exposes them to GAML as variables (accel_x, gyro_z, light, ...) and
 * primitives (get_sensor_data, is_sensor_available, inject_sensor_data).
 */
@skill(
		name = AndroidSensorSkill.SENSOR_SKILL,
		concept = {},
		doc = { @doc("A skill allowing agents to read the sensors of the Android device (accelerometer, gyroscope, " +
				"orientation, magnetic field, light, proximity, pressure, temperature, humidity and battery level). " +
				"Provides the accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z, orientation_x, orientation_y, " +
				"orientation_z, magnetic_x, magnetic_y, magnetic_z, light, proximity, pressure, temperature, humidity " +
				"and battery_level variables, and the get_sensor_data, is_sensor_available and inject_sensor_data actions.") })
public class AndroidSensorSkill extends Skill {

	public static final String SENSOR_SKILL = "android_sensor";
	public static final String ACCELEROMETER_X = "accel_x";
	public static final String ACCELEROMETER_Y = "accel_y";
	public static final String ACCELEROMETER_Z = "accel_z";
	public static final String GYROSCOPE_X = "gyro_x";
	public static final String GYROSCOPE_Y = "gyro_y";
	public static final String GYROSCOPE_Z = "gyro_z";
	public static final String ORIENTATION_X = "orientation_x";
	public static final String ORIENTATION_Y = "orientation_y";
	public static final String ORIENTATION_Z = "orientation_z";
	public static final String MAGNETIC_X = "magnetic_x";
	public static final String MAGNETIC_Y = "magnetic_y";
	public static final String MAGNETIC_Z = "magnetic_z";
	public static final String LIGHT = "light";
	public static final String PROXIMITY = "proximity";
	public static final String PRESSURE = "pressure";
	public static final String TEMPERATURE = "temperature";
	public static final String HUMIDITY = "humidity";
	public static final String BATTERY = "battery_level";
	public static final String TIMESTAMP = "sensor_timestamp";

	public AndroidSensorSkill() {}

	private static AndroidSensorBridge.Snapshot snapshot() { return AndroidSensorBridge.getSnapshot(); }

	public static Double getAccelX(final Object o) { return (double) snapshot().accelerometerX; }

	public static Double getAccelY(final Object o) { return (double) snapshot().accelerometerY; }

	public static Double getAccelZ(final Object o) { return (double) snapshot().accelerometerZ; }

	public static Double getGyroX(final Object o) { return (double) snapshot().gyroscopeX; }

	public static Double getGyroY(final Object o) { return (double) snapshot().gyroscopeY; }

	public static Double getGyroZ(final Object o) { return (double) snapshot().gyroscopeZ; }

	public static Double getOrientationX(final Object o) { return (double) snapshot().orientationX; }

	public static Double getOrientationY(final Object o) { return (double) snapshot().orientationY; }

	public static Double getOrientationZ(final Object o) { return (double) snapshot().orientationZ; }

	public static Double getMagneticX(final Object o) { return (double) snapshot().magneticX; }

	public static Double getMagneticY(final Object o) { return (double) snapshot().magneticY; }

	public static Double getMagneticZ(final Object o) { return (double) snapshot().magneticZ; }

	public static Double getLight(final Object o) { return (double) snapshot().light; }

	public static Double getProximity(final Object o) { return (double) snapshot().proximity; }

	public static Double getPressure(final Object o) { return (double) snapshot().pressure; }

	public static Double getTemperature(final Object o) { return (double) snapshot().temperature; }

	public static Double getHumidity(final Object o) { return (double) snapshot().humidity; }

	public static Double getBattery(final Object o) { return (double) snapshot().batteryLevel; }

	public static Integer getTimestamp(final Object o) { return (int) snapshot().timestamp; }

	public Object primGetSensorData(final IScope scope) {
		AndroidSensorBridge.Snapshot s = snapshot();
		return GamaListFactory.create(scope, Types.FLOAT, new double[] {
				s.accelerometerX, s.accelerometerY, s.accelerometerZ,
				s.gyroscopeX, s.gyroscopeY, s.gyroscopeZ,
				s.orientationX, s.orientationY, s.orientationZ,
				s.magneticX, s.magneticY, s.magneticZ,
				s.light, s.proximity, s.pressure, s.temperature, s.humidity, s.batteryLevel });
	}

	public Boolean primIsSensorAvailable(final IScope scope) {
		return snapshot().timestamp > 0;
	}

	public Boolean primInjectSensorData(final IScope scope) {
		Object values = scope.getArg("values", IType.LIST);
		if (!(values instanceof List)) {
			throw GamaRuntimeException.error("inject_sensor_data expects a list of 18 floats", scope);
		}
		List<?> list = (List<?>) values;
		if (list.size() != 18) {
			throw GamaRuntimeException.error(
					"inject_sensor_data expects a list of 18 floats (got " + list.size() + ")", scope);
		}
		float[] f = new float[18];
		for (int i = 0; i < 18; i++) {
			f[i] = ((Number) list.get(i)).floatValue();
		}
		new AndroidSensorBridge.Builder()
				.withTimestamp(System.currentTimeMillis())
				.withAccelerometer(f[0], f[1], f[2])
				.withGyroscope(f[3], f[4], f[5])
				.withOrientation(f[6], f[7], f[8])
				.withMagnetic(f[9], f[10], f[11])
				.withLight(f[12])
				.withProximity(f[13])
				.withPressure(f[14])
				.withTemperature(f[15])
				.withHumidity(f[16])
				.withBatteryLevel(f[17])
				.publish();
		return true;
	}
}
