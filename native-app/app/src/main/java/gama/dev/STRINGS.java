package gama.dev;

public class STRINGS {

    private STRINGS() {
    }

    public static String PAD(final String s, final int n) {
        return PAD(s, n, ' ');
    }

    public static String PAD(final String s, final int n, final char c) {
        if (s == null || s.length() >= n) {
            return s;
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < n; i++) {
            sb.append(c);
        }
        sb.append(s);
        return sb.toString();
    }
}
