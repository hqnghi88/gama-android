// Demo model shipped inside the gama.extension.demo plugin. Installed plugins that
// carry a models/ tree in their jar appear under "Extensions" in the app library;
// this one exercises the demo_square operator the plugin contributes.
model PluginDemo

global {
	init {
		write "PLUGIN DEMO: demo_square(4)=" + demo_square(4) +
			" demo_cube(3)=" + demo_cube(3) + " demo_greet(GAMA)=" + demo_greet("GAMA");
	}
}

experiment plugin_demo type: gui {
}