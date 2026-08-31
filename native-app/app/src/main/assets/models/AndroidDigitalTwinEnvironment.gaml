/**
 * Name: Digital Twin - 3D City
 * Author: GAMA Android
 * Description: A living 3D city that is the digital twin of your phone. The phone is the
 * 	physical twin of a whole urban environment, and every sensor is mirrored onto the city:
 * 	> LIGHT drives the day/night cycle: ambient light moves the sun across a 3D sky and switches
 * 	  street lamps and building windows on as night falls.
 * 	> TEMPERATURE / HUMIDITY / PRESSURE drive the live weather: falling pressure brings rain, and
 * 	  heat tints the skyline.
 * 	> ACCELEROMETER moves a control buoy that tracks your hand around the city, and the GYROSCOPE
 * 	  slides it sideways - the buoy is the 3D mirror of your phone's own orientation.
 * 	> PROXIMITY launches a 3D drone that dives over the skyline the closer an object gets.
 * 	> BATTERY is the city's power grid: as the battery empties, street lamps and windows go out.
 * 	Runs offline by injecting simulated sensor data with inject_sensor_data when no physical
 * 	sensor is available.
 * Tags: android, sensor, digital_twin, 3d, city, environment, climate, daynight, grid
 */
model AndroidDigitalTwinEnvironment

global {
	int world_side <- 900;
	geometry shape <- rectangle(world_side, world_side);
	int nb_buildings <- 34;

	// --- the physical twin (phone as the environment) ---
	float s_accel_x <- 0.0;
	float s_accel_y <- 0.0;
	float s_gyro_z <- 0.0;
	float s_light <- 0.0;
	float s_proximity <- 0.0;
	float s_pressure <- 0.0;
	float s_temperature <- 0.0;
	float s_humidity <- 0.0;
	float s_battery <- 0.0;
	bool sensor_ok <- false;
	bool simulate_sensors <- true;

	// --- derived twin state ---
	float sun_angle <- 0.0;          // radians along the sky arc
	float night_factor <- 0.0;       // 0 = day, 1 = deep night
	float rain_intensity <- 0.0;
	float grid_level <- 1.0;         // battery-driven power grid (0..1)
	float buoy_x <- world_side / 2;  // control buoy, mirror of the phone
	float buoy_y <- world_side / 2;
	float sun_r <- 215.0;            // sun color channel (precomputed)
	float sun_g <- 210.0;
	int drone_active <- 0;

	reflex read_twin {
		ask the_twin {
			if (simulate_sensors) {
				int t <- int(time) mod 600;
				do inject_sensor_data values: [
					sin(t / 45.0) * 6.0, cos(t / 35.0) * 6.0, 9.8,
					0.0, 0.0, sin(t / 60.0) * 1.2,
					60.0, 60.0, 0.0,
					0.0, 0.0, 0.0,
					350.0 + 300.0 * cos(t / 90.0),
					2.5 + sin(t / 30.0) * 2.0,
					1007.0 - cos(t / 55.0) * 3.0,
					20.0 + cos(t / 70.0) * 6.0,
					45.0 + sin(t / 45.0) * 20.0,
					0.8 ];
			}
			s_accel_x <- accel_x;
			s_accel_y <- accel_y;
			s_gyro_z <- gyro_z;
			s_light <- light;
			s_proximity <- proximity;
			s_pressure <- pressure;
			s_temperature <- temperature;
			s_humidity <- humidity;
			s_battery <- battery_level;
			sensor_ok <- is_sensor_available();
		}

		// DAY/NIGHT: ambient light moves the sun across the sky arc
		sun_angle <- (max([0.0, min([1.0, s_light / 900.0])])) * 3.14159;
		night_factor <- 1.0 - min([1.0, max([0.0, (s_light - 20.0) / 550.0])]);
		sun_r <- 255.0;
		sun_g <- 215.0 - night_factor * 165.0;

		// WEATHER: falling pressure + humidity -> rain
		rain_intensity <- max([0.0, (1007.0 - s_pressure) * 0.4 + (s_humidity - 30.0) * 0.004]);

		// POWER GRID: battery is the city's energy budget
		grid_level <- max([0.05, s_battery]);

		// BUOY: the accelerometer steers it like a joystick, the gyroscope slides it sideways
		buoy_x <- buoy_x + s_accel_x * 0.9 - s_gyro_z * 3.0;
		buoy_y <- buoy_y + s_accel_y * 0.9;
		if (buoy_x < 40.0) { buoy_x <- 40.0; }
		if (buoy_x > world_side - 40.0) { buoy_x <- world_side - 40.0; }
		if (buoy_y < 40.0) { buoy_y <- 40.0; }
		if (buoy_y > world_side - 40.0) { buoy_y <- world_side - 40.0; }

		// PROXIMITY drone: a close object triggers a dive over the skyline
		if (s_proximity > 0.0) { drone_active <- 1; } else { drone_active <- 0; }
	}

	init {
		create the_twin;
		create building number: nb_buildings;
		create drone;
		create buoy;
		write "Digital Twin (3D City) started. Move the phone to steer the buoy!";
	}
}

species the_twin skills: [android_sensor] {}

species building {
	float h <- 40.0 + rnd(230.0);
	float w <- 34.0 + rnd(32.0);
	int hue <- int(rnd(0, 70));

	init {
		location <- {rnd(60.0, world_side - 60.0), rnd(60.0, world_side - 60.0)};
	}

	aspect twin {
		int face_r <- 150 + hue;
		int face_g <- 130 + hue;
		draw box({w, w, h}) at: {location.x, location.y, h / 2.0}
			color: rgb(face_r, face_g, 150 - int(night_factor * 90.0))
			border: rgb(70, 60, 80);
		if (night_factor > 0.35 and grid_level > 0.45) {
			draw box({w * 0.45, w * 0.45, h * 0.5}) at: {location.x, location.y, h * 0.72}
				color: rgb(255, 200, 80);
		}
	}
}

species buoy {
	aspect twin {
		point b <- {buoy_x, buoy_y, 0.0};
		draw box({16.0, 16.0, 60.0}) at: {b.x, b.y, 30.0}
			color: rgb(210, 90, 60) border: rgb(255, 255, 255);
		draw sphere(22.0) at: {b.x, b.y, 64.0}
			color: rgb(255, 140, 60);
	}
}

species drone {
	point dloc <- {0.0, 0.0};

	reflex fly {
		if (drone_active = 1) {
			dloc <- {rnd(-350.0, 350.0), rnd(-350.0, 350.0)};
		} else {
			dloc <- {0.0, 0.0};
		}
	}

	aspect body {
		if (drone_active = 1) {
			draw box({14.0, 14.0, 8.0}) at: {dloc.x, dloc.y, 200.0}
				color: rgb(112, 128, 144) border: rgb(255, 255, 255);
			draw cylinder(30.0, 2.0) at: {dloc.x, dloc.y, 204.0}
				color: rgb(80, 190, 230);
		}
	}
}

experiment city_twin type: gui {
	output {
		display city type: 3d background: rgb(12, 16, 40) {
			species building aspect: twin;
			species buoy aspect: twin;
			species drone aspect: body;
			graphics sun {
				float sx <- world_side / 2.0 + cos(sun_angle) * 340.0;
				float sy <- world_side / 2.0 + sin(sun_angle) * 260.0;
				draw sphere(36.0) at: {sx, sy, 320.0}
					color: rgb(int(sun_r), int(sun_g), 70);
			}
			graphics rain {
				if (rain_intensity > 0.01) {
					loop k from: 0 to: 46 {
						point p <- {rnd(world_side), rnd(world_side)};
						draw line([{p.x, p.y, 300.0}, {p.x, p.y - 10.0, 250.0}])
							color: rgb(120, 180, 255);
					}
				}
			}
			graphics lamps {
				if (night_factor > 0.3) {
					loop b over: building {
						draw sphere(9.0) at: {b.location.x, b.location.y, 10.0}
							color: rgb(int(130.0 * grid_level), int(95.0 * grid_level), 40);
					}
				}
			}
			graphics overlay {
				draw "3D CITY DIGITAL TWIN" at: {20, 680} color: rgb(255, 255, 255);
				draw "Sun: " + (sun_angle with_precision 2) + " rad   Night: " + (night_factor with_precision 2) + "   Rain: " + (rain_intensity with_precision 2) at: {20, 705} color: rgb(120, 190, 255);
				draw "Buoy: " + int(buoy_x) + "," + int(buoy_y) + "   Grid (battery): " + int(grid_level * 100.0) + "%" at: {20, 730} color: rgb(255, 170, 60);
				draw "Sensors: " + (sensor_ok ? "live" : "simulated (inject_sensor_data)") at: {20, 755} color: rgb(140, 255, 160);
			}
		}
		display weather type: 2d {
			chart "Sun / Rain / Grid" type: series background: rgb(255, 255, 255) {
				data "Sun" value: sun_angle * 60.0 color: rgb(255, 140, 0);
				data "Rain" value: rain_intensity * 25.0 color: rgb(0, 90, 255);
				data "Night" value: night_factor * 40.0 color: rgb(60, 0, 180);
				data "Grid %" value: grid_level * 100.0 color: rgb(0, 150, 0);
			}
		}
	}
}
