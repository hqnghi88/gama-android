/**
 * Name: Digital Twin - 3D City Map (GPS + OpenStreetMap)
 * Author: GAMA Android
 * Description: A realistic 3D city map built from real OpenStreetMap data centered on the
 * 	phone's actual GPS position. The model itself downloads the buildings and roads around the
 * 	GPS fix from the Overpass API with the osm_file operator, then projects the WGS84 degrees
 * 	onto a local metric grid anchored at the fix to raise a 3D city in meters. Whenever the
 * 	phone moves more than ~55 m the model downloads a fresh map from Overpass and rebuilds the
 * 	skyline around the new location.
 * 	GPS attributes come from the android_sensor skill: gps_lat, gps_lon, gps_bearing,
 * 	gps_speed and gps_accuracy.
 * Tags: android, sensor, gps, osm, openstreetmap, overpass, network, gis, 3d, city, digital_twin, map
 */
model AndroidDigitalTwinMap

global {
	// The OSM geometries arrive in WGS84 degrees; GAML projects them onto a local metric grid
	// anchored at the GPS fix (see the project action). With a 100m fetch radius the city spans
	// roughly +/-120m around the origin.
	geometry shape <- polygon([{-150,-150},{150,-150},{150,150},{-150,150}]);

	// --- Overpass (OpenStreetMap) download configuration ---
	float OVERPASS_RADIUS_M <- 100.0;
	string OVERPASS_URL <- "https://overpass-api.de/api/interpreter";

	// --- the physical twin: the phone's real GPS ---
	float s_lat <- 0.0;
	float s_lon <- 0.0;
	float s_speed <- 0.0;
	float s_bearing <- 0.0;
	float s_accuracy <- 0.0;
	bool has_fix <- false;
	bool sensor_ok <- false;

	// --- the geographic twin: OpenStreetMap data downloaded by the GAML model ---
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
		// Build/rebuild when the phone moved meaningfully (>~55 m) or the city has not been
		// built yet. s_lat/s_lon are copied from the twin by read_gps; an unfixed GPS stays 0
		// and never satisfies the comparisons.
		if (has_fix) {
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

		// Build the Overpass query for the buildings and highways around the GPS fix. The query
		// is percent-encoded by the encode action and fetched directly in GAML through osm_file.
		string query <- "[out:xml][timeout:40];(way[\"building\"](around:" + int(OVERPASS_RADIUS_M)
			+ "," + s_lat + "," + s_lon + ");way[\"highway\"](around:" + int(OVERPASS_RADIUS_M)
			+ "," + s_lat + "," + s_lon + "););out meta;>;out meta qt;";
		string url <- OVERPASS_URL + "?data=" + encode(query);
		write "Downloading OSM from Overpass around " + (s_lat with_precision 4) + ","
			+ (s_lon with_precision 4) + " (radius " + int(OVERPASS_RADIUS_M) + " m)";

		file<geometry> osmfile <- file<geometry>(
				osm_file(url, ["highway"::["primary", "secondary", "tertiary", "motorway",
						"living_street", "residential", "unclassified", "service"],
						"building"::["yes"]]));

		create osm(highway_str: string(read("highway")), building_str: string(read("building"))) from: osmfile;
		ask osm {
			// project each OSM geometry from WGS84 degrees onto the local metric grid, then
			// spawn the corresponding building or road agent (single OSM highway nodes are
			// skipped, exactly like before)
			if (highway_str != nil and length(shape.points) < 2) {
				// single road node: skip
			} else {
				geometry localshape <- project(shape, s_lat, s_lon);
				if (highway_str != nil and localshape.perimeter > 0.0) {
					create road(shape: localshape, type: highway_str);
				} else if (building_str != nil and localshape.area > 0.0) {
					create building(shape: localshape);
				}
			}
			do die();
		}

		nb_buildings <- length(building);
		nb_roads <- length(road);

		write "City map ready: " + nb_buildings + " buildings, " + nb_roads + " roads around GPS "
			+ (s_lat with_precision 4) + "," + (s_lon with_precision 4);
	}

	// Percent-encode the Overpass query so it can travel safely inside the GET URL.
	// (GAMA has no url_encode operator; every character below is legal in the query text.)
	string encode(string src) {
		string out <- src;
		list<string> chars <- [" ", "[", "]", "(", ")", ";", ":", ",", ">", "=", "\""];
		list<string> enc <- ["%20", "%5B", "%5D", "%28", "%29", "%3B", "%3A", "%2C", "%3E", "%3D", "%22"];
		loop i from: 0 to: length(chars) - 1 {
			out <- replace(out, chars[i], enc[i]);
		}
		return out;
	}

	init {
		// Give Overpass time to answer and retry failed downloads (it is occasionally slow).
		gama.pref_http_read_timeout <- 90000;
		gama.pref_http_retry_number <- 3;
		create the_twin;
		write "3D City Map started: waiting for GPS fix to download OpenStreetMap data...";
	}
}

species the_twin skills: [android_sensor] {}

// Intermediate species holding the raw OSM attributes, as in the GAMA OSM import model.
species osm {
	string highway_str;
	string building_str;

	// Move an OSM geometry from WGS84 degrees to a local metric grid anchored at the GPS fix.
	// This keeps building/road sizes naturally in meters for the 3D display and lets the model
	// keep GAMA's default projection (the OSM data is used verbatim, no auto reprojection).
	// Defined here (not in global) so it can be called from this agent's context while populating
	// the city: globals aren't resolvable as operators from inside an "ask osm" block.
	geometry project(geometry g, float ref_lat, float ref_lon) {
		list<point> pts <- g.points;
		list<point> localPts <- [];
		point prep;
		loop p over: pts {
			prep <- { (p.x - ref_lon) * 111320.0 * cos_rad(ref_lat * #pi / 180.0), (p.y - ref_lat) * 111320.0 };
			localPts << prep;
		}
		if (g.area > 0.0) { return polygon(localPts); }
		if (length(localPts) > 1) { return polyline(localPts); }
		if (length(localPts) = 1) { return first(localPts); }
		return g;
	}
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
			camera 'default' location: {0, -90, 6} target: {0, 0, 15};
			species building aspect: map;
			species road aspect: map;
			graphics marker {
				if (has_fix) {
					draw sphere(2.5) at: {0, 0, 1.5} color: rgb(255, 80, 60);
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
