import org.objectweb.asm.*;

import java.nio.file.*;

public final class GenerateRuntimeLoader implements Opcodes {
    private static final String ID = "steambridge_linux_runtime";

    private static void annotation(
            ClassWriter writer, String type, String version, boolean legacy, boolean neoforge) {
        AnnotationVisitor value = writer.visitAnnotation("L" + type + ";", true);
        value.visit("value", ID);
        if (neoforge) {
            // Client only: on a dedicated server without SBM the bootstrap class would not link.
            AnnotationVisitor dist = value.visitArray("dist");
            dist.visitEnum(null, "Lnet/neoforged/api/distmarker/Dist;", "CLIENT");
            dist.visitEnd();
        }
        if (legacy) {
            // FML 1.7.10-1.12.2 registers a network holder for every @Mod, clientSideOnly included.
            // Without "*" a Linux LAN host with this addon rejects guests that do not need it.
            value.visit("acceptableRemoteVersions", "*");
            value.visit("modid", ID);
            value.visit("name", "SBM Linux Runtime");
            value.visit("version", version);
            value.visit("dependencies", "required-after:steambridge");
            value.visit("acceptedMinecraftVersions", "[1.7.10],[1.8.9],[1.12.2]");
            value.visit("clientSideOnly", true);
        }
        value.visitEnd();
    }

    public static void main(String[] args) throws Exception {
        int java = Integer.parseInt(args[0]);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                java + 44,
                ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
                "steambridge/linuxruntime/RuntimeLoader",
                null,
                "java/lang/Object",
                null);
        if (java == 8) annotation(writer, "cpw/mods/fml/common/Mod", args[1], true, false);
        annotation(
                writer,
                java == 21 ? "net/neoforged/fml/common/Mod" : "net/minecraftforge/fml/common/Mod",
                args[1],
                java == 8,
                java == 21);
        MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitMethodInsn(
                INVOKESTATIC,
                "steambridge/linuxruntime/RuntimeBootstrap",
                "initialize",
                "()V",
                false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        writer.visitEnd();
        Path output = Paths.get(args[2], "steambridge/linuxruntime/RuntimeLoader.class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
