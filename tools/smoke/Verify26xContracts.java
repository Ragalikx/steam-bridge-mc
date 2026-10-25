import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.zip.ZipFile;

public final class Verify26xContracts {
    private static ClassModel read(ZipFile jar, String name) throws Exception {
        var entry = jar.getEntry(name + ".class");
        if (entry == null) throw new IllegalStateException("Missing Minecraft class " + name);
        return ClassFile.of().parse(jar.getInputStream(entry).readAllBytes());
    }

    private static MethodModel method(ClassModel owner, String name, String descriptor) {
        return owner.methods().stream()
                .filter(
                        m ->
                                m.methodName().equalsString(name)
                                        && m.methodType().equalsString(descriptor))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Missing target "
                                                + owner.thisClass().asInternalName()
                                                + "."
                                                + name
                                                + descriptor));
    }

    private static void field(ClassModel owner, String name, String descriptor) {
        if (owner.fields().stream()
                .noneMatch(
                        f ->
                                f.fieldName().equalsString(name)
                                        && f.fieldType().equalsString(descriptor)))
            throw new IllegalStateException(
                    "Missing field " + owner.thisClass().asInternalName() + "." + name);
    }

    public static void main(String[] args) throws Exception {
        try (ZipFile jar = new ZipFile(args[0])) {
            var connect = read(jar, "net/minecraft/client/gui/screens/ConnectScreen");
            field(connect, "aborted", "Z");
            field(connect, "parent", "Lnet/minecraft/client/gui/screens/Screen;");
            field(
                    read(jar, "net/minecraft/client/gui/components/Button"),
                    "onPress",
                    "Lnet/minecraft/client/gui/components/Button$OnPress;");
            for (String screen : new String[] {"ManageServerScreen", "DirectJoinServerScreen"}) {
                var editor = read(jar, "net/minecraft/client/gui/screens/" + screen);
                field(editor, "ipEdit", "Lnet/minecraft/client/gui/components/EditBox;");
                field(editor, "serverData", "Lnet/minecraft/client/multiplayer/ServerData;");
                field(editor, "lastScreen", "Lnet/minecraft/client/gui/screens/Screen;");
            }
            field(
                    read(jar, "net/minecraft/client/gui/screens/ManageServerScreen"),
                    "nameEdit",
                    "Lnet/minecraft/client/gui/components/EditBox;");
            field(
                    read(jar, "net/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen"),
                    "serverSelectionList",
                    "Lnet/minecraft/client/gui/screens/multiplayer/ServerSelectionList;");
            method(
                    read(
                            jar,
                            "net/minecraft/client/gui/screens/multiplayer/ServerSelectionList$OnlineServerEntry"),
                    "getServerData",
                    "()Lnet/minecraft/client/multiplayer/ServerData;");
            method(
                    connect,
                    "startConnecting",
                    "(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/TransferState;)V");
            var login = read(jar, "net/minecraft/server/network/ServerLoginPacketListenerImpl");
            var hello =
                    method(
                            login,
                            "handleHello",
                            "(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V");
            field(login, "connection", "Lnet/minecraft/network/Connection;");
            field(login, "server", "Lnet/minecraft/server/MinecraftServer;");
            boolean redirect =
                    hello.code()
                            .orElseThrow()
                            .elementStream()
                            .anyMatch(
                                    element ->
                                            element instanceof InvokeInstruction call
                                                    && call.owner()
                                                            .asInternalName()
                                                            .equals(
                                                                    "net/minecraft/server/MinecraftServer")
                                                    && call.name()
                                                            .equalsString("getSingleplayerProfile")
                                                    && call.type()
                                                            .equalsString(
                                                                    "()Lcom/mojang/authlib/GameProfile;"));
            if (!redirect) throw new IllegalStateException("Login redirect invocation changed");
            field(
                    read(jar, "net/minecraft/client/Minecraft"),
                    "pendingConnection",
                    "Lnet/minecraft/network/Connection;");
            field(
                    read(jar, "net/minecraft/server/network/ServerCommonPacketListenerImpl"),
                    "connection",
                    "Lnet/minecraft/network/Connection;");
            String screen =
                    args[1].equals("26.1")
                            ? "ShareToLanScreen"
                            : args[1].equals("26.2")
                                    ? "MultiplayerOptionsScreen"
                                    : "WorldOptionsScreen";
            var lan = read(jar, "net/minecraft/client/gui/screens/" + screen);
            field(lan, "port", "I");
            field(lan, "portEdit", "Lnet/minecraft/client/gui/components/EditBox;");
            field(
                    lan,
                    args[1].equals("26.3") ? "wantedDefaultGameMode" : "gameMode",
                    "Lnet/minecraft/world/level/GameType;");
            field(
                    lan,
                    args[1].equals("26.3") ? "wantedGuestCommandAccess" : "commands",
                    args[1].equals("26.3") ? "Ljava/lang/Boolean;" : "Z");
            System.out.println("Minecraft hooks and reflective field contracts passed: " + args[1]);
        }
    }
}
