package steambridge.gui;

import net.minecraft.client.gui.screen.AddServerScreen;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.gui.screen.MultiplayerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ServerListScreen;
import net.minecraft.client.gui.screen.ServerSelectionList;
import net.minecraft.client.gui.screen.ShareToLanScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.NetworkManager;
import steambridge.SteamBridgeMod;
import steambridge.core.LogLimiter;
import steambridge.core.NamedField;
import steambridge.core.ProblemLog;

final class GuiFields {
    private static final LogLimiter LOG_LIMITER = new LogLimiter(32, 30_000);
    static final NamedField<TextFieldWidget> EDITOR_IP = new NamedField<>(AddServerScreen.class, TextFieldWidget.class, "ipEdit", "field_146308_f");
    static final NamedField<TextFieldWidget> EDITOR_NAME = new NamedField<>(AddServerScreen.class, TextFieldWidget.class, "nameEdit", "field_146309_g");
    static final NamedField<ServerData> EDITOR_SERVER = new NamedField<>(AddServerScreen.class, ServerData.class, "serverData", "field_146311_h");
    static final NamedField<Screen> DIRECT_PARENT = new NamedField<>(ServerListScreen.class, Screen.class, "lastScreen", "field_228178_e_");
    static final NamedField<TextFieldWidget> DIRECT_IP = new NamedField<>(ServerListScreen.class, TextFieldWidget.class, "ipEdit", "field_146302_g");
    static final NamedField<Boolean> CONNECT_ABORTED = new NamedField<>(ConnectingScreen.class, Boolean.class, "aborted", "field_146373_h");
    static final NamedField<Screen> CONNECT_PARENT = new NamedField<>(ConnectingScreen.class, Screen.class, "parent", "field_146374_i");
    static final NamedField<Button.IPressable> BUTTON_PRESS = new NamedField<>(Button.class, Button.IPressable.class, "onPress", "field_230697_t_");
    static final NamedField<String> LAN_MODE = new NamedField<>(ShareToLanScreen.class, String.class, "gameModeName", "field_146599_h");
    static final NamedField<Boolean> LAN_COMMANDS = new NamedField<>(ShareToLanScreen.class, Boolean.class, "commands", "field_146600_i");
    static final NamedField<ServerSelectionList> SERVER_LIST = new NamedField<>(MultiplayerScreen.class, ServerSelectionList.class, "serverSelectionList", "field_146803_h");
    static final NamedField<NetworkManager> CONNECT_CONNECTION = new NamedField<>(ConnectingScreen.class, NetworkManager.class, "connection", "field_146371_g");

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
