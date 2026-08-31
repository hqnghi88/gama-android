import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Injects the committed Android sensor library models (from src/main/assets/models/)
 * into the runtime library jar (assets/gama.library.jar) under
 * "models/Toy Models/Android Sensors/". Idempotent: existing target entries are replaced.
 *
 * Usage: java JarModelInjector <library.jar> <modelsDir>
 */
public class JarModelInjector {

    private static final String[] MODELS = {
        "AndroidSensorLab.gaml",
        "AndroidSensorTest.gaml",
        "AndroidDigitalTwinEnvironment.gaml",
        "AndroidDigitalTwinFlocking.gaml"
    };

    public static void main(String[] args) throws Exception {
        File jar = new File(args[0]);
        File modelsDir = new File(args[1]);
        File tmp = new File(jar.getAbsolutePath() + ".inject");

        Map<String, File> injected = new LinkedHashMap<>();

        // Directory entries must be present for the runtime tree builder to create the
        // folder node and attach the injected files, and must be written BEFORE the files
        // in the jar (buildTree() looks up a file's parent in a dir map built as it scans
        // entries in jar order). The baseline libs jar from the CI deps bundle lacks the
        // "Android Sensors" directory, so add each ancestor directory entry that is NOT
        // already present (adding existing ones would create duplicate folder nodes —
        // buildTree() does not deduplicate directories).
        Set<String> existing = new HashSet<>();
        try (JarFile zin = new JarFile(jar)) {
            Enumeration<JarEntry> it = zin.entries();
            while (it.hasMoreElements()) existing.add(it.nextElement().getName());
        }
        String targetDir = "models/Toy Models/Android Sensors/";
        int idx = 0;
        while ((idx = targetDir.indexOf('/', idx + 1)) > 0) {
            String dir = targetDir.substring(0, idx + 1);
            if (!existing.contains(dir)) injected.put(dir, null);
        }

        for (String name : MODELS) {
            File f = new File(modelsDir, name);
            if (f.exists()) {
                injected.put("models/Toy Models/Android Sensors/" + name, f);
            }
        }

        try (JarFile zin = new JarFile(jar);
             JarOutputStream zos = new JarOutputStream(new FileOutputStream(tmp))) {
            Enumeration<JarEntry> it = zin.entries();
            while (it.hasMoreElements()) {
                JarEntry e = it.nextElement();
                if (injected.containsKey(e.getName())) continue;
                zos.putNextEntry(e);
                if (!e.isDirectory()) {
                    try (InputStream is = zin.getInputStream(e)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
            }
            for (Map.Entry<String, File> en : injected.entrySet()) {
                String name = en.getKey();
                File source = en.getValue();
                JarEntry out = new JarEntry(name);
                if (source == null) out.setTime(0);
                zos.putNextEntry(out);
                if (source != null) {
                    try (InputStream is = new FileInputStream(source)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
                System.out.println("injectLibraryModels: added " + name);
            }
        }
        Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}