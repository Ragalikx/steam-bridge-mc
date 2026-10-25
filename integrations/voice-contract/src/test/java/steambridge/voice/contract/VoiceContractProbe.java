package steambridge.voice.contract;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class VoiceContractProbe {
    private static boolean api(String name, String prefix) {
        return name != null && name.startsWith(prefix);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static final class Index implements AutoCloseable {
        final ZipFile jar;
        final Map<String, ClassNode> classes = new HashMap<>();

        Index(String file) throws Exception {
            jar = new ZipFile(file);
        }

        ClassNode get(String name) throws Exception {
            if (name == null) return null;
            if (classes.containsKey(name)) return classes.get(name);
            ZipEntry entry = jar.getEntry(name + ".class");
            if (entry == null && name.equals("java/lang/Object")) {
                try (InputStream input = Object.class.getResourceAsStream("Object.class")) {
                    ClassNode node = new ClassNode();
                    new ClassReader(input)
                            .accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    classes.put(name, node);
                    return node;
                }
            }
            if (entry == null) return null;
            try (InputStream input = jar.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(input)
                        .accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                classes.put(name, node);
                return node;
            }
        }

        MethodNode method(String owner, String name, String descriptor, Set<String> seen)
                throws Exception {
            if (owner == null || !seen.add(owner)) return null;
            ClassNode node = get(owner);
            if (node == null) return null;
            for (MethodNode method : node.methods)
                if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
            if (name.equals("<init>")) return null;
            MethodNode found = method(node.superName, name, descriptor, seen);
            if (found != null) return found;
            for (String parent : node.interfaces) {
                found = method(parent, name, descriptor, seen);
                if (found != null) return found;
            }
            return null;
        }

        FieldNode field(String owner, String name, String descriptor, Set<String> seen)
                throws Exception {
            if (owner == null || !seen.add(owner)) return null;
            ClassNode node = get(owner);
            if (node == null) return null;
            for (FieldNode field : node.fields)
                if (field.name.equals(name) && field.desc.equals(descriptor)) return field;
            FieldNode found = field(node.superName, name, descriptor, seen);
            if (found != null) return found;
            for (String parent : node.interfaces) {
                found = field(parent, name, descriptor, seen);
                if (found != null) return found;
            }
            return null;
        }

        public void close() throws Exception {
            jar.close();
        }
    }

    private static void requireType(Index jar, String name, String prefix) throws Exception {
        if (api(name, prefix) && jar.get(name) == null)
            throw new IllegalStateException(
                    "Missing API class: " + name + " in " + jar.jar.getName());
    }

    private static void descriptor(Index jar, String descriptor, String prefix) throws Exception {
        Type type = Type.getType(descriptor);
        if (type.getSort() == Type.METHOD) {
            descriptor(jar, type.getReturnType().getDescriptor(), prefix);
            for (Type argument : type.getArgumentTypes())
                descriptor(jar, argument.getDescriptor(), prefix);
        } else if (type.getSort() == Type.ARRAY)
            descriptor(jar, type.getElementType().getDescriptor(), prefix);
        else if (type.getSort() == Type.OBJECT) requireType(jar, type.getInternalName(), prefix);
    }

    private static MethodNode implementation(
            Map<String, ClassNode> own,
            Index api,
            String owner,
            String name,
            String descriptor,
            Set<String> seen)
            throws Exception {
        if (owner == null || !seen.add(owner)) return null;
        ClassNode node = own.get(owner);
        if (node == null) node = api.get(owner);
        if (node == null) return null;
        for (MethodNode method : node.methods)
            if (method.name.equals(name)
                    && method.desc.equals(descriptor)
                    && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == 0)
                return method;
        MethodNode found = implementation(own, api, node.superName, name, descriptor, seen);
        if (found != null) return found;
        for (String parent : node.interfaces) {
            found = implementation(own, api, parent, name, descriptor, seen);
            if (found != null) return found;
        }
        return null;
    }

    private static int interfaceContract(
            Map<String, ClassNode> own,
            Index jar,
            ClassNode adapter,
            String iface,
            String prefix,
            Set<String> seen)
            throws Exception {
        if (!api(iface, prefix) || !seen.add(iface)) return 0;
        ClassNode node = jar.get(iface);
        if (node == null) throw new IllegalStateException("Missing API interface: " + iface);
        int checks = 0;
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_ABSTRACT) == 0) continue;
            if (implementation(own, jar, adapter.name, method.name, method.desc, new HashSet<>())
                    == null)
                throw new IllegalStateException(
                        "Missing adapter implementation: "
                                + adapter.name
                                + "."
                                + method.name
                                + method.desc
                                + " required by "
                                + iface);
            checks++;
        }
        for (String parent : node.interfaces)
            checks += interfaceContract(own, jar, adapter, parent, prefix, seen);
        return checks;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2)
            throw new IllegalArgumentException("Compiled adapters and voice JARs required");
        Map<String, ClassNode> own = new HashMap<>();
        try (java.util.stream.Stream<Path> files =
                Files.walk(Paths.get(args[0], "steambridge", "voice"))) {
            for (Path path :
                    (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                ClassNode node = read(Files.readAllBytes(path));
                own.put(node.name, node);
            }
        }
        for (int index = 1; index < args.length; index++) {
            try (Index jar = new Index(args[index])) {
                boolean plasmo = jar.get("su/plo/voice/api/client/socket/UdpClient") != null;
                String prefix = plasmo ? "su/plo/voice/" : "de/maxhenkel/voicechat/api/";
                if (plasmo) requireType(jar, "su/plo/voice/client/socket/NettyUdpClient", prefix);
                else requireType(jar, "de/maxhenkel/voicechat/api/ForgeVoicechatPlugin", prefix);
                int methods = 0, fields = 0, interfaces = 0;
                for (ClassNode node : own.values()) {
                    if (node.visibleAnnotations != null)
                        for (AnnotationNode annotation : node.visibleAnnotations)
                            descriptor(jar, annotation.desc, prefix);
                    for (FieldNode field : node.fields) descriptor(jar, field.desc, prefix);
                    for (String iface : node.interfaces)
                        interfaces +=
                                interfaceContract(own, jar, node, iface, prefix, new HashSet<>());
                    for (MethodNode method : node.methods) {
                        descriptor(jar, method.desc, prefix);
                        for (AbstractInsnNode instruction : method.instructions) {
                            if (instruction instanceof MethodInsnNode) {
                                MethodInsnNode call = (MethodInsnNode) instruction;
                                if (!api(call.owner, prefix)) continue;
                                MethodNode target =
                                        jar.method(
                                                call.owner, call.name, call.desc, new HashSet<>());
                                if (target == null
                                        || (target.access & Opcodes.ACC_PUBLIC) == 0
                                        || ((target.access & Opcodes.ACC_STATIC) != 0)
                                                != (call.getOpcode() == Opcodes.INVOKESTATIC))
                                    throw new IllegalStateException(
                                            "Missing/incompatible API: "
                                                    + call.owner
                                                    + "."
                                                    + call.name
                                                    + call.desc
                                                    + " in "
                                                    + jar.jar.getName());
                                methods++;
                            } else if (instruction instanceof FieldInsnNode) {
                                FieldInsnNode access = (FieldInsnNode) instruction;
                                if (!api(access.owner, prefix)) continue;
                                FieldNode target =
                                        jar.field(
                                                access.owner,
                                                access.name,
                                                access.desc,
                                                new HashSet<>());
                                boolean staticAccess =
                                        access.getOpcode() == Opcodes.GETSTATIC
                                                || access.getOpcode() == Opcodes.PUTSTATIC;
                                if (target == null
                                        || (target.access & Opcodes.ACC_PUBLIC) == 0
                                        || ((target.access & Opcodes.ACC_STATIC) != 0)
                                                != staticAccess)
                                    throw new IllegalStateException(
                                            "Missing API field: "
                                                    + access.owner
                                                    + "."
                                                    + access.name);
                                fields++;
                            } else if (instruction instanceof TypeInsnNode)
                                requireType(jar, ((TypeInsnNode) instruction).desc, prefix);
                        }
                    }
                }
                System.out.println(
                        "Voice API contract passed: methods="
                                + methods
                                + " fields="
                                + fields
                                + " implementations="
                                + interfaces
                                + " "
                                + jar.jar.getName());
            }
        }
    }
}
