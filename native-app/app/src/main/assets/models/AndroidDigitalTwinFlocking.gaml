/**
 * Name: Digital Twin - 3D Drone Pilot
 * Author: GAMA Android
 * Description: Your phone is the flight controller, and a 3D drone over a virtual city is its
 * 	digital twin - the drone mirrors how you hold and move the phone, in real time:
 * 	> ACCELEROMETER is the joystick: tilt the phone forward / back / left / right to fly the
 * 	  drone across a 3D cityscape, and the drone body pitches and rolls to match your hand.
 * 	> GYROSCOPE is the rudder: twist the phone to yaw (rotate) the drone.
 * 	> LIGHT is the throttle: more ambient light, more engine power and higher speed.
 * 	> PROXIMITY is the altimeter guard: as an object gets close, the drone climbs to avoid it.
 * 	> BATTERY is the fuel budget: the drone flies slower and hovers lower as the battery empties.
 * 	Runs offline by injecting simulated sensor data with inject_sensor_data when no physical
 * 	sensor is available - swipe the air to fly the demo.
 * Tags: android, sensor, digital_twin, 3d, drone, flight, interaction, city, telemetry
 */
model AndroidDigitalTwinFlocking

global {
	int world_side <- 900;
	geometry shape <- rectangle(world_side, world_side);
	point drone_start <- {world_side / 2, world_side / 2, 120.0};
	int nb_obstacles <- 16;

	// --- the physical twin (phone as flight controller) ---
	float s_accel_x <- 0.0;
	float s_accel_y <- 0.0;
	float s_gyro_z <- 0.0;
	float s_light <- 0.0;
	float s_proximity <- 0.0;
	float s_battery <- 0.0;
	bool sensor_ok <- false;
	bool simulate_sensors <- true;

	// --- drone state ---
	point drone_loc <- drone_start;
	float drone_alt <- 120.0;
	float drone_yaw <- 0.0;
	point d_vel <- {0.0, 0.0};
	float alt_vel <- 0.0;
	float throttle <- 1.0;
	float roll <- 0.0;     // mirrored drone roll (deg, shows on HUD)
	float pitch <- 0.0;    // mirrored drone pitch (deg, shows on HUD)

	reflex read_phone {
		ask the_phone {
			if (simulate_sensors) {
				int t <- int(time) mod 600;
				float ax <- sin(t / 30.0) * 6.0;
				float ay <- cos(t / 38.0) * 5.0;
				do inject_sensor_data values: [
					ax, ay, 9.8,
					0.0, 0.0, sin(t / 42.0) * 1.2,
					220.0 - sin(t / 50.0) * 60.0, -ay * 8.0, -ax * 8.0,
					0.0, 0.0, 0.0,
					420.0 + 240.0 * cos(t / 55.0),
					0.5 + sin(t / 25.0) * 0.4,
					1012.0, 21.0 + sin(t / 60.0) * 3.0,
					48.0, 0.9 ];
			}
			s_accel_x <- accel_x;
			s_accel_y <- accel_y;
			s_gyro_z <- gyro_z;
			s_light <- light;
			s_proximity <- proximity;
			s_battery <- battery_level;
			sensor_ok <- is_sensor_available();
		}

		// THROTTLE: ambient light = engine power
		throttle <- max([0.25, min([1.5, s_light / 400.0])]);

		// JOYSTICK: accelerometer tilt = lateral thrust; battery saps power as it empties
		float power <- throttle * (0.3 + s_battery);
		d_vel <- {d_vel.x * 0.9 + s_accel_x * -0.22 * power, d_vel.y * 0.9 + s_accel_y * -0.22 * power};
		drone_loc <- drone_loc + d_vel;

		// ATTITUDE mirror: the drone's roll/pitch follows the physical tilt
		roll <- (-s_accel_x) * 2.6;
		pitch <- (-s_accel_y) * 2.6;

		// ALTITUDE: proximity guard climbs, plus gentle battery-dependent hover drift
		alt_vel <- alt_vel * 0.85 + (s_proximity * 1.2 - 0.1 * (1.0 - s_battery));
		drone_alt <- drone_alt + alt_vel;
		if (drone_alt < 30.0) { drone_alt <- 30.0; alt_vel <- max([0.0, alt_vel]); }
		if (drone_alt > 320.0) { drone_alt <- 320.0; }

		// YAW: gyroscope spins the drone upside only (kept simple, Z-axis)
		drone_yaw <- drone_yaw + s_gyro_z * 14.0;

		// keep the drone inside the city bounds
		if (drone_loc.x < 20.0) { drone_loc <- {20.0, drone_loc.y}; }
		if (drone_loc.x > world_side - 20.0) { drone_loc <- {world_side - 20.0, drone_loc.y}; }
		if (drone_loc.y < 20.0) { drone_loc <- {drone_loc.x, 20.0}; }
		if (drone_loc.y > world_side - 20.0) { drone_loc <- {drone_loc.x, world_side - 20.0}; }
	}

	init {
		create the_phone;
		create obstacle number: nb_obstacles;
		create drone;
		write "Digital Twin (3D Drone) started. Tilt and twist your phone to fly!";
	}
}

species the_phone skills: [android_sensor] {}

species obstacle {
	float h <- 60.0 + rnd(200.0);
	float w <- 40.0 + rnd(30.0);
	int hue <- int(rnd(0, 80));

	init {
		location <- {rnd(80.0, world_side - 80.0), rnd(80.0, world_side - 80.0)};
		if ((location distance_to drone_start) < 130.0) {
			location <- {rnd(120.0, world_side - 120.0), rnd(120.0, world_side - 120.0)};
		}
	}

	aspect twin {
		int face_r <- 120 + hue;
		draw box({w, w, h}) at: {location.x, location.y, h / 2.0}
			color: rgb(face_r, 110 + hue, 130) border: rgb(60, 55, 70);
	}
}

species drone {
	aspect twin {
		// body drifts with the tilt (the digital-twin mirror of the phone)
		float bx <- drone_loc.x + roll * 0.6;
		float by <- drone_loc.y + pitch * 0.6;
		draw box({16.0, 16.0, 8.0}) at: {bx, by, drone_alt}
			color: rgb(112, 128, 144) border: rgb(255, 255, 255);
		// rotor disc, its radius = throttle, spinning with the yaw
		float rotor_r <- 26.0 * throttle;
		draw cylinder(rotor_r, 2.5) at: {bx, by, drone_alt + 5.0}
			rotate: drone_yaw color: rgb(80, 190, 230);
		// motion trail behind the drone
		draw line([{bx, by, drone_alt}, {bx - d_vel.x * 2.2, by - d_vel.y * 2.2, drone_alt}])
			color: rgb(255, 255, 200);
	}
}

experiment drone_twin type: gui {
	output {
		display flight type: 3d background: rgb(16, 22, 44) {
			species obstacle aspect: twin;
			species drone aspect: twin;
			graphics ground {
				draw square(world_side) at: {world_side / 2, world_side / 2, 0.0}
					color: rgb(38, 60, 44);
			}
			graphics overlay {
				draw "3D DRONE DIGITAL TWIN" at: {20, 60} color: rgb(255, 255, 255);
				draw "Roll: " + (roll with_precision 1) + " deg   Pitch: " + (pitch with_precision 1) + " deg   Yaw: " + (drone_yaw with_precision 0) + " deg" at: {20, 85} color: rgb(255, 170, 60);
				draw "Throttle: " + (throttle with_precision 2) + "   Alt: " + int(drone_alt) + "   Sensors: " + (sensor_ok ? "live" : "simulated") at: {20, 110} color: rgb(140, 255, 160);
			}
		}
		display telemetry type: 2d {
			chart "Drone Twin Telemetry" type: series background: rgb(255, 255, 255) {
				data "Roll (deg)" value: roll * 5.0 color: rgb(220, 0, 0);
				data "Pitch (deg)" value: pitch * 5.0 color: rgb(255, 140, 0);
				data "Yaw (deg)" value: drone_yaw / 4.0 color: rgb(0, 0, 220);
				data "Throttle" value: throttle * 30.0 color: rgb(0, 160, 0);
			}
		}
	}
}
