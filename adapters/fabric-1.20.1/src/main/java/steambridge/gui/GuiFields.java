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
    static final NamedField<EditBox> EDITOR_IP = new NamedField<>(EditServerScreen.class, EditBox.class, "ipEdit", "field_2474");
    static final NamedField<EditBox> EDITOR_NAME = new NamedField<>(EditServerScreen.class, EditBox.class, "nameEdit", "field_2471");
    static final NamedField<ServerData> EDITOR_SERVER = new NamedField<>(EditServerScreen.class, ServerData.class, "serverData", "field_2469");
    static final NamedField<Screen> DIRECT_PARENT = new NamedField<>(DirectJoinServerScreen.class, Screen.class, "lastScreen", "field_21790");
    static final NamedField<EditBox> DIRECT_IP = new NamedField<>(DirectJoinServerScreen.class, EditBox.class, "ipEdit", "field_2463");
    static final NamedField<Boolean> CONNECT_ABORTED = new NamedField<>(ConnectScreen.class, Boolean.class, "aborted", "field_2409");
    static final NamedField<Screen> CONNECT_PARENT = new NamedField<>(ConnectScreen.class, Screen.class, "parent", "field_2412");
    static final NamedField<Button.OnPress> BUTTON_PRESS = new NamedField<>(Button.class, Button.OnPress.class, "onPress", "field_22767");
    static final NamedField<GameType> LAN_MODE = new NamedField<>(ShareToLanScreen.class, GameType.class, "gameMode", "field_2545");
    static final NamedField<Boolean> LAN_COMMANDS = new NamedField<>(ShareToLanScreen.class, Boolean.class, "commands", "field_2546");
    static final NamedField<ServerSelectionList> SERVER_LIST = new NamedField<>(JoinMultiplayerScreen.class, ServerSelectionList.class, "serverSelectionList", "field_3043");
    static final NamedField<Integer> LAN_PORT = new NamedField<>(ShareToLanScreen.class, Integer.class, "port", "field_41110");
    static final NamedField<EditBox> LAN_PORT_EDIT = new NamedField<>(ShareToLanScreen.class, EditBox.class, "portEdit", "field_41111");
    static final NamedField<Connection> CONNECT_CONNECTION = new NamedField<>(ConnectScreen.class, Connection.class, "connection", "field_2411");

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
