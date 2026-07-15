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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class GuiSteamFriends extends Screen {

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

    private final Screen           parent;
    private final EditBox          targetField;
    private final Consumer<String> onSelected;

    private EditBox searchField;

    private static final class FriendItem {
        long   steamId;
        String name;
    }

    private final List<FriendItem> cachedFriends   = new ArrayList<>();
    private final List<FriendItem> filteredFriends  = new ArrayList<>();

    /** Index of the first visible row (scroll position). */
    private int scrollOffset = 0;

    public GuiSteamFriends(Screen parent, EditBox targetField, Consumer<String> onSelected) {
        super(Component.translatable("steambridge.gui.select_friend"));
        this.parent      = parent;
        this.targetField = targetField;
        this.onSelected  = onSelected;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                b -> this.minecraft.setScreen(parent))
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());

        searchField = new EditBox(this.font, this.width / 2 - 100, 35, 200, 20, Component.empty());
        searchField.setMaxLength(50);
        searchField.setResponder(s -> updateFilter());
        this.addRenderableWidget(searchField);
        this.setInitialFocus(searchField);

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
        String query = searchField != null ? searchField.getValue().toLowerCase() : "";
        for (FriendItem f : cachedFriends) {
            if (f.name.toLowerCase().contains(query)) {
                filteredFriends.add(f);
            }
        }
        // Reset scroll when the result set changes.
        scrollOffset = 0;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxVisible = maxVisibleRows();
        int maxScroll  = Math.max(0, filteredFriends.size() - maxVisible);
        scrollOffset  += (delta > 0) ? -1 : 1;
        scrollOffset   = Math.max(0, Math.min(scrollOffset, maxScroll));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if (super.mouseClicked(mouseX, mouseY, mouseButton)) {
            return true;
        }
        if (mouseButton != 0) return false;

        int maxVisible = maxVisibleRows();
        for (int i = 0; i < maxVisible; i++) {
            int friendIdx = scrollOffset + i;
            if (friendIdx >= filteredFriends.size()) break;

            int y = LIST_Y_START + i * ROW_HEIGHT;
            if (y + ROW_HEIGHT > listPanelBottom()) break;

            boolean inRow = mouseX > this.width / 2.0 - LIST_HALF_WIDTH
                         && mouseX < this.width / 2.0 + LIST_HALF_WIDTH
                         && mouseY > y
                         && mouseY < y + ROW_HEIGHT;

            if (inRow) {
                selectFriend(filteredFriends.get(friendIdx));
                return true;
            }
        }
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        super.renderBackground(g);
        g.drawCenteredString(this.font,
                I18n.get("steambridge.gui.select_friend"),
                this.width / 2, 15, 0xFFFFFF);

        drawFriendPanel(g, mouseX, mouseY);

        super.render(g, mouseX, mouseY, partialTicks);
    }

    private void drawFriendPanel(GuiGraphics g, int mouseX, int mouseY) {
        int panelLeft   = this.width / 2 - PANEL_HALF_WIDTH;
        int panelRight  = this.width / 2 + PANEL_HALF_WIDTH;
        int panelBottom = listPanelBottom();
        int maxVisible  = maxVisibleRows();

        // Dark background
        g.fill(panelLeft, LIST_Y_START - PANEL_PADDING, panelRight, panelBottom, 0x88000000);

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
                g.fill(this.width / 2 - LIST_HALF_WIDTH, y,
                       this.width / 2 + LIST_HALF_WIDTH, y + ROW_HEIGHT,
                       0x55FFFFFF);
            }

            // Avatar (only fetched when this row is visible → lazy load)
            drawAvatar(g, friend.steamId, this.width / 2 - LIST_HALF_WIDTH + 1, y + 4);

            // Name (offset by avatar width + 2px gap)
            int nameX = this.width / 2 - LIST_HALF_WIDTH + AVATAR_SIZE + 4;
            g.drawString(this.font, friend.name, nameX, y + 8, 0xFFFFFF);
        }
    }

    /**
     * Draws the Steam avatar at (x, y) as a 16×16 square.
     * Does nothing if the texture is not yet available.
     */
    private void drawAvatar(GuiGraphics g, long steamId, int x, int y) {
        String texturePath = SteamSocial.ProfileCache.get().getAvatarTexture(steamId);
        if (texturePath == null || texturePath.isEmpty()) return;

        try {
            // GuiGraphics.blit(ResourceLocation, x, y, u, v, w, h, texW, texH): 1.20.1 API
            ResourceLocation loc = new ResourceLocation(texturePath);
            g.blit(loc, x, y, 0.0F, 0.0F, AVATAR_SIZE, AVATAR_SIZE, AVATAR_SIZE, AVATAR_SIZE);
        } catch (Exception ignored) {
            // Missing/invalid dynamic texture: skip this avatar rather than crash the screen.
        }
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
        if (targetField != null) targetField.setValue(steamIdStr);
        Minecraft.getInstance().setScreen(parent);
    }
}
