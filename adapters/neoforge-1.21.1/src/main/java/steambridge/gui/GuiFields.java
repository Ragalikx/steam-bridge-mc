package steambridge.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.EditServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.world.level.GameType;
import steambridge.SteamBridgeMod;
import steambridge.core.LogLimiter;
import steambridge.core.NamedField;
import steambridge.core.ProblemLog;

final class GuiFields {
    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);
    static final NamedField<EditBox> EDITOR_IP = new NamedField<>(EditServerScreen.class, EditBox.class, "ipEdit");
    static final NamedField<EditBox> EDITOR_NAME = new NamedField<>(EditServerScreen.class, EditBox.class, "nameEdit");
    static final NamedField<ServerData> EDITOR_SERVER = new NamedField<>(EditServerScreen.class, ServerData.class, "serverData");
    static final NamedField<Screen> DIRECT_PARENT = new NamedField<>(DirectJoinServerScreen.class, Screen.class, "lastScreen");
    static final NamedField<EditBox> DIRECT_IP = new NamedField<>(DirectJoinServerScreen.class, EditBox.class, "ipEdit");
    static final NamedField<Boolean> CONNECT_ABORTED = new NamedField<>(ConnectScreen.class, Boolean.class, "aborted");
    static final NamedField<Screen> CONNECT_PARENT = new NamedField<>(ConnectScreen.class, Screen.class, "parent");
    static final NamedField<Button.OnPress> BUTTON_PRESS = new NamedField<>(Button.class, Button.OnPress.class, "onPress");
    static final NamedField<GameType> LAN_MODE = new NamedField<>(ShareToLanScreen.class, GameType.class, "gameMode");
    static final NamedField<Boolean> LAN_COMMANDS = new NamedField<>(ShareToLanScreen.class, Boolean.class, "commands");
    static final NamedField<ServerSelectionList> SERVER_LIST = new NamedField<>(JoinMultiplayerScreen.class, ServerSelectionList.class, "serverSelectionList");
    static final NamedField<Integer> LAN_PORT = new NamedField<>(ShareToLanScreen.class, Integer.class, "port");
    static final NamedField<EditBox> LAN_PORT_EDIT = new NamedField<>(ShareToLanScreen.class, EditBox.class, "portEdit");
    static final NamedField<Connection> CONNECT_CONNECTION = new NamedField<>(ConnectScreen.class, Connection.class, "connection");

    private GuiFields() {}

    static <T> T read(NamedField<T> field, Object owner) {
        try {
            return field.get(owner);
        } catch (RuntimeException failure) {
            ProblemLog.warn(SteamBridgeMod.LOG, LOG_LIMITER, "gui-field-access",
                    "GUI field access failed: {}", field, failure);
            return null;
        }
    }
}
