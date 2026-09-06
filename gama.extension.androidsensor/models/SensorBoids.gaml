/**
* Name: Sensor Boids
* Author: Gama Development Team
* Description: An implementation of Craig Reynolds' Boids flocking algorithm. Each boid agent follows three
*   simple local rules: separation (avoid crowding neighbors), alignment (steer towards the average heading of
*   neighbors), and cohesion (steer towards the average position of neighbors). Despite these simple rules,
*   complex flocking behavior emerges at the group level. The goal agent is steered by your device: tilt the
*   phone (accelerometer) to move it, twist it (gyroscope z) to swirl the drift. When no sensors are available
*   (e.g. desktop), the goal gently wanders so the model still runs without a device.
* Tags: android, sensor, boids, flocking, emergence, collective_behavior, interaction, mobile
*/

model SensorBoids 
global { 
	//Number of boids that will be created
	int number_of_agents <- 50 min: 1 max: 500;
	//Number of obstacles for the boids movement to represent
	int number_of_obstacles <- 0 min: 0;
	//Maximal speed of the boids
	float maximal_speed <- 15.0 min: 0.1 max: 15.0;
	//Factors for the group of boids
	int cohesion_factor <- 200;
	int alignment_factor <- 100; 
	//Variables for the movement of the boids
	float minimal_distance <- 30.0; 
	
	int width_and_height_of_environment <- 1000;  
	bool apply_cohesion <- true ;
	bool apply_alignment <- true ;
	bool apply_separation <- true;
	bool apply_avoid <- true;  
	bool apply_wind <- true;   
	bool moving_obstacles <- false;   
	int bounds <- int(width_and_height_of_environment / 20); 
	//Vector for the wind
	point wind_vector <- {0,0}; 
	int xmin <- bounds;   
	int ymin <- bounds;  
	int xmax <- (width_and_height_of_environment - bounds);     
	int ymax <- (width_and_height_of_environment - bounds);   
	
	//Action to move the goal to the mouse location
	action move_goal() {
		ask first(boids_goal) {
			do goto(target: #user_location, speed: 30.0);
		}
	}
	
	geometry shape <- square(width_and_height_of_environment);
	
	init { 
		//Create the boids agents
		create boids number: number_of_agents { 
			 location <- {rnd (width_and_height_of_environment - 2) + 1, rnd (width_and_height_of_environment -2) + 1 };
		} 
		//Create the obstacles agents
		create obstacle number: number_of_obstacles {
			location <- {rnd (width_and_height_of_environment - 2) + 1, rnd (width_and_height_of_environment -2) + 1 }; 
		}
		//Create the goal that boids will follow
		create  boids_goal;	
	}	
}

//Species boids goal which represents the goal that will be followed by boids agents using the skill moving
species boids_goal skills: [android_sensor, moving] {
	float range  <- 20.0;
	
	//Velocity accumulated from the device sensors. The accelerometer tilt steers the
	//goal and the gyroscope z swirls the drift direction on top of the tilt.
	point sensor_vel <- {0,0};
	
	//If the mouse is not used, then the goal just wanders. With a device, the goal is
	//steered by the sensors instead; without sensors it falls back to wandering.
	reflex wander {  
		if (is_sensor_available()) {
			point tilt <- {accel_x * 4.0, -accel_y * 4.0};
			sensor_vel <- {sensor_vel.x * 0.9 + tilt.x, sensor_vel.y * 0.9 + tilt.y};
			float ang <- gyro_z * 0.4;
			sensor_vel <- {sensor_vel.x * cos(ang) - sensor_vel.y * sin(ang), sensor_vel.x * sin(ang) + sensor_vel.y * cos(ang)};
			float spd <- sqrt(sensor_vel.x * sensor_vel.x + sensor_vel.y * sensor_vel.y);
			if (spd > 12.0) {
				sensor_vel <- {sensor_vel.x * 12.0 / spd, sensor_vel.y * 12.0 / spd};
			}
			//keep the goal inside the environment, bouncing on the borders
			if (location.x < float(xmin)) { sensor_vel <- {0 - sensor_vel.x, sensor_vel.y}; }
			if (location.x > float(xmax)) { sensor_vel <- {0 - sensor_vel.x, sensor_vel.y}; }
			if (location.y < float(ymin)) { sensor_vel <- {sensor_vel.x, 0 - sensor_vel.y}; }
			if (location.y > float(ymax)) { sensor_vel <- {sensor_vel.x, 0 - sensor_vel.y}; }
			location <- location + sensor_vel;
		} else {  
			do  wander(amplitude: 45.0, speed: 20.0);  
		}
	}
	
	aspect default {
		draw circle(10) color: #red ;
		draw circle(40) color: #orange wireframe: true;
	}
} 
//Species boids which represents the boids agents whom follow the boid goal agents, using the skill moving
species boids skills: [moving] {
	//Speed of the boids agents
	float speed max: maximal_speed <- maximal_speed;
	//Range used to consider the group of the agent
	float range <- minimal_distance * 2;
	point velocity <- {0,0};
		
	//Reflex used when the separation is applied to change the velocity of the boid
	reflex separation when: apply_separation {
		point acc <- {0,0};
		ask (boids overlapping (circle(minimal_distance)))  {
			acc <- acc - ((location) - myself.location);
		}  
		velocity <- velocity + acc;
	}
	
	//Reflex to align the boid with the other boids in the range
	reflex alignment when: apply_alignment {
		list<boids> others  <- ((boids overlapping (circle (range)))  - self);
		point acc <- mean (others collect (each.velocity)) - velocity;
		velocity <- velocity + (acc / alignment_factor);
	}
	 
	//Reflex to apply the cohesion of the boids group in the range of the agent
	reflex cohesion when: apply_cohesion {
		list<boids> others <- ((boids overlapping (circle (range)))  - self);
		point mass_center <- (length(others) > 0) ? mean (others collect (each.location)) : location;

		point acc <- mass_center - location;
		acc <- acc / cohesion_factor; 
		velocity <- velocity + acc;   
	}
	
	//Reflex to avoid the obstacles
	reflex avoid when: apply_avoid { 
		point acc <- {0,0};
		list<obstacle> nearby_obstacles <- (obstacle overlapping (circle (range)) );
		loop obs over: nearby_obstacles {
			acc <- acc - ((location of obs) -  location);
		}
		velocity <- velocity + acc; 
	}
	
	//action to represent the bounding of the environment considering the velocity of the boid
	action bounding() {
		if  (location.x) < xmin {
			velocity <- velocity + {bounds,0};
		} else if (location.x) > xmax {
			velocity <- velocity - {bounds,0};
		}
		
		if (location.y) < ymin {
			velocity <- velocity + {0,bounds};
		} else if (location.y) > ymax {
			velocity <- velocity - {0,bounds};
		}	
	}
	//Reflex to follow the goal 
	reflex follow_goal {
		velocity <- velocity + ((first(boids_goal).location - location) / cohesion_factor);
	}
	//Reflex to apply the wind vector on the velocity
	reflex wind when: apply_wind {
		velocity <- velocity + wind_vector;
	}
	
	//Action to move the agent  
	action do_move() {  
		if (((velocity.x) as int) = 0) and (((velocity.y) as int) = 0) {
			velocity <- {(rnd(4)) -2, (rnd(4)) - 2};
		}
		point old_location <- copy(location);
		do goto (target: location + velocity);
		velocity <- location - old_location;
	}
	
	//Reflex to apply the movement by calling the do_move action
	reflex movement {
		do do_move();
		do bounding();
	}
	
	aspect circle { 
		draw circle(15)  color: #red;
	}
	
	aspect default { 
		draw circle(20) color: #lightblue wireframe: true;
	}
} 

//Species obstacle that represents the obstacles avoided by the boids agents using the skill moving
species obstacle skills: [moving] {
	float speed <- 2.0;

	init {
		shape <- triangle(15);
	}	
	//Reflex to move the obstacles if it is available
	reflex move_obstacles when: moving_obstacles {
		//Will make the agent go to a boid with a 50% probability
		if flip(0.5)  
		{ 
			do goto (target: one_of(boids));
		} 
		else{ 
			do wander (amplitude: 360.0);   
		}
	}
	aspect default {
		draw  triangle(20) color: #black ;
	}

}


experiment sensor_boids type: gui {
	parameter 'Number of agents' var: number_of_agents;
	parameter 'Number of obstacles' var: number_of_obstacles;
	parameter 'Maximal speed' var: maximal_speed;
	parameter 'Cohesion Factor' var: cohesion_factor;
	parameter 'Alignment Factor' var: alignment_factor; 
	parameter 'Minimal Distance'  var: minimal_distance; 
	parameter 'Width/Height of the Environment' var: width_and_height_of_environment ;  
	parameter 'Apply Cohesion ?' var: apply_cohesion ;
	parameter 'Apply Alignment ?' var: apply_alignment ;   
	parameter 'Apply Separation ?' var: apply_separation ;   
	parameter 'Apply Avoidance ?' var: apply_avoid ;   
	parameter 'Apply Wind ?' var: apply_wind ;     
	parameter 'Moving Obstacles ?' var: moving_obstacles  ;    
	parameter 'Direction of the wind' var: wind_vector ;  
	
	//Minimum duration of a step to better see the movements
	float minimum_cycle_duration <- 0.01;

	output synchronized: true {
		display Sky type: 2d background: rgb(20, 20, 40) {
			species boids aspect: circle;
			species boids_goal;
			species obstacle;
		}
	}
}


