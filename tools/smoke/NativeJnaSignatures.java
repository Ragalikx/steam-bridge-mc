package steambridge.steam;

import com.sun.jna.Callback;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.Structure;
import com.sun.jna.ptr.ByReference;

import java.lang.reflect.Method;

public final class NativeJnaSignatures {
    private static int type(Class<?> type) {
        if (type == void.class) return 0;
        if (Pointer.class.isAssignableFrom(type)
                || PointerType.class.isAssignableFrom(type)
                || ByReference.class.isAssignableFrom(type)
                || Callback.class.isAssignableFrom(type)
                || type == String.class) return 1;
        if (Structure.class.isAssignableFrom(type)
                && !Structure.ByValue.class.isAssignableFrom(type)) return 1;
        if (type == byte.class) return 2;
        if (type == int.class || (type == boolean.class && Native.getNativeSize(type) == 4))
            return 3;
        if (type == long.class) return 4;
        if (type == float.class) return 5;
        throw new IllegalArgumentException("Unsupported JNA ABI type: " + type);
    }

    private static void signature(String name, Method method) {
        System.out.println("function." + name + ".result=" + type(method.getReturnType()));
        Class<?>[] arguments = method.getParameterTypes();
        System.out.println("function." + name + ".count=" + arguments.length);
        for (int i = 0; i < arguments.length; i++)
            System.out.println("function." + name + ".arg" + i + "=" + type(arguments[i]));
    }

    public static void main(String[] args) throws Exception {
        ClassLoader loader = NativeJnaSignatures.class.getClassLoader();
        Class<?> api = Class.forName("steambridge.steam.SteamSocketsApi$FlatApi", false, loader);
        for (Method method : api.getDeclaredMethods()) signature(method.getName(), method);
        Class<?> callback =
                Class.forName(
                        "steambridge.steam.SteamSocketsApi$ConnectionStatusChangedCallback",
                        false,
                        loader);
        signature(
                "FnSteamNetConnectionStatusChanged",
                callback.getDeclaredMethod("invoke", Pointer.class));
    }
}
