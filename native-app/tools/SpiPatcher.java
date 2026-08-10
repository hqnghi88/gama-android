import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;

/**
 * Removes LongitudeFirstFactory/LongitudeFirstEpsgDecorator entries from
 * all GeoTools SPI files inside gt-referencing-*.jar to prevent
 * recursive factory loop on Android.
 */
public class SpiPatcher {

    static final String[] SPI_PATHS = {
        "META-INF/services/org.geotools.api.referencing.crs.CRSAuthorityFactory",
        "META-INF/services/org.geotools.api.referencing.cs.CSAuthorityFactory",
        "META-INF/services/org.geotools.api.referencing.operation.CoordinateOperationAuthorityFactory",
        "META-INF/services/org.geotools.api.referencing.datum.DatumAuthorityFactory"
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SpiPatcher <jar-file>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found: " + jarFile); System.exit(1); }

        Map<String, List<String>> patches = new LinkedHashMap<>();
        boolean anyPatched = false;

        JarFile jar = new JarFile(jarFile);
        for (String spiPath : SPI_PATHS) {
            JarEntry spiEntry = jar.getJarEntry(spiPath);
            if (spiEntry == null) {
                System.out.println("SPI file not found: " + spiPath);
                continue;
            }
            List<String> filtered = new ArrayList<>();
            boolean patched = false;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(jar.getInputStream(spiEntry), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.contains("LongitudeFirstFactory") || trimmed.contains("LongitudeFirstEpsgDecorator")) {
                        System.out.println("Removing from " + spiPath + ": " + trimmed);
                        patched = true;
                    } else {
                        filtered.add(line);
                    }
                }
            }
            if (patched) {
                patches.put(spiPath, filtered);
                anyPatched = true;
            }
        }
        jar.close();

        if (!anyPatched) {
            System.out.println("No LongitudeFirstFactory entries found, nothing to patch");
            return;
        }

        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tmpJar));
             JarFile jarIn = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jarIn.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                List<String> replacement = patches.get(entry.getName());
                if (replacement != null) {
                    JarEntry newEntry = new JarEntry(entry.getName());
                    jos.putNextEntry(newEntry);
                    for (String l : replacement) {
                        jos.write((l + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                    jos.closeEntry();
                } else {
                    jos.putNextEntry(new JarEntry(entry.getName()));
                    try (InputStream is = jarIn.getInputStream(entry)) {
                        is.transferTo(jos);
                    }
                    jos.closeEntry();
                }
            }
        }

        jarFile.delete();
        tmpJar.renameTo(jarFile);
        System.out.println("Patched " + patches.size() + " SPI file(s) in " + jarFile.getName());
    }
}
