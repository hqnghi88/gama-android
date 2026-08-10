package gama.dev;

import java.io.OutputStream;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Android stub for the missing gama.dev plugin.
 *
 * <p>The GAMA jars shipped in app/libs reference gama.dev.DEBUG (and the other
 * gama.dev helpers) but do not bundle them (the gama.dev bundle is not part of
 * this distribution). Without these classes, loading {@code gama.api.gaml.types.Types}
 * fails with a NoClassDefFoundError and the whole engine is unusable. This class
 * provides a minimal, behaviour-preserving implementation: logging is disabled by
 * default (as on desktop) and timer helpers simply run their payloads.</p>
 */
public class DEBUG {

    private static boolean IS_ACTIVE = false;

    private DEBUG() {
    }

    public static void OFF() {
        IS_ACTIVE = false;
    }

    public static void ON() {
        IS_ACTIVE = true;
    }

    public static void FORCE_ON() {
        IS_ACTIVE = true;
    }

    public static boolean IS_ON() {
        return IS_ACTIVE;
    }

    public static void LINE() {
        if (IS_ACTIVE) {
            System.out.println();
        }
    }

    public static void LOG(final Object o) {
        if (IS_ACTIVE) {
            System.out.println(o);
        }
    }

    public static void LOG(final Object o, final boolean newLine) {
        if (IS_ACTIVE) {
            System.out.println(o);
        }
    }

    public static void OUT(final Object o) {
        if (IS_ACTIVE) {
            System.out.println(o);
        }
    }

    public static void OUT(final Object o, final boolean newLine) {
        if (IS_ACTIVE) {
            System.out.println(o);
        }
    }

    public static void ERR(final Object o) {
        System.err.println(o);
    }

    public static void ERR(final Object o, final Throwable t) {
        System.err.println(o);
        if (t != null) {
            t.printStackTrace();
        }
    }

    public static void REGISTER_LOG_WRITER(final OutputStream out) {
    }

    public static void UNREGISTER_LOG_WRITER() {
    }

    public static void SECTION(final String s) {
        if (IS_ACTIVE) {
            System.out.println("--- " + s);
        }
    }

    public static void TITLE(final String s) {
        if (IS_ACTIVE) {
            System.out.println("== " + s + " ==");
        }
    }

    public static void BANNER(final BANNER_CATEGORY category, final String title, final String sub, final String footer) {
        if (IS_ACTIVE) {
            System.out.println("== " + title + " ==");
        }
    }

    public static void TIMER(final BANNER_CATEGORY category, final String title, final String label,
            final Runnable r, final Consumer<?>... out) {
        if (r != null) {
            r.run();
        }
    }

    public static Object TIMER(final BANNER_CATEGORY category, final String title, final String label,
            final Supplier<?> s) {
        return s != null ? s.get() : null;
    }

    public static void TIMER_WITH_EXCEPTIONS(final BANNER_CATEGORY category, final String title, final String label,
            final RunnableWithException r) throws Exception {
        if (r != null) {
            r.run();
        }
    }

    @FunctionalInterface
    public interface RunnableWithException {
        void run() throws Exception;
    }
}
