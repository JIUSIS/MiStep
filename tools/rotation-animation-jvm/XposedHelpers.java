package de.robv.android.xposed;
import java.lang.reflect.*;

public class XposedHelpers {
    private static Class<?> boxed(Class<?> c) {
        if (c==int.class) return Integer.class;
        if (c==float.class) return Float.class;
        if (c==boolean.class) return Boolean.class;
        if (c==long.class) return Long.class;
        return c;
    }
    private static boolean matches(Class<?>[] types, Object[] args) {
        if (types.length!=args.length) return false;
        for (int i=0;i<types.length;i++) if (args[i]!=null && !boxed(types[i]).isInstance(args[i])) return false;
        return true;
    }
    private static RuntimeException failed(Throwable e) {
        if (e instanceof InvocationTargetException) e=((InvocationTargetException)e).getCause();
        if (e instanceof Error) throw (Error)e;
        if (e instanceof RuntimeException) return (RuntimeException)e;
        return new IllegalStateException(e);
    }
    private static Field field(Class<?> c,String n) {
        for (Class<?> t=c;t!=null;t=t.getSuperclass()) {
            try { Field f=t.getDeclaredField(n); f.setAccessible(true); return f; }
            catch (NoSuchFieldException absent) {}
        }
        throw new NoSuchFieldError(n);
    }
    public static Object getObjectField(Object o,String n) { try { return field(o.getClass(),n).get(o); } catch (Exception e) { throw failed(e); } }
    public static int getIntField(Object o,String n) { return ((Number)getObjectField(o,n)).intValue(); }
    public static boolean getBooleanField(Object o,String n) { return (Boolean)getObjectField(o,n); }
    public static void setObjectField(Object o,String n,Object v) { try { field(o.getClass(),n).set(o,v); } catch(Exception e) { throw failed(e); } }
    public static void setIntField(Object o,String n,int v) { setObjectField(o,n,v); }
    public static Object callMethod(Object o,String n,Object... args) {
        for (Class<?> c=o.getClass();c!=null;c=c.getSuperclass()) for (Method m:c.getDeclaredMethods()) {
            if (!m.getName().equals(n) || !matches(m.getParameterTypes(),args)) continue;
            try { m.setAccessible(true); return m.invoke(o,args); } catch(Exception e) { throw failed(e); }
        }
        throw new NoSuchMethodError(o.getClass()+"."+n);
    }
    public static Class<?> findClass(String n,ClassLoader l) { try { return Class.forName(n,true,l); } catch(Exception e) { throw failed(e); } }
    public static Object getStaticObjectField(Class<?> c,String n) { try { return field(c,n).get(null); } catch(Exception e) { throw failed(e); } }
    public static Object newInstance(Class<?> c,Object... args) {
        for (Constructor<?> k:c.getDeclaredConstructors()) if (matches(k.getParameterTypes(),args)) {
            try { k.setAccessible(true); return k.newInstance(args); } catch(Exception e) { throw failed(e); }
        }
        throw new NoSuchMethodError(c+" constructor");
    }
    public static Object callStaticMethod(Class<?> c,String n,Object... args) {
        for(Method m:c.getDeclaredMethods()) if(m.getName().equals(n)&&matches(m.getParameterTypes(),args)) {
            try { m.setAccessible(true); return m.invoke(null,args); } catch(Exception e) { throw failed(e); }
        }
        throw new NoSuchMethodError(n);
    }
}
