import java.lang.classfile.ClassFile;
import java.util.zip.ZipFile;

public final class VerifyGuiFields {
    public static void main(String[] args) throws Exception {
        try (ZipFile jar = new ZipFile(args[0])) {
            for (int i = 1; i < args.length; i += 3) {
                String owner = args[i], name = args[i + 1], descriptor = args[i + 2];
                var entry = jar.getEntry(owner + ".class");
                if (entry == null) throw new IllegalStateException("Missing GUI class: " + owner);
                var model = ClassFile.of().parse(jar.getInputStream(entry).readAllBytes());
                if (model.fields().stream()
                        .noneMatch(
                                field ->
                                        field.fieldName().equalsString(name)
                                                && field.fieldType().equalsString(descriptor))) {
                    throw new IllegalStateException(
                            "Missing GUI field: " + owner + "." + name + descriptor);
                }
            }
        }
        System.out.println("Verified GUI fields: " + (args.length - 1) / 3);
    }
}
