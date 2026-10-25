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
import steambridge.core.NamedField;

final class GuiFields {
    static final NamedField<EditBox> EDITOR_IP = new NamedField<>(EditServerScreen.class, EditBox.class, "ipEdit", "f_96010_");
    static final NamedField<EditBox> EDITOR_NAME = new NamedField<>(EditServerScreen.class, EditBox.class, "nameEdit", "f_96011_");
    static final NamedField<ServerData> EDITOR_SERVER = new NamedField<>(EditServerScreen.class, ServerData.class, "serverData", "f_96009_");
    static final NamedField<Screen> DIRECT_PARENT = new NamedField<>(DirectJoinServerScreen.class, Screen.class, "lastScreen", "f_95957_");
    static final NamedField<EditBox> DIRECT_IP = new NamedField<>(DirectJoinServerScreen.class, EditBox.class, "ipEdit", "f_95955_");
    static final NamedField<Boolean> CONNECT_ABORTED = new NamedField<>(ConnectScreen.class, Boolean.class, "aborted", "f_95685_");
    static final NamedField<Screen> CONNECT_PARENT = new NamedField<>(ConnectScreen.class, Screen.class, "parent", "f_95686_");
    static final NamedField<Button.OnPress> BUTTON_PRESS = new NamedField<>(Button.class, Button.OnPress.class, "onPress", "f_93717_");
    static final NamedField<GameType> LAN_MODE = new NamedField<>(ShareToLanScreen.class, GameType.class, "gameMode", "f_169427_");
    static final NamedField<Boolean> LAN_COMMANDS = new NamedField<>(ShareToLanScreen.class, Boolean.class, "commands", "f_96647_");
    static final NamedField<ServerSelectionList> SERVER_LIST = new NamedField<>(JoinMultiplayerScreen.class, ServerSelectionList.class, "serverSelectionList", "f_99673_");
    static final NamedField<Connection> CONNECT_CONNECTION = new NamedField<>(ConnectScreen.class, Connection.class, "connection", "f_95684_");

    private GuiFields() {}

    static <T> T read(NamedField<T> field, Object owner) {
        try {
            return field.get(owner);
        } catch (RuntimeException failure) {
            SteamBridgeMod.LOG.warn("GUI field access failed: {}", field, failure);
            return null;
        }
    }
}
