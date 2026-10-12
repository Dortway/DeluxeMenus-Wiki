package dev.exo.cleanwipe.integrations.live;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Minimal reflection used to reach the in-memory state of the installed Exo plugins, which have no public API for
 * removing a player's data. Every member name used here exists in the installed versions (checked against the
 * decompiled jars). If a plugin is updated and a member is gone, the call throws and the step is moved to the next
 * server start instead of guessing.
 */
final class Reflect {

    private Reflect() {
    }

    static Object field(Object target, String name) throws ReflectiveOperationException {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }

    /** Calls the first method with this name and parameter count whose parameters accept the arguments. */
    static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        Class<?> c = target.getClass();
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length || !accepts(m.getParameterTypes(), args)) {
                    continue;
                }
                m.setAccessible(true);
                try {
                    return m.invoke(target, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception ex) {
                        throw new ReflectiveOperationException(name + " failed: " + ex.getMessage(), ex);
                    }
                    throw e;
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name + "/" + args.length);
    }

    private static boolean accepts(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Object a = args[i];
            Class<?> t = wrap(types[i]);
            if (a != null && !t.isInstance(a)) {
                return false;
            }
            if (a == null && types[i].isPrimitive()) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> t) {
        if (!t.isPrimitive()) {
            return t;
        }
        if (t == boolean.class) {
            return Boolean.class;
        }
        if (t == long.class) {
            return Long.class;
        }
        if (t == int.class) {
            return Integer.class;
        }
        if (t == double.class) {
            return Double.class;
        }
        return t;
    }

    /** An enum constant of a class loaded by another plugin. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object enumConstant(ClassLoader loader, String className, String constant) throws ReflectiveOperationException {
        Class cls = Class.forName(className, true, loader);
        return Enum.valueOf(cls, constant);
    }
}
