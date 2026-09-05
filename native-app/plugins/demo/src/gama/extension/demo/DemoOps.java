package gama.extension.demo;

import gama.api.runtime.scope.IScope;

public class DemoOps {

    public static Integer demoSquare(final IScope scope, final int x) {
        return x * x;
    }

    public static Integer demoCube(final IScope scope, final int x) {
        return x * x * x;
    }

    public static String demoGreet(final IScope scope, final String name) {
        return "Hello, " + name + "!";
    }
}