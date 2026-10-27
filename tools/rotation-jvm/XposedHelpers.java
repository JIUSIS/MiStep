package de.robv.android.xposed;
import java.lang.reflect.*;
public class XposedHelpers {
    public static Object callMethod(Object receiver, String name, Object... args) {
        return invoke(receiver.getClass(), receiver, name, args);
    }
    public static Object callStaticMethod(Class<?> type, String name, Object... args) {
        return invoke(type, null, name, args);
    }
    private static Object invoke(Class<?> type, Object receiver, String name, Object[] args) {
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            try { method.setAccessible(true); return method.invoke(receiver, args); }
            catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
        }
        throw new IllegalArgumentException("missing " + type.getName() + "." + name);
    }
    public static Object getObjectField(Object receiver, String name) {
        try { Field field = receiver.getClass().getDeclaredField(name);
            field.setAccessible(true); return field.get(receiver);
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
    public static int getIntField(Object receiver, String name) {
        return (Integer) getObjectField(receiver, name);
    }
    public static void setIntField(Object receiver, String name, int value) {
        setObjectField(receiver, name, value);
    }
    public static void setObjectField(Object receiver, String name, Object value) {
        try { Field field = receiver.getClass().getDeclaredField(name);
            field.setAccessible(true); field.set(receiver, value);
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
}
