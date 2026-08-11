/*******************************************************************************************************
 *
 * AndroidSensorBridge.java, in gama.extension.androidsensor, is part of the source code of the GAMA modeling and
 * simulation platform.
 *
 * (c) 2007-2025 UMI 209 UMMISCO IRD/SU & Partners
 *
 ********************************************************************************************************/
package gama.extension.androidsensor;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The class AndroidSensorBridge. A pure-Java static bridge between the Android sensor framework and the GAMA
 * simulation. The native Android application registers sensor listeners and pushes the last known values here; the
 * GAML skill {@link AndroidSensorSkill} reads from this bridge. Kept free of any android.* import so the plugin
 * remains fully desktop-compilable, like any other GAMA extension.
 */
public class AndroidSensorBridge {

	/** A read-only snapshot of the latest known sensor values. */
	public static final class Snapshot {
		public final long timestamp;
		public final float accelerometerX;
		public final float accelerometerY;
		public final float accelerometerZ;
		public final float gyroscopeX;
		public final float gyroscopeY;
		public final float gyroscopeZ;
		public final float orientationX;
		public final float orientationY;
		public final float orientationZ;
		public final float magneticX;
		public final float magneticY;
		public final float magneticZ;
		public final float light;
		public final float proximity;
		public final float pressure;
		public final float temperature;
		public final float humidity;
		public final float batteryLevel;

		Snapshot(final long timestamp, final float accX, final float accY, final float accZ, final float gyrX,
				final float gyrY, final float gyrZ, final float oriX, final float oriY, final float oriZ,
				final float magX, final float magY, final float magZ, final float light, final float proximity,
				final float pressure, final float temperature, final float humidity, final float batteryLevel) {
			this.timestamp = timestamp;
			this.accelerometerX = accX;
			this.accelerometerY = accY;
			this.accelerometerZ = accZ;
			this.gyroscopeX = gyrX;
			this.gyroscopeY = gyrY;
			this.gyroscopeZ = gyrZ;
			this.orientationX = oriX;
			this.orientationY = oriY;
			this.orientationZ = oriZ;
			this.magneticX = magX;
			this.magneticY = magY;
			this.magneticZ = magZ;
			this.light = light;
			this.proximity = proximity;
			this.pressure = pressure;
			this.temperature = temperature;
			this.humidity = humidity;
			this.batteryLevel = batteryLevel;
		}
	}

	/** The current snapshot. */
	private static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>(
			new Snapshot(0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, Float.NaN, 0f, Float.NaN, Float.NaN, 0f));

	private AndroidSensorBridge() {}

	/**
	 * Returns the latest sensor snapshot. Never null.
	 *
	 * @return the current snapshot
	 */
	public static Snapshot getSnapshot() { return CURRENT.get(); }

	/** A builder used by the Android side to publish a new snapshot. */
	public static final class Builder {
		private long timestamp;
		private float accX, accY, accZ;
		private float gyrX, gyrY, gyrZ;
		private float oriX, oriY, oriZ;
		private float magX, magY, magZ;
		private float light, proximity, pressure, temperature, humidity, batteryLevel;

		public Builder withTimestamp(final long t) { timestamp = t; return this; }

		public Builder withAccelerometer(final float x, final float y, final float z) {
			accX = x; accY = y; accZ = z; return this;
		}

		public Builder withGyroscope(final float x, final float y, final float z) {
			gyrX = x; gyrY = y; gyrZ = z; return this;
		}

		public Builder withOrientation(final float x, final float y, final float z) {
			oriX = x; oriY = y; oriZ = z; return this;
		}

		public Builder withMagnetic(final float x, final float y, final float z) {
			magX = x; magY = y; magZ = z; return this;
		}

		public Builder withLight(final float v) { light = v; return this; }

		public Builder withProximity(final float v) { proximity = v; return this; }

		public Builder withPressure(final float v) { pressure = v; return this; }

		public Builder withTemperature(final float v) { temperature = v; return this; }

		public Builder withHumidity(final float v) { humidity = v; return this; }

		public Builder withBatteryLevel(final float v) { batteryLevel = v; return this; }

		public void publish() {
			CURRENT.set(new Snapshot(timestamp, accX, accY, accZ, gyrX, gyrY, gyrZ, oriX, oriY, oriZ, magX, magY, magZ,
					light, proximity, pressure, temperature, humidity, batteryLevel));
		}
	}
}
