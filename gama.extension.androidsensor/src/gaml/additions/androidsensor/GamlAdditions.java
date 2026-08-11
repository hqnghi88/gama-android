package gaml.additions.androidsensor;

import static gama.annotations.constants.IKeyword.*;
import gama.api.additions.AbstractGamlAdditions;
import gama.api.additions.GamaHelper;
import gama.extension.androidsensor.AndroidSensorSkill;

@SuppressWarnings({ "rawtypes", "unchecked", "unused" })
public class GamlAdditions extends AbstractGamlAdditions {

	public void initialize() throws SecurityException, NoSuchMethodException {
		initializeVars();
		initializeAction();
		initializeSkill();
	}

	public void initializeVars() {
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "accel_x")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getAccelX(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "accel_y")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getAccelY(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "accel_z")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getAccelZ(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "gyro_x")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getGyroX(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "gyro_y")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getGyroY(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "gyro_z")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getGyroZ(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "orientation_x")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getOrientationX(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "orientation_y")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getOrientationY(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "orientation_z")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getOrientationZ(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "magnetic_x")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getMagneticX(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "magnetic_y")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getMagneticY(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "magnetic_z")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getMagneticZ(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "light")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getLight(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "proximity")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getProximity(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "pressure")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getPressure(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "temperature")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getTemperature(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "humidity")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getHumidity(a), null, null);
		_var(AndroidSensorSkill.class, desc(2, S("type", "2", "name", "battery_level")),
				(s, a, t, v) -> t == null ? 0d : ((AndroidSensorSkill) t).getBattery(a), null, null);
		_var(AndroidSensorSkill.class, desc(1, S("type", "1", "name", "sensor_timestamp")),
				(s, a, t, v) -> t == null ? 0 : ((AndroidSensorSkill) t).getTimestamp(a), null, null);
	}

	public void initializeAction() throws SecurityException, NoSuchMethodException {
		_action(new GamaHelper("get_sensor_data", AndroidSensorSkill.class,
				(s, a, t, v) -> ((AndroidSensorSkill) t).primGetSensorData(s)),
				desc(PRIM, new Children(), NAME, "get_sensor_data", TYPE, Ti(O), VIRTUAL, FALSE),
				AndroidSensorSkill.class.getMethod("primGetSensorData", SC));
		_action(new GamaHelper("is_sensor_available", AndroidSensorSkill.class,
				(s, a, t, v) -> ((AndroidSensorSkill) t).primIsSensorAvailable(s)),
				desc(PRIM, new Children(), NAME, "is_sensor_available", TYPE, Ti(B), VIRTUAL, FALSE),
				AndroidSensorSkill.class.getMethod("primIsSensorAvailable", SC));
		_action(new GamaHelper("inject_sensor_data", AndroidSensorSkill.class,
				(s, a, t, v) -> ((AndroidSensorSkill) t).primInjectSensorData(s)),
				desc(PRIM, new Children(_arg("values", 5, T)), NAME, "inject_sensor_data", TYPE, Ti(B), VIRTUAL, FALSE),
				AndroidSensorSkill.class.getMethod("primInjectSensorData", SC));
	}

	public void initializeSkill() {
		_skill("android_sensor", AndroidSensorSkill.class, AS);
	}
}
