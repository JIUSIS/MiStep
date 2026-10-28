package de.robv.android.xposed;
import java.lang.reflect.*;
public class XposedBridge {
    public static int errors;
    public static void log(String message) {}
    public static void log(Throwable error) { errors++; }
    public static Object invokeOriginalMethod(Member method, Object receiver, Object[] args)
            throws Throwable {
        ((Method) method).setAccessible(true);
        return ((Method) method).invoke(receiver, args);
    }
}