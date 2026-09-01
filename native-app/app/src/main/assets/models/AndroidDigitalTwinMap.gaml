/**
 * Name: Digital Twin - 3D City Map (GPS + OpenStreetMap)
 * Author: GAMA Android
 * Description: A realistic 3D city map built from real OpenStreetMap data centered on the
 * 	phone's actual GPS position. On start the app captures the real GPS fix, downloads the
 * 	buildings and roads around it from OpenStreetMap (Overpass) into map.osm, and the model
 * 	loads them with the osm_file operator to raise a 3D city. Whenever the phone moves more
 * 	than ~50 m the app downloads a fresh map and bumps map.version; this model detects the
 * 	change and rebuilds the skyline around the new location.
 * 	GPS attributes come from the android_sensor skill: gps_lat, gps_lon, gps_bearing,
 * 	gps_speed and gps_accuracy.
 * Tags: android, sensor, gps, osm, openstreetmap, gis, 3d, city, digital_twin, map
 */
model AndroidDigitalTwinMap

global {

	// The OverpassFetcher projects raw WGS84 lat/lon onto a local metric grid anchored at the GPS
	// fix, so the city always spans roughly +/-800m around the origin (0,0). Frame the world there
	// at init so the 3D camera opens on the city instead of an empty default region.
	geometry shape <- polygon([{-800,-800},{800,-800},{800,800},{-800,800}]);

	// --- the physical twin: the phone's real GPS ---
	float s_lat <- 0.0;
	float s_lon <- 0.0;
	float s_speed <- 0.0;
	float s_bearing <- 0.0;
	float s_accuracy <- 0.0;
	bool has_fix <- false;
	bool sensor_ok <- false;

	// --- the geographic twin: OpenStreetMap data natively written into map.osm ---
	bool city_built <- false;
	int nb_buildings <- 0;
	int nb_roads <- 0;
	// location (lat, lon) the city was last built for, to detect meaningful movement
	float s_lat_last <- 0.0;
	float s_lon_last <- 0.0;

	reflex read_gps {
		ask the_twin {
			s_lat <- gps_lat;
			s_lon <- gps_lon;
			s_speed <- gps_speed;
			s_bearing <- gps_bearing;
			s_accuracy <- gps_accuracy;
			sensor_ok <- is_sensor_available();
			// NaN never satisfies this comparison, so an unfixed GPS is detected safely.
			if (gps_lat > -90.0 and gps_lat < 90.0) {
				has_fix <- true;
			}
		}
	}

	reflex poll_map {
		// Build/rebuild when the OSM data file exists and the phone moved meaningfully
		// (>~55 m) or the city has not been built yet. s_lat/s_lon are copied from the twin
		// by read_gps; an unfixed GPS stays 0 and never satisfies the comparisons.
		if (file_exists("map.osm") and has_fix) {
			bool moved <- false;
			if (abs(s_lat - s_lat_last) > 0.0005 or abs(s_lon - s_lon_last) > 0.0005) {
				moved <- true;
			}
			if (not city_built or moved) {
				s_lat_last <- s_lat;
				s_lon_last <- s_lon;
				city_built <- true;
				do build_city;
			}
		}
	}

	action build_city () {
		// clear the previous skyline
		ask list(building) { do die(); }
		ask list(road) { do die(); }

		// Import the OSM data. The native OverpassFetcher projects raw WGS84 lat/lon into a local
		// metric grid anchored at the GPS fix, so the coordinates are small local meter values that
		// GAMA's default projection accepts without reprojection errors, and building/road sizes are
		// naturally in meters for the 3D display.
		file<geometry> osmfile <- file<geometry>(
				osm_file("map.osm", ["highway"::["primary", "secondary", "tertiary", "motorway",
						"living_street", "residential", "unclassified", "service"],
						"building"::["yes"]]));
		create osm(highway_str: string(read("highway")), building_str: string(read("building"))) from: osmfile;
		ask osm {
			if (length(shape.points) = 1 and highway_str != nil) {
				// single road node: skip
			} else {
				if (highway_str != nil) {
					create road(shape: shape, type: highway_str);
				} else if (building_str != nil) {
					create building(shape: shape);
				}
			}
			do die();
		}

		nb_buildings <- length(building);
		nb_roads <- length(road);

		write "City map ready: " + nb_buildings + " buildings, " + nb_roads + " roads around GPS "
			+ (s_lat with_precision 4) + "," + (s_lon with_precision 4);
	}

	init {
		create the_twin;
		write "3D City Map started: waiting for GPS fix and OpenStreetMap data...";
	}
}

species the_twin skills: [android_sensor] {}

// Intermediate species holding the raw OSM attributes, as in the GAMA OSM import model.
species osm {
	string highway_str;
	string building_str;
}

species building {
	float h <- 24.0 + rnd(60.0);
	aspect map {
		draw shape depth: h color: rgb(190, 180, 168) border: rgb(120, 110, 100);
	}
}

species road {
	string type;
	aspect map {
		draw shape width: 6.0 color: rgb(120, 130, 150);
	}
}

experiment city_map type: gui autorun: true {
	output {
		display city type: 3d background: rgb(12, 16, 40) {
			species building aspect: map;
			species road aspect: map;
			graphics marker {
				if (has_fix) {
					draw sphere(6.0) at: {0, 0, 2.0} color: rgb(255, 80, 60);
				}
			}
			graphics overlay {
				draw "3D CITY MAP  /  OPENSTREETMAP + GPS" at: {20, 680} color: rgb(255, 255, 255);
				draw "GPS: " + (s_lat with_precision 5) + "," + (s_lon with_precision 5)
					+ "  Bearing: " + int(s_bearing) + "  Speed: " + (s_speed with_precision 1)
					+ "  Acc: " + int(s_accuracy) at: {20, 705} color: rgb(120, 190, 255);
				draw "City: " + nb_buildings + " buildings, " + nb_roads + " roads   Fix: "
					+ (has_fix ? "yes" : "no") at: {20, 730} color: rgb(170, 255, 160);
			}
		}
	}
}
