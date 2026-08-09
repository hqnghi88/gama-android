package org.eclipse.core.runtime;

import java.util.HashMap;
import java.util.Map;

import org.osgi.framework.Bundle;

public class Platform {
    private static final Map<String, Bundle> bundles = new HashMap<>();
    private static final IExtensionRegistry emptyRegistry = new EmptyExtensionRegistry();

    public static Bundle getBundle(String id) {
        return bundles.get(id);
    }

    public static void registerBundle(String id, Bundle bundle) {
        bundles.put(id, bundle);
    }

    public static IExtensionRegistry getExtensionRegistry() {
        return emptyRegistry;
    }

    public static String getOS() {
        return "linux";
    }

    public static String getOSArch() {
        String arch = System.getProperty("os.arch", "");
        if (arch.contains("aarch64") || arch.contains("arm64")) return "aarch64";
        return "x86_64";
    }

    public static String getNL() {
        return System.getProperty("user.language", "en");
    }

    public static String getWS() {
        return "gtk";
    }

    public static boolean inDebugMode() {
        return false;
    }

    private static class EmptyExtensionRegistry implements IExtensionRegistry {
        @Override
        public IExtensionPoint getExtensionPoint(String id) { return null; }
        @Override
        public IConfigurationElement[] getConfigurationElementsFor(String id) { return new IConfigurationElement[0]; }
        @Override
        public IExtension[] getExtensions(String id) { return new IExtension[0]; }
    }
}
