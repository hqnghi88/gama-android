/**
 * Name: Digital Twin - Immersive 3D City (GPS + OpenStreetMap + Air Mesh)
 * Author: GAMA Android
 * Description: An immersive digital twin around the phone's real GPS fix. The model downloads
 * 	real buildings and roads from OpenStreetMap through the osm_file operator (Overpass API),
 * 	projects the WGS84 degrees onto a local metric grid and raises a city in meters. A dynamic
 * 	'first_person' camera places the viewer at eye level on the GPS position, looking along the
 * 	phone's bearing; dragging looks around and pinching zooms. A ground map background (asphalt
 * 	+ grid + flat roads) anchors the scene, and a 3D 'air mesh' -- a synthetic pollution field
 * 	built from road line sources and a hotspot at the phone -- is rendered as a colored
 * 	surface rising through the city. Rebuilt around the GPS whenever the phone moves > ~55 m.
 * 	GPS attributes come from the android_sensor skill: gps_lat, gps_lon, gps_bearing,
 * 	gps_speed and gps_accuracy.
 * Tags: android, sensor, gps, osm, openstreetmap, overpass, network, gis, 3d, city, digital_twin, map, pollution, immersive
 */
model AndroidDigitalTwinMap

global {
	// The world is shifted into the [0..WORLD_M] square so that the pollution mesh (which the
	// 3D renderer lays over [0..envW]x[0..envH]) lines up exactly with the projected city.
	float WORLD_M <- 300.0;
	float OFFSET <- WORLD_M / 2.0;
	geometry shape <- polygon([{0.0,0.0},{WORLD_M,0.0},{WORLD_M,WORLD_M},{0.0,WORLD_M}]);

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
	bool city_ok <- false;
	int nb_buildings <- 0;
	int nb_roads <- 0;
	// The city origin (lat, lon): the OSM download is anchored 55m apart on this and the local
	// grid maps it to (OFFSET, OFFSET). Updated even on a failed download so the camera, the
	// marker and the pollution hotspot always stay inside the world.
	float s_anchor_lat <- 0.0;
	float s_anchor_lon <- 0.0;
	float last_city_try <- -1000.0;

	// --- world-space position of the phone (m), recomputed every step from the fix ---
	float M_PER_DEG <- 111320.0;
	float gps_wx <- OFFSET;
	float gps_wy <- OFFSET;

	// --- immersive camera ---
	float EYE_H <- 1.6;
	float LOOKAHEAD_M <- 8.0;
	float CAM_LENS <- 90.0;

	// --- air pollution mesh (synthetic: roads line-source + GPS hotspot + noise) ---
	int POLL_N <- 32;
	float CELL_W <- WORLD_M / float(POLL_N);
	field road_base <- field(POLL_N, POLL_N, 0.0);
	field poll_vals <- field(POLL_N, POLL_N, 0.0);
	float pm_at_gps <- 0.0;

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
		// phone position on the local grid, anchored at the last city-build origin
		gps_wx <- OFFSET + (s_lon - s_anchor_lon) * M_PER_DEG * cos_rad(s_anchor_lat * #pi / 180.0);
		gps_wy <- OFFSET + (s_lat - s_anchor_lat) * M_PER_DEG;
		// keep the air mesh pinned to the phone between city rebuilds
		do refresh_pollution;
	}

	reflex poll_map {
		// Build/rebuild the city when the phone moved meaningfully (>~55 m), when the city is
		// not available yet but the download was last tried long enough ago (a failed Overpass
		// attempt must not be retried every step), or on the very first fix. An unfixed GPS
		// stays 0 and never satisfies the comparisons.
		if (has_fix) {
			bool moved <- abs(s_lat - s_anchor_lat) > 0.0005 or abs(s_lon - s_anchor_lon) > 0.0005;
			if (not city_ok or moved or time - last_city_try > 60.0) {
				s_anchor_lat <- s_lat;
				s_anchor_lon <- s_lon;
				last_city_try <- time;
				do build_city;
				do build_pollution;
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

		// A failed/slow download must not freeze or kill the simulation: the machine stays
		// city_ok=false and poll_map retries once the phone moves or every ~60 s.
		try {
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
					geometry localshape <- project(shape, s_anchor_lat, s_anchor_lon);
					if (highway_str != nil and localshape.perimeter > 0.0) {
						create road(shape: localshape, type: highway_str);
					} else if (building_str != nil and localshape.area > 0.0) {
						create building(shape: localshape);
					}
				}
				do die();
			}
		} catch {
			write "OSM download failed (will retry)";
		}

		nb_buildings <- length(building);
		nb_roads <- length(road);
		city_built <- true;
		if (nb_buildings + nb_roads > 0) {
			city_ok <- true;
			write "City map ready: " + nb_buildings + " buildings, " + nb_roads + " roads around GPS "
				+ (s_lat with_precision 4) + "," + (s_lon with_precision 4);
		}
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

	// --- air mesh layer 1: everything except the GPS hotspot (rebuilt only with the city) ---
	action build_pollution () {
		field base <- field(POLL_N, POLL_N, 0.0);
		// sample the road centrelines so the mesh has line-source 'corridors'
		list<point> rp <- [];
		ask road {
			list<point> pts <- shape.points;
			loop k from: 0 to: length(pts) - 2 {
				point a <- pts[k];
				point b <- pts[k + 1];
				float seg <- a distance_to b;
				int n <- int(seg / 6.0) + 1;
				loop t from: 0 to: n {
					float f <- float(t) / float(n);
					point sp <- { (a.x + (b.x - a.x) * f), (a.y + (b.y - a.y) * f) };
					if (sp distance_to {gps_wx, gps_wy} < 96.0) { rp << sp; }
				}
			}
		}
		loop i from: 0 to: POLL_N - 1 {
			loop j from: 0 to: POLL_N - 1 {
				point cc <- { CELL_W * (float(i) + 0.5), CELL_W * (float(j) + 0.5) };
				float v <- 0.0;
				// road line-source corridors
				loop p over: rp {
					float dr <- cc distance_to p;
					if (dr < 90.0) {
						v <- v + 0.4 * exp(-1.0 * (dr * dr) / 800.0);
					}
				}
				// deterministic micro-structure so the surface is not uniform
				v <- v + 0.02 * abs(sin(float(i) * 12.9898 + float(j) * 78.233));
				base[i,j] <- min(0.85, v);
			}
		}
		road_base <- base;
		do refresh_pollution;
	}

	// --- air mesh layer 2: GPS hotspot added on top, refreshed every step so the plume
	// follows the phone smoothly between city rebuilds ---
	action refresh_pollution () {
		field now <- field(POLL_N, POLL_N, 0.0);
		point gp <- {gps_wx, gps_wy};
		loop i from: 0 to: POLL_N - 1 {
			loop j from: 0 to: POLL_N - 1 {
				point cc <- { CELL_W * (float(i) + 0.5), CELL_W * (float(j) + 0.5) };
				float dg <- cc distance_to gp;
				float v <- road_base[i,j] + 0.26 * exp(-1.0 * (dg * dg) / 1100.0);
				now[i,j] <- min(1.0, v);
			}
		}
		poll_vals <- now;
		int ix <- min(POLL_N - 1, max(0, int(gps_wx / CELL_W)));
		int iy <- min(POLL_N - 1, max(0, int(gps_wy / CELL_W)));
		pm_at_gps <- poll_vals[ix,iy];
	}

	init {
		// A failed Overpass must not freeze the twin for minutes: short read timeout + one retry.
		gama.pref_http_read_timeout <- 20000;
		gama.pref_http_retry_number <- 1;
		create the_twin;
		do build_pollution;
		write "Immersive digital twin started: waiting for GPS fix to download OpenStreetMap data...";
	}
}

species the_twin skills: [android_sensor] {}

// Intermediate species holding the raw OSM attributes, as in the GAMA OSM import model.
species osm {
	string highway_str;
	string building_str;

	// Move an OSM geometry from WGS84 degrees to a local metric grid anchored at the GPS fix.
	// The +OFFSET shift puts the projected city inside the [0..WORLD_M] world so it aligns with
	// the pollution mesh (which the renderer lays over the whole environment envelope).
	geometry project(geometry g, float ref_lat, float ref_lon) {
		list<point> pts <- g.points;
		list<point> localPts <- [];
		point prep;
		float kx <- M_PER_DEG * cos_rad(ref_lat * #pi / 180.0);
		loop p over: pts {
			prep <- { (p.x - ref_lon) * kx + OFFSET, (p.y - ref_lat) * M_PER_DEG + OFFSET };
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
		// A buffered polygon band: the 3D backend cannot stroke lines with a width, so the
		// road geometry is inflated into an area that reads as a street on the map.
		float w <- 3.0;
		if (type = "primary" or type = "secondary" or type = "motorway") { w <- 4.5; }
		draw buffer(shape, w) color: rgb(120, 130, 150);
	}
}

experiment city_map type: gui autorun: true {
	output {
		display city type: 3d background: rgb(12, 16, 40) camera: "first_person" {
			// 'first_person' is detected on the native side: the eye stays at the GPS position,
			// dragging looks around and pinching zooms the field of view.
			camera 'first_person' location: {gps_wx, gps_wy, EYE_H}
				target: {gps_wx + LOOKAHEAD_M * sin_rad(s_bearing * #pi / 180.0),
					gps_wy + LOOKAHEAD_M * cos_rad(s_bearing * #pi / 180.0), EYE_H}
				lens: CAM_LENS dynamic: true;

			// map background: asphalt ground + orientation grid, so the horizon is anchored
			graphics ground {
				draw rectangle(WORLD_M, WORLD_M) at: {OFFSET, OFFSET, -0.5} color: rgb(24, 29, 38);
				int steps <- int(WORLD_M / 25.0);
				loop g from: 0 to: steps {
					float x <- float(g) * 25.0;
					// thin flat bands (not stroked lines: the 3D backend draws lines at 1 px and
					// they get buried under the pollution surface)
					draw rectangle(1.2, WORLD_M) at: {x, OFFSET, 0.12} color: rgb(58, 66, 82);
					draw rectangle(WORLD_M, 1.2) at: {OFFSET, x, 0.12} color: rgb(58, 66, 82);
				}
			}

			// the air-pollution mesh: colored surface whose height ~ concentration
			mesh poll_vals scale: 9.0 color: palette([rgb(30, 110, 60), rgb(210, 185, 45),
				rgb(225, 100, 40), rgb(178, 28, 28)]) triangulation: true transparency: 0.3;

			species building aspect: map;
			species road aspect: map;

			graphics marker {
				if (has_fix) {
					// the phone position: a slim pin raised above the local pollution
					// surface so it is never buried under the mesh
					draw box(0.8, 0.8, 7.0) at: {gps_wx, gps_wy, 0.3 + pm_at_gps * 7.0 + 3.5}
						color: rgb(255, 60, 50);
				}
			}

			graphics overlay {
				draw "DIGITAL TWIN  /  IMMERSIVE" at: {20, 680} color: rgb(255, 255, 255);
				draw "GPS: " + (s_lat with_precision 5) + "," + (s_lon with_precision 5)
					+ "  Bearing: " + int(s_bearing) + "  Speed: " + (s_speed with_precision 1)
					+ "  Acc: " + int(s_accuracy) at: {20, 705} color: rgb(120, 190, 255);
				draw "City: " + nb_buildings + " buildings, " + nb_roads + " roads   Air @ GPS: "
					+ (pm_at_gps with_precision 2) + "   Fix: " + (has_fix ? "yes" : "no")
					at: {20, 730} color: rgb(170, 255, 160);
				draw "1 finger: pan  |  2 fingers: look around  |  pinch: zoom"
					at: {20, 755} color: rgb(150, 150, 165);
			}
		}
	}
}