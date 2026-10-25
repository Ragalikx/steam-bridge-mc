package steambridge.gui;

import net.minecraft.client.gui.screen.AddServerScreen;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.DirectConnectScreen;
import net.minecraft.client.gui.screen.OpenToLanScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.ClientConnection;
import steambridge.SteamBridgeMod;
import steambridge.core.NamedField;

final class GuiFields {
    static final NamedField<TextFieldWidget> EDITOR_IP = new NamedField<>(AddServerScreen.class, TextFieldWidget.class, "addressField", "field_2474");
    static final NamedField<TextFieldWidget> EDITOR_NAME = new NamedField<>(AddServerScreen.class, TextFieldWidget.class, "serverNameField", "field_2471");
    static final NamedField<ServerInfo> EDITOR_SERVER = new NamedField<>(AddServerScreen.class, ServerInfo.class, "server", "field_2469");
    static final NamedField<Screen> DIRECT_PARENT = new NamedField<>(DirectConnectScreen.class, Screen.class, "parent", "field_21790");
    static final NamedField<TextFieldWidget> DIRECT_IP = new NamedField<>(DirectConnectScreen.class, TextFieldWidget.class, "addressField", "field_2463");
    static final NamedField<Boolean> CONNECT_ABORTED = new NamedField<>(ConnectScreen.class, Boolean.class, "connectingCancelled", "field_2409");
    static final NamedField<Screen> CONNECT_PARENT = new NamedField<>(ConnectScreen.class, Screen.class, "parent", "field_2412");
    static final NamedField<ButtonWidget.PressAction> BUTTON_PRESS = new NamedField<>(ButtonWidget.class, ButtonWidget.PressAction.class, "onPress", "field_22767");
    static final NamedField<String> LAN_MODE = new NamedField<>(OpenToLanScreen.class, String.class, "gameMode", "field_2545");
    static final NamedField<Boolean> LAN_COMMANDS = new NamedField<>(OpenToLanScreen.class, Boolean.class, "allowCommands", "field_2546");
    static final NamedField<MultiplayerServerListWidget> SERVER_LIST = new NamedField<>(MultiplayerScreen.class, MultiplayerServerListWidget.class, "serverListWidget", "field_3043");
    static final NamedField<ClientConnection> CONNECT_CONNECTION = new NamedField<>(ConnectScreen.class, ClientConnection.class, "connection", "field_2411");

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
