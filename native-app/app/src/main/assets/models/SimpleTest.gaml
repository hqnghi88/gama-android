model SimpleTest

global {
	int nb_agents <- 20;
	
	init {
		create test_agent number: nb_agents;
		write "INIT: after create, length(test_agent)=" + length(test_agent);
	}
}

species test_agent skills:[moving]{
	int age <- 0;
	
	reflex aging {
		age <- age + 1;
		do wander();
	}
	
	aspect default {
		draw triangle(3.0) color: rgb(0, 0, 255);
	}
}

experiment test_experiment type: gui {
	output {
		display map type: 2d {
			species test_agent;
		}
	}
}
