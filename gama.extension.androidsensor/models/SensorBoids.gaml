/**
 * Name: Sensor Boids
 * Description: A classic flocking model whose target is steered by your device's sensors
 * 	(accelerometer tilt moves the goal, gyroscope z adds a swirl) instead of wandering on its
 * 	own. Thirty boids flock together and are attracted toward the sensor-driven target. When no
 * 	sensors are available (e.g. desktop), the target falls back to a gentle wander so the model
 * 	still runs without a device.
 * Tags: android, sensor, boids, flocking, interaction, mobile
 */
model SensorBoids

global {
	int world_width <- 700;
	int world_height <- 700;
	geometry shape <- rectangle(world_width, world_height);

	point target_pos <- {world_width / 2, world_height / 2};
	point target_vel <- {0.0, 0.0};
	string control_mode <- "WANDERING (no sensors)";

	init {
		create boid number: 30;
		create sensor_target;
		write "Sensor Boids started. Tilt your device to steer the flock target!";
	}

	// Global reflex so the target moves BEFORE the boids read target_pos each cycle.
	reflex drive_target {
		ask sensor_target {
			point cv <- {0.0, 0.0};
			if (is_sensor_available()) {
				// accelerometer tilt steers the target (screen x/y directions)
				float fx <- accel_x * 4.0;
				float fy <- -accel_y * 4.0;
				cv <- {v.x * 0.9 + fx, v.y * 0.9 + fy};
				// gyroscope z swirls the drift direction on top of the tilt
				float ang <- gyro_z * 0.4;
				cv <- {cv.x * cos(ang) - cv.y * sin(ang), cv.x * sin(ang) + cv.y * cos(ang)};
				control_mode <- "SENSOR CONTROL (tilt to steer)";
			} else {
				// desktop fallback: gentle wander keeps the model alive without sensor input
				float wx <- sin(float(cycle) * 0.02) * 0.8;
				float wy <- cos(float(cycle) * 0.017) * 0.8;
				cv <- {v.x * 0.9 + wx, v.y * 0.9 + wy};
				control_mode <- "WANDERING (no sensors)";
			}
			float spd <- sqrt(cv.x * cv.x + cv.y * cv.y);
			if (spd > 12.0) {
				cv <- {cv.x * 12.0 / spd, cv.y * 12.0 / spd};
			}
			location <- {self.location.x + cv.x, self.location.y + cv.y};
			if (location.x < 20) {
				location <- {20.0, location.y};
				cv <- {abs(cv.x) * 0.4, cv.y};
			}
			if (location.x > world_width - 20) {
				location <- {world_width - 20.0, location.y};
				cv <- {-abs(cv.x) * 0.4, cv.y};
			}
			if (location.y < 20) {
				location <- {location.x, 20.0};
				cv <- {cv.x, abs(cv.y) * 0.4};
			}
			if (location.y > world_height - 20) {
				location <- {location.x, world_height - 20.0};
				cv <- {cv.x, -abs(cv.y) * 0.4};
			}
			v <- cv;
			target_pos <- self.location;
			target_vel <- cv;
		}
	}
}

species sensor_target skills: [android_sensor, moving] {
	point v <- {0.0, 0.0};

	init {
		location <- target_pos;
	}

	aspect default {
		draw circle(16) color: rgb(235, 90, 60) border: #white width: 2.0;
		draw circle(28) color: rgb(150, 45, 35) border: #white width: 1.5;
		draw line([{0.0, 0.0}, {v * 4.0}]) color: #yellow width: 2.5;
	}
}

species boid skills: [moving] {
	point v <- {0.0, 0.0};
	float max_speed <- 6.0;

	// flock tuning: separation / cohesion / alignment / target attraction
	float w_sep <- 1.6;
	float w_coh <- 0.8;
	float w_ali <- 1.0;
	float w_tgt <- 2.0;

	init {
		location <- {rnd(world_width), rnd(world_height)};
		float a <- rnd(360);
		v <- {cos(a) * 2.0, sin(a) * 2.0};
	}

	reflex fly {
		point steer <- {0.0, 0.0};

		list<boid> mates <- (boids where (each != self)
			and ((self.location) distance_to (each.location) < 45.0));

		if (mates not_empty) {
			point sep <- {0.0, 0.0};
			float cx <- 0.0;
			float cy <- 0.0;
			float ax <- 0.0;
			float ay <- 0.0;
			for m in mates {
				sep <- sep + (self.location - m.location);
				cx <- cx + m.location.x;
				cy <- cy + m.location.y;
				ax <- ax + m.v.x;
				ay <- ay + m.v.y;
			}
			float n <- float(length(mates));
			cx <- cx / n;
			cy <- cy / n;
			ax <- ax / n;
			ay <- ay / n;
			point coh <- {cx - self.location.x, cy - self.location.y};
			point ali <- {ax - v.x, ay - v.y};
			steer <- sep * w_sep + coh * w_coh + ali * w_ali;
		}

		// attraction toward the sensor-driven target (springs in near the goal)
		point toward <- {target_pos.x - self.location.x, target_pos.y - self.location.y};
		float dist <- sqrt(toward.x * toward.x + toward.y * toward.y);
		if (dist > 1.0) {
			if (dist < 60.0) {
				toward <- {toward.x * dist / 60.0, toward.y * dist / 60.0};
			}
			steer <- steer + (toward * w_tgt);
		}

		v <- v + steer;
		float spd <- sqrt(v.x * v.x + v.y * v.y);
		if (spd > max_speed) {
			v <- {v.x * max_speed / spd, v.y * max_speed / spd};
		}
		location <- {self.location.x + v.x, self.location.y + v.y};

		if (location.x < 8) {
			location <- {8.0, location.y};
			v <- {abs(v.x) * 0.4, v.y};
		}
		if (location.x > world_width - 8) {
			location <- {world_width - 8.0, location.y};
			v <- {-abs(v.x) * 0.4, v.y};
		}
		if (location.y < 8) {
			location <- {location.x, 8.0};
			v <- {v.x, abs(v.y) * 0.4};
		}
		if (location.y > world_height - 8) {
			location <- {location.x, world_height - 8.0};
			v <- {v.x, -abs(v.y) * 0.4};
		}
	}

	aspect default {
		point dir <- v;
		float len <- sqrt(dir.x * dir.x + dir.y * dir.y);
		if (len > 0.1) {
			dir <- {dir.x / len, dir.y / len};
			point left <- {-dir.y, dir.x};
			point nose <- self.location + dir * 7.0;
			point lw <- self.location + left * 4.0 - dir * 5.0;
			point rw <- self.location - left * 4.0 - dir * 5.0;
			draw triangle([nose, lw, rw]) color: rgb(90, 180, 235) border: #white width: 1.0;
		} else {
			draw circle(4) color: rgb(90, 180, 235);
		}
	}
}

experiment sensor_boids type: gui {
	output {
		display boids_display type: 2d background: rgb(20, 20, 40) {
			species boid aspect: default;
			species sensor_target aspect: default;
			graphics status {
				draw "MODE: " + control_mode
					at: {20, 20} color: #white;
				draw "TARGET  x: " + (target_pos.x with_precision 1) + "  y: " + (target_pos.y with_precision 1)
					at: {20, 45} color: #orange;
				draw "BOIDS " + (length(boids))
					at: {20, 70} color: #skyblue;
				draw "TILT YOUR DEVICE TO STEER THE TARGET"
					at: {world_width / 2 - 160, world_height - 30} color: #white;
			}
		}
	}
}