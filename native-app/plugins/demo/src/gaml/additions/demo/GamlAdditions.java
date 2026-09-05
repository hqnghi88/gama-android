package gaml.additions.demo;

import static gama.annotations.support.ITypeProvider.NONE;

import java.lang.reflect.Method;

import gama.api.additions.AbstractGamlAdditions;
import gama.extension.demo.DemoOps;

@SuppressWarnings("unused")
public class GamlAdditions extends AbstractGamlAdditions {

    public void initialize() throws NoSuchMethodException {
        initializeOperators();
    }

    private void initializeOperators() throws NoSuchMethodException {
        Method square = DemoOps.class.getMethod("demoSquare", SC, i);
        _operator(S("demo_square"), square, null, AI, I, F, NONE, NONE, NONE, NONE,
                (s, o) -> DemoOps.demoSquare(s, ((Integer) o[0]).intValue()), F);

        Method cube = DemoOps.class.getMethod("demoCube", SC, i);
        _operator(S("demo_cube"), cube, null, AI, I, F, NONE, NONE, NONE, NONE,
                (s, o) -> DemoOps.demoCube(s, ((Integer) o[0]).intValue()), F);

        Method greet = DemoOps.class.getMethod("demoGreet", SC, S);
        _operator(S("demo_greet"), greet, null, AI, S, F, NONE, NONE, NONE, NONE,
                (s, o) -> DemoOps.demoGreet(s, (String) o[0]), F);
    }
}