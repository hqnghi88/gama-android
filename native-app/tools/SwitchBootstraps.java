package java.lang.runtime;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class SwitchBootstraps {

    @SuppressWarnings("unused")
    public static CallSite typeSwitch(MethodHandles.Lookup lookup, String invocationName,
            MethodType invocationType, Object... labels) throws Exception {
        int paramCount = invocationType.parameterCount();
        int returnType = invocationType.returnType() == int.class ? 1 : 0;
        if (paramCount != 2 || returnType != 1) {
            throw new IllegalArgumentException("Illegal invocation type for typeSwitch: " + invocationType);
        }
        Object[] labelsCopy = labels.clone();
        for (Object label : labelsCopy) {
            if (label == null) continue;
            if (!(label instanceof Class) && !(label instanceof String)) {
                throw new IllegalArgumentException("Label must be a Class or String, got: " + label.getClass());
            }
        }
        java.lang.invoke.MethodHandle handle = java.lang.invoke.MethodHandles.insertArguments(
            java.lang.invoke.MethodHandles.lookup().findStatic(
                SwitchBootstraps.class, "doTypeSwitch",
                MethodType.methodType(int.class, Object[].class, Object.class)),
            0, (Object) labelsCopy);
        return new ConstantCallSite(handle);
    }

    public static int doTypeSwitch(Object[] labels, Object obj, int match) {
        for (int i = match; i < labels.length; i++) {
            Object label = labels[i];
            if (label == null) {
                if (obj == null) return i;
            } else if (label instanceof Class<?> c) {
                if (c.isInstance(obj)) return i;
            } else if (label instanceof String s) {
                if (obj != null && obj.getClass().getName().equals(s)) return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unused")
    public static CallSite enumSwitch(MethodHandles.Lookup lookup, String invocationName,
            MethodType invocationType, Object... labels) throws Exception {
        throw new UnsupportedOperationException("enumSwitch not supported on Android");
    }
}
