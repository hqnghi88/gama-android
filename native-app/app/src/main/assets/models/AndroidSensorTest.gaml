model AndroidSensorTest

global {
	int sensor_agent_nb <- 3;

	init {
		create sensor_agent number: sensor_agent_nb;
		write "INIT: created " + sensor_agent_nb + " sensor agents";
	}
}

species sensor_agent skills:[android_sensor]{
	int ticks <- 0;

	reflex check_sensor {
		ticks <- ticks + 1;
		if (ticks mod 10 = 0) {
			list vals <- get_sensor_data();
			write "SENSOR vals=" + vals + " accel_x=" + accel_x + " light=" + light +
				" battery=" + battery_level + " ts=" + sensor_timestamp +
				" available=" + is_sensor_available();
		}
	}

	aspect default {
		draw circle(3.0) color: rgb(200, 50, 50);
	}
}

experiment sensor_test type: gui {
	output {
		display sensors type: 2d {
			species sensor_agent;
		}
	}
}
