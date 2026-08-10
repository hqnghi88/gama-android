package gama.dev;

import java.util.concurrent.atomic.AtomicLong;

public class COUNTER {

    private static final AtomicLong counter = new AtomicLong(0);

    private COUNTER() {
    }

    public static Long COUNT() {
        return counter.incrementAndGet();
    }

    public static long GET_UNIQUE() {
        return counter.incrementAndGet();
    }
}
