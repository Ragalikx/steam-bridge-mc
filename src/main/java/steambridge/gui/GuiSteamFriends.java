/*
 * Copyright (c) 2026 Ragalikx
 * MIT License - see the LICENSE file in the repository root.
 * If you use this code, please credit the author.
 */
package steambridge.gui;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamFriends;
import steambridge.steam.SteamManager;
import steambridge.steam.SteamSocial;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.Gui;
import org.lwjgl.opengl.GL11;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class GuiSteamFriends extends GuiScreen {



    /** Height of a single row (avatar + name). */
    private static final int ROW_HEIGHT = 24;

    /** Width of the avatar icon drawn inside each row. */
    private static final int AVATAR_SIZE = 16;

    /** Horizontal padding between the panel edge and content. */
    private static final int PANEL_PADDING = 5;

    /** Extra pixels added on each side of the panel beyond the list width. */
    private static final int PANEL_HALF_WIDTH = 105;

    /** Width of the clickable / name area (excluding avatar). */
    private static final int LIST_HALF_WIDTH = 100;

    /** Y coordinate where the friend list panel starts. */
    private static final int LIST_Y_START = 65;



    private final GuiScreen        parent;
    private final GuiTextField     targetField;
    private final Consumer<String> onSelected;

    private GuiTextField searchField;

    private static final class FriendItem {
        long   steamId;
        String name;
    }

    private final List<FriendItem> cachedFriends   = new ArrayList<>();
    private final List<FriendItem> filteredFriends  = new ArrayList<>();

    /** Index of the first visible row (scroll position). */
    private int scrollOffset = 0;



    public GuiSteamFriends(GuiScreen parent, GuiTextField targetField, Consumer<String> onSelected) {
        this.parent      = parent;
        this.targetField = targetField;
        this.onSelected  = onSelected;
    }



    @Override
    public void initGui() {
        this.buttonList.add(GuiButtons.createCentered(0, this.fontRendererObj, this.width / 2, this.height - 30,
                net.minecraft.client.resources.I18n.format("gui.back"), 100, this.width - 20));

        // 1.7.10 GuiTextField has no component-id constructor arg.
        searchField = new GuiTextField(this.fontRendererObj,
                this.width / 2 - 100, 35, 200, 20);
        searchField.setMaxStringLength(50);
        searchField.setFocused(true);

        loadFriends();
        updateFilter();
    }

    /** Populates {@link #cachedFriends} from the Steam friends API. */
    private void loadFriends() {
        cachedFriends.clear();
        SteamFriends friends = SteamManager.getInstance().getFriends();
        if (friends == null) return;

        int count = friends.getFriendCount(SteamFriends.FriendFlags.Immediate);
        for (int i = 0; i < count; i++) {
            SteamID id = friends.getFriendByIndex(i, SteamFriends.FriendFlags.Immediate);
            FriendItem item = new FriendItem();
            item.steamId = com.codedisaster.steamworks.SteamNativeHandle.getNativeHandle(id);
            item.name    = SteamSocial.ProfileCache.get().getDisplayName(item.steamId);
            cachedFriends.add(item);
        }
    }

    private void updateFilter() {
        filteredFriends.clear();
        String query = searchField.getText().toLowerCase();
        for (FriendItem f : cachedFriends) {
            if (f.name.toLowerCase().contains(query)) {
                filteredFriends.add(f);
            }
        }
        // Reset scroll when the result set changes.
        scrollOffset = 0;
    }

    @Override
    public void updateScreen() {
        searchField.updateCursorCounter();
    }



    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (searchField.textboxKeyTyped(typedChar, keyCode)) {
            updateFilter();
        } else {
            super.keyTyped(typedChar, keyCode);
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();

        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel != 0) {
            int maxVisible = maxVisibleRows();
            int maxScroll  = Math.max(0, filteredFriends.size() - maxVisible);
            scrollOffset  += (wheel < 0) ? 1 : -1;
            scrollOffset   = Math.max(0, Math.min(scrollOffset, maxScroll));
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        searchField.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton != 0) return;

        int maxVisible = maxVisibleRows();
        for (int i = 0; i < maxVisible; i++) {
            int friendIdx = scrollOffset + i;
            if (friendIdx >= filteredFriends.size()) break;

            int y = LIST_Y_START + i * ROW_HEIGHT;
            if (y + ROW_HEIGHT > listPanelBottom()) break;

            boolean inRow = mouseX > this.width / 2 - LIST_HALF_WIDTH
                         && mouseX < this.width / 2 + LIST_HALF_WIDTH
                         && mouseY > y
                         && mouseY < y + ROW_HEIGHT;

            if (inRow) {
                selectFriend(filteredFriends.get(friendIdx));
                return;
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            Minecraft.getMinecraft().displayGuiScreen(parent);
        }
    }



    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRendererObj,
                net.minecraft.client.resources.I18n.format("steambridge.gui.select_friend"),
                this.width / 2, 15, 0xFFFFFF);

        searchField.drawTextBox();

        drawFriendPanel(mouseX, mouseY);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawFriendPanel(int mouseX, int mouseY) {
        int panelLeft   = this.width / 2 - PANEL_HALF_WIDTH;
        int panelRight  = this.width / 2 + PANEL_HALF_WIDTH;
        int panelBottom = listPanelBottom();
        int maxVisible  = maxVisibleRows();

        // Dark background
        Gui.drawRect(panelLeft, LIST_Y_START - PANEL_PADDING,
                     panelRight, panelBottom, 0x88000000);

        for (int i = 0; i < maxVisible; i++) {
            int friendIdx = scrollOffset + i;
            if (friendIdx >= filteredFriends.size()) break;

            FriendItem friend = filteredFriends.get(friendIdx);
            int y = LIST_Y_START + i * ROW_HEIGHT;
            if (y + ROW_HEIGHT > panelBottom) break;

            // Hover highlight
            boolean hover = mouseX > this.width / 2 - LIST_HALF_WIDTH
                         && mouseX < this.width / 2 + LIST_HALF_WIDTH
                         && mouseY > y
                         && mouseY < y + ROW_HEIGHT;
            if (hover) {
                drawRect(this.width / 2 - LIST_HALF_WIDTH, y,
                         this.width / 2 + LIST_HALF_WIDTH, y + ROW_HEIGHT,
                         0x55FFFFFF);
            }

            // Avatar (only fetched when this row is visible → lazy load)
            drawAvatar(friend.steamId, this.width / 2 - LIST_HALF_WIDTH + 1, y + 4);

            // Name (offset by avatar width + 2px gap)
            int nameX = this.width / 2 - LIST_HALF_WIDTH + AVATAR_SIZE + 4;
            this.drawString(this.fontRendererObj, friend.name, nameX, y + 8, 0xFFFFFF);
        }
    }

    /**
     * Draws the Steam avatar at (x, y) as a 16×16 square.
     * Does nothing if the texture is not yet available.
     */
    private void drawAvatar(long steamId, int x, int y) {
        String texturePath = SteamSocial.ProfileCache.get().getAvatarTexture(steamId);
        if (texturePath == null || texturePath.isEmpty()) return;

        try {
            ResourceLocation loc = new ResourceLocation(texturePath);
            this.mc.getTextureManager().bindTexture(loc);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            // 1.7.10: Gui.func_146110_a == drawModalRectWithCustomSizedTexture on later versions.
            Gui.func_146110_a(x, y, 0, 0, AVATAR_SIZE, AVATAR_SIZE, AVATAR_SIZE, AVATAR_SIZE);
        } catch (Exception ignored) {}
    }



    /** Bottom Y of the friend-list panel (leaves room for the Back button). */
    private int listPanelBottom() {
        return this.height - 40;
    }

    /** Number of rows that fit inside the panel. */
    private int maxVisibleRows() {
        return (listPanelBottom() - LIST_Y_START) / ROW_HEIGHT;
    }

    private void selectFriend(FriendItem friend) {
        String steamIdStr = String.valueOf(friend.steamId);
        if (onSelected != null)  onSelected.accept(steamIdStr);
        if (targetField != null) targetField.setText(steamIdStr);
        Minecraft.getMinecraft().displayGuiScreen(parent);
    }
}
