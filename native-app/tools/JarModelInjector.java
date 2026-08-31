import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
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
                zos.putNextEntry(new JarEntry(en.getKey()));
                try (InputStream is = new FileInputStream(en.getValue())) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
                System.out.println("injectLibraryModels: added " + en.getKey());
            }
        }
        Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}