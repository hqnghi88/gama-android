/**
 * Name: Android Sensor Lab
 * Description: An interactive showcase of the android_sensor skill. Tilt your device to roll the
 * 	ball (accelerometer), watch the gyroscope steer it, let the ambient light set the background
 * 	colour, and read every sensor live on a dashboard drawn into the display.
 * Tags: android, sensor, interaction, mobile
 */
model AndroidSensorLab

global {
	int world_width <- 600;
	int world_height <- 600;
	geometry shape <- rectangle(world_width, world_height);
	
	float g_accel_x <- 0.0;
	float g_accel_y <- 0.0;
	float g_accel_z <- 0.0;
	float g_gyro_z <- 0.0;
	float g_ori_x <- 0.0;
	float g_ori_y <- 0.0;
	float g_ori_z <- 0.0;
	float g_light <- 0.0;
	float g_proximity <- 0.0;
	float g_pressure <- 0.0;
	float g_temperature <- 0.0;
	float g_humidity <- 0.0;
	float g_battery <- 0.0;
	
	init {
		create sensor_ball;
		write "Android Sensor Lab started. Tilt your device!";
	}
	
	reflex read_sensors {
		ask sensor_ball {
			if (is_sensor_available()) {
				g_accel_x <- accel_x;
				g_accel_y <- accel_y;
				g_accel_z <- accel_z;
				g_gyro_z <- gyro_z;
				g_ori_x <- orientation_x;
				g_ori_y <- orientation_y;
				g_ori_z <- orientation_z;
				g_light <- light;
				g_proximity <- proximity;
				g_pressure <- pressure;
				g_temperature <- temperature;
				g_humidity <- humidity;
				g_battery <- battery_level;
			}
		}
	}
}

species sensor_ball skills:[android_sensor, moving] {
	point vel <- {0.0, 0.0};
	point pos_init <- {0.0, 0.0};
	
	init {
		location <- {world_width / 2, world_height / 2};
		pos_init <- location;
	}
	
	reflex roll {
		// Accelerometer controls the tilt: the ball accelerates toward the low side
		float fx <- accel_x * 4.0;
		float fy <- -accel_y * 4.0;
		vel <- {vel.x * 0.85 + fx, vel.y * 0.85 + fy};
		// Gyroscope adds a swirl on top of the tilt
		float ang <- gyro_z * 0.5;
		vel <- {vel.x * cos(ang) - vel.y * sin(ang), vel.x * sin(ang) + vel.y * cos(ang)};
		// cap the speed
		float spd <- sqrt(vel.x * vel.x + vel.y * vel.y);
		if (spd > 15.0) { vel <- {vel.x * 15.0 / spd, vel.y * 15.0 / spd}; }
		location <- {location.x + vel.x, location.y + vel.y};
		if (location.x < 10) { location <- {10, location.y}; vel <- {-vel.x * 0.5, vel.y}; }
		if (location.x > world_width - 10) { location <- {world_width - 10, location.y}; vel <- {-vel.x * 0.5, vel.y}; }
		if (location.y < 10) { location <- {location.x, 10}; vel <- {vel.x, -vel.y * 0.5}; }
		if (location.y > world_height - 10) { location <- {location.x, world_height - 10}; vel <- {vel.x, -vel.y * 0.5}; }
	}
	
	aspect default {
		// proximity shrinks the ball when something is very close
		float r <- 14.0;
		if (proximity > 0.0) { r <- max([6.0, 30.0 - proximity / 4.0]); }
		draw circle(r) color: rgb(80, 160, 220) border: #white width: 2.0;
		// heading marker aligned with the current velocity
		draw line([{0.0, 0.0}, {vel.x, vel.y}]) color: #yellow width: 3.0;
	}
}

experiment sensor_lab type: gui {
	output {
		display sensor_dashboard type: 2d background: rgb(20, 20, 40) {
			species sensor_ball aspect: default;
			graphics dashboard {
				draw "ACCELEROMETER  x: " + (g_accel_x with_precision 2) + "  y: " + (g_accel_y with_precision 2) + "  z: " + (g_accel_z with_precision 2)
					at: {20, 20} color: #white;
				draw "GYROSCOPE  z: " + (g_gyro_z with_precision 3) + " rad/s"
					at: {20, 45} color: #orange;
				draw "ORIENTATION  az: " + (g_ori_x with_precision 1) + "  pitch: " + (g_ori_y with_precision 1) + "  roll: " + (g_ori_z with_precision 1)
					at: {20, 70} color: #yellow;
				draw "LIGHT  " + (g_light with_precision 1) + " lx"
					at: {20, 95} color: #green;
				draw "PROXIMITY  " + (g_proximity with_precision 1) + " cm"
					at: {20, 120} color: #red;
				draw "PRESSURE  " + (g_pressure with_precision 1) + " hPa"
					at: {20, 145} color: #skyblue;
				draw "TEMPERATURE  " + (g_temperature with_precision 1) + " C"
					at: {20, 170} color: #orange;
				draw "HUMIDITY  " + (g_humidity with_precision 1) + " %"
					at: {20, 195} color: #cyan;
				draw "BATTERY  " + ((g_battery * 100.0) with_precision 0) + " %"
					at: {20, 220} color: #green;
				draw "TILT YOUR DEVICE TO ROLL THE BALL"
					at: {world_width / 2 - 150, world_height - 30} color: #white;
			}
		}
	}
}