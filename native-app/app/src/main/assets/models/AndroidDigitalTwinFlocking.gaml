/**
 * Name: Digital Twin - Flocking (Boids)
 * Author: GAMA Android
 * Description: A digital twin of your phone's motion, mirrored live into a Boids
 * 	flock. The phone is the physical asset and the virtual flock is its twin:
 * 	> TILT (accelerometer) steers the flock: the world tilts so the boids drift
 * 	  toward the low side, exactly like the real fluid in the device.
 * 	> GYROSCOPE adds a swirl - twisting the phone makes the flock spin.
 * 	> LIGHT sets the boids' energy: less ambient light, slower nervous flock.
 * 	> PROXIMITY spawns a virtual 'hand' that pushes the flock away as you get close.
 * 	Every sensor value is also logged on a live telemetry chart (the twin's data
 * 	stream), and the whole thing runs offline by injecting simulated sensor data
 * 	with inject_sensor_data when no physical sensor is available.
 * Tags: android, sensor, digital_twin, boids, flocking, interaction, telemetry
 */
model AndroidDigitalTwinFlocking

global {
	int nb_boids <- 40 min: 2 max: 120;
	int world_side <- 700;
	geometry shape <- rectangle(world_side, world_side);

	// live sensor readouts (the physical twin)
	float s_accel_x <- 0.0;
	float s_accel_y <- 0.0;
	float s_light <- 0.0;
	float s_gyro_z <- 0.0;
	float s_proximity <- 0.0;
	float s_temperature <- 0.0;
	bool sensor_ok <- false;

	// run in pure-simulation mode (no physical sensors): inject fake data
	bool simulate_sensors <- false;

	point wind <- {0.0, 0.0};
	float speed_factor <- 1.0;
	point hand_pos <- {world_side / 2, world_side / 2};
	float hand_radius <- 0.0;

	reflex read_phone {
		ask the_phone {
			if (simulate_sensors) {
				// a gentle Lissajous drift + light cycling over time - the twin's
				// simulated reality when the physical device is not in the loop
				int t <- int(time) mod 400;
				float ax <- sin(t / 40.0) * 5.0;
				float ay <- cos(t / 25.0) * 5.0;
				do inject_sensor_data values: [
					ax, ay, 9.8,
					0.0, 0.0, sin(t / 30.0) * 1.5,
					30.0, 30.0, 0.0,
					0.0, 0.0, 0.0,
					300.0 + 200.0 * cos(t / 50.0),
					5.0 - sin(t / 60.0) * 2.0,
					1013.0 + sin(t / 70.0),
					22.0 + sin(t / 55.0) * 3.0,
					50.0 + cos(t / 45.0) * 10.0,
					80.0 ];
			}
			s_accel_x <- accel_x;
			s_accel_y <- accel_y;
			s_light <- light;
			s_gyro_z <- gyro_z;
			s_proximity <- proximity;
			s_temperature <- temperature;
			sensor_ok <- is_sensor_available();
		}
		// accelerometer in m/s2 (gravity included) -> tilt direction & strength
		wind <- {(s_accel_x - 0.0) * 0.045, (s_accel_y - 0.0) * 0.045};
		// less light -> slower, more nervous boids
		speed_factor <- max([0.25, min([1.0, s_light / 300.0])]);
		hand_radius <- 0.0;
		if (s_proximity > 0.0) {
			// a real object is close: place the virtual hand between the boids centre
			// and the device - the closer, the bigger the repulsion radius
			hand_radius <- 60.0 + s_proximity * 12.0;
			hand_pos <- {rnd(world_side), rnd(world_side)};
		}
	}

	init {
		create the_phone;
		create boid number: nb_boids {
			location <- {rnd(world_side), rnd(world_side)};
			vel <- {rnd(-2.0, 2.0), rnd(-2.0, 2.0)};
		}
		write "Digital Twin (Boids) started. Tilt your device to steer the flock!";
	}
}

species the_phone skills: [android_sensor] {}

species boid {
	point vel <- {0.0, 0.0};
	float max_speed <- 4.0;

	point separation(boid n) {
		point d <- location - n.location;
		float dist <- sqrt(d.x * d.x + d.y * d.y);
		if (dist < 30.0 and dist > 0.001) {
			return d / max([dist, 1.0]);
		}
		return {0.0, 0.0};
	}
	point alignment(boid n) {
		return n.vel - vel;
	}
	point cohesion(boid n) {
		return (n.location - location) / 80.0;
	}

	reflex move {
		float sep_f <- 1.4;
		float ali_f <- 0.15;
		float coh_f <- 0.02;
		point steer <- {0.0, 0.0};
		// flocking rules among nearby neighbours
		loop n over: boid {
			if (n != self) {
				float dist <- sqrt((location.x - n.location.x) * (location.x - n.location.x) + (location.y - n.location.y) * (location.y - n.location.y));
				if (dist < 60.0) {
					steer <- steer + separation(n) * sep_f + alignment(n) * ali_f + cohesion(n) * coh_f;
				}
			}
		}
		// the twin's sensor-driven external forces
		steer <- steer + wind;
		if (s_gyro_z != 0.0) {
			float rot <- s_gyro_z * 0.02;
			steer <- {steer.x * cos(rot) - steer.y * sin(rot), steer.x * sin(rot) + steer.y * cos(rot)};
		}
		if (hand_radius > 0.0) {
			point d <- location - hand_pos;
			float dist <- sqrt(d.x * d.x + d.y * d.y);
			if (dist < hand_radius and dist > 0.001) {
				steer <- steer + (d / dist) * (hand_radius - dist) * 0.3;
			}
		}
		vel <- vel + steer;
		if (sqrt(vel.x * vel.x + vel.y * vel.y) > max_speed * speed_factor) {
			vel <- vel / sqrt(vel.x * vel.x + vel.y * vel.y) * (max_speed * speed_factor);
		}
		location <- location + vel;
		// wrap around the world (toroidal twin)
		if (location.x < 0.0) { location <- {location.x + world_side, location.y}; }
		if (location.x >= world_side) { location <- {location.x - world_side, location.y}; }
		if (location.y < 0.0) { location <- {location.x, location.y + world_side}; }
		if (location.y >= world_side) { location <- {location.x, location.y - world_side}; }
	}

	aspect default {
		draw circle(5.0) color: rgb(90, 180 + int(s_light) mod 60, 255) border: #white width: 1.0;
	}
	aspect marker {
		draw line([{0.0, 0.0}, vel * 6.0]) color: #red width: 2.0;
	}
}

experiment digital_twin type: gui {
	output {
		display flock_twin type: 2d background: #black {
			species boid aspect: default;
			graphics twin_overlay {
				if (hand_radius > 0.0) {
					draw circle(hand_radius) at: hand_pos color: #orange wireframe: true width: 2.0;
				}
				draw "DIGITAL TWIN - Boids" at: {20, 20} color: #skyblue;
				draw "Tilt: " + (s_accel_x with_precision 1) + "," + (s_accel_y with_precision 1) + " m/s2   Light: " + (s_light with_precision 0) + " lx" at: {20, 45} color: #white;
				draw "Sensors: " + (sensor_ok ? "live (physical phone)" : "simulated (inject_sensor_data)") at: {20, 70} color: #yellow;
			}
		}
		display telemetry type: 2d {
			species boid aspect: marker;
			chart "Flock Twin Telemetry" type: series background: #white {
				data "Wind X" value: wind.x * 20.0 color: #blue;
				data "Wind Y" value: wind.y * 20.0 color: #green;
				data "Light" value: s_light / 20.0 color: #orange;
			}
		}
	}
}
