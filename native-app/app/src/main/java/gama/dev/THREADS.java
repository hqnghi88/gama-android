package gama.dev;

public class THREADS {

    private THREADS() {
    }

    public static boolean WAIT(final long ms, final String... reasons) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
