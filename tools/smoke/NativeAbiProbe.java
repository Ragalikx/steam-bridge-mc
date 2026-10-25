package steambridge.steam;
import com.sun.jna.Structure;
import java.lang.reflect.*;
public final class NativeAbiProbe {
    public static void main(String[] args) throws Exception {
        Method offset = Structure.class.getDeclaredMethod("fieldOffset", String.class);
        offset.setAccessible(true);
        for (Class<?> type : SteamOffsets.class.getDeclaredClasses()) {
            if (!Structure.class.isAssignableFrom(type)) continue;
            Structure instance = (Structure) type.newInstance();
            System.out.println(type.getSimpleName() + ".size=" + instance.size());
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Modifier.isPublic(field.getModifiers())) continue;
                System.out.println(type.getSimpleName() + "." + field.getName() + "=" + offset.invoke(instance, field.getName()));
            }
        }
    }
}
