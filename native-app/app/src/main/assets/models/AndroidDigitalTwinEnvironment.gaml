/**
 * Name: Digital Twin - Environment Station
 * Author: GAMA Android
 * Description: An environmental digital twin of a place, fed live by the sensors
 * 	of your phone (the physical twin of a remote sensing station):
 * 	> CLIMATE / CITY: temperature, humidity, pressure and light are streamed onto
 * 	  live telemetry charts, and ambient light drives the virtual day-night cycle.
 * 	> FLOOD / WATER: device pitch (accelerometer) and a pressure-derived "rain"
 * 	  proxy control the inflow of a virtual reservoir whose water level rises and
 * 	  overflows - a hydrological twin driven by the device's microclimate.
 * 	> TRAFFIC / ACTIVITY: proximity is used as an occupancy proxy, and the battery
 * 	  level acts as the station's power/energy budget.
 * 	Runs offline by injecting simulated sensor data with inject_sensor_data when
 * 	no physical sensor is available, so the twin can be demonstrated anywhere.
 * Tags: android, sensor, digital_twin, environment, climate, flood, traffic, telemetry
 */
model AndroidDigitalTwinEnvironment

global {
	int world_side <- 800;
	geometry shape <- rectangle(world_side, world_side);

	// --- the physical twin (phone as sensing station) ---
	float s_accel_y <- 0.0;
	float s_light <- 0.0;
	float s_proximity <- 0.0;
	float s_pressure <- 0.0;
	float s_temperature <- 0.0;
	float s_humidity <- 0.0;
	float s_battery <- 0.0;
	bool sensor_ok <- false;
	bool simulate_sensors <- true;

	// --- derived twin state ---
	float rain_intensity <- 0.0;      // flood/water driver
	float activity <- 0.0;            // traffic/occupancy proxy
	float water_level <- 120.0;       // virtual reservoir level
	float water_capacity <- 400.0;
	int active_agents <- 10;          // city traffic count
	int nb_cars <- 60;

	reflex read_station {
		ask the_station {
			if (simulate_sensors) {
				int t <- int(time) mod 500;
				do inject_sensor_data values: [
					0.0, sin(t / 60.0) * 6.0, 9.8,
					0.0, 0.0, 0.0,
					0.0, 40.0 + sin(t / 80.0) * 20.0, 0.0,
					0.0, 0.0, 0.0,
					400.0 + 300.0 * cos(t / 90.0),
					3.0 + sin(t / 20.0) * 2.0,
					1008.0 - sin(t / 50.0) * 4.0,
					20.0 + cos(t / 70.0) * 5.0,
					45.0 + sin(t / 40.0) * 15.0,
					70.0 ];
			}
			s_accel_y <- accel_y;
			s_light <- light;
			s_proximity <- proximity;
			s_pressure <- pressure;
			s_temperature <- temperature;
			s_humidity <- humidity;
			s_battery <- battery_level;
			sensor_ok <- is_sensor_available();
		}
		// FLOOD: rain from device pitch (tilt forward = more rain) + falling pressure
		rain_intensity <- max([0.0, (abs(s_accel_y) / 9.8) + (1008.0 - s_pressure) * 0.3]);
		water_level <- water_level + rain_intensity * 2.0 - 0.4;
		if (water_level < 0.0) { water_level <- 0.0; }
		if (water_level > water_capacity) { water_level <- water_capacity; }
		// TRAFFIC: proximity ~ occupancy, light ~ activity hours
		activity <- min([1.0, (s_proximity / 6.0) + (s_light / 1000.0)]);
		active_agents <- 10 + int(activity * 50.0);
		// only the configured number of cars are "active" (traffic twin)
		list<agent_car> cars <- agent_car;
		loop i from: 0 to: length(cars) - 1 {
			cars[i].active <- (i < active_agents);
		}
	}

	init {
		create the_station;
		create agent_car number: nb_cars;
		write "Digital Twin (Environment Station) started.";
	}
}

species the_station skills: [android_sensor] {}

species agent_car {
	point target <- {rnd(700.0), rnd(700.0)};
	bool active <- false;

	reflex circulate {
		location <- location + (target - location) / ((location distance_to target) + 1.0);
		if ((location distance_to target) < 5.0) { target <- {rnd(700.0), rnd(700.0)}; }
		// battery below 20% slows every car (energy budget of the station)
		if (s_battery < 0.2) { location <- location; }
	}

	aspect dot {
		if (active) {
			draw circle(6.0) color: rgb(int(180 - s_temperature * 2.0), 160, 220) border: #white width: 1.0;
		}
	}
}

experiment environment_twin type: gui {
	output {
		display station type: 2d background: rgb(30, 34, 60) {
			species agent_car aspect: dot;
			graphics reservoir {
				// the basin
				draw rectangle(world_side, 40.0) at: {0, 0} color: rgb(90, 60, 30);
				// the animated water (flood/water twin)
				float w <- min([water_level, world_side]);
				draw rectangle(w, 24.0) at: {0, 8.0} color: rgb(40, int(120 + rain_intensity * 100.0), 220);
			}
			graphics overlay {
				draw "ENVIRONMENT DIGITAL TWIN" at: {20, 650} color: #white;
				draw "Station: " + (sensor_ok ? "physical phone" : "simulated (inject_sensor_data)") at: {20, 675} color: #yellow;
				draw "Rain: " + (rain_intensity with_precision 2) + "  Water: " + int(water_level) + "/" + int(water_capacity) at: {20, 700} color: #lightblue;
				draw "Activity: " + (activity with_precision 2) + "  Active cars: " + active_agents + "/" + nb_cars at: {20, 725} color: #orange;
				draw "Battery (station power): " + int(s_battery * 100.0) + "%" at: {20, 750} color: #lightgreen;
			}
		}
		display climate type: 2d {
			chart "Station Climate Telemetry" type: series background: #white {
				data "Temperature (C)" value: (s_temperature - 10.0) * 5.0 color: #red;
				data "Humidity (%)" value: s_humidity * 0.5 color: #green;
				data "Pressure-1000 (hPa)" value: (s_pressure - 1000.0) * 10.0 color: #blue;
			}
		}
	}
}
