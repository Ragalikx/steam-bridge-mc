/*
 * Copyright (c) 2019-2026 Ragalikx
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class GuiSteamFriends extends GuiScreen {
    private final GuiScreen parent;
    private final GuiTextField targetField;
    private final Consumer<String> onSelected;
    private GuiTextField searchField;

    private static class FriendItem {
        long steamId;
        String name;
    }

    private List<FriendItem> cachedFriends = new ArrayList<>();
    private List<FriendItem> filteredFriends = new ArrayList<>();
    private int listScrollY = 0;

    public GuiSteamFriends(GuiScreen parent, GuiTextField targetField, Consumer<String> onSelected) {
        this.parent = parent;
        this.targetField = targetField;
        this.onSelected = onSelected;
    }

    @Override
    public void initGui() {
        this.buttonList.add(new GuiButton(0, this.width / 2 - 100, this.height - 30, 200, 20, net.minecraft.client.resources.I18n.format("gui.back")));

        searchField = new GuiTextField(1, this.fontRenderer, this.width / 2 - 100, 35, 200, 20);
        searchField.setMaxStringLength(50);
        searchField.setFocused(true);

        cachedFriends.clear();
        SteamFriends friends = SteamManager.getInstance().getFriends();
        if (friends != null) {
            int count = friends.getFriendCount(SteamFriends.FriendFlags.Immediate);
            for (int i = 0; i < count; i++) {
                SteamID id = friends.getFriendByIndex(i, SteamFriends.FriendFlags.Immediate);
                FriendItem item = new FriendItem();
                item.steamId = com.codedisaster.steamworks.SteamNativeHandle.getNativeHandle(id);
                item.name = SteamSocial.ProfileCache.get().getDisplayName(item.steamId);
                cachedFriends.add(item);
            }
        }
        updateFilter();
    }

    private void updateFilter() {
        filteredFriends.clear();
        String query = searchField.getText().toLowerCase();
        for (FriendItem f : cachedFriends) {
            if (f.name.toLowerCase().contains(query)) {
                filteredFriends.add(f);
            }
        }
    }

    @Override
    public void updateScreen() {
        searchField.updateCursorCounter();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (searchField.textboxKeyTyped(typedChar, keyCode)) {
            updateFilter();
        } else {
            super.keyTyped(typedChar, keyCode);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            Minecraft.getMinecraft().displayGuiScreen(parent);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, net.minecraft.client.resources.I18n.format("steambridge.gui.select_friend"), this.width / 2, 15, 16777215);

        searchField.drawTextBox();

        int yStart = 65;
        int listHeight = this.height - 105;
        int maxItems = listHeight / 24;

        Gui.drawRect(this.width / 2 - 105, yStart - 5, this.width / 2 + 105, yStart + (maxItems * 24), 0x88000000);

        int index = 0;
        for (FriendItem friend : filteredFriends) {
            int y = yStart + (index * 24);
            if (y > this.height - 40) break;

            boolean hover = mouseX > this.width / 2 - 100 && mouseX < this.width / 2 + 100 && mouseY > y && mouseY < y + 24;
            if (hover) {
                drawRect(this.width / 2 - 100, y, this.width / 2 + 100, y + 24, 0x88FFFFFF);
            }

            this.drawString(this.fontRenderer, friend.name, this.width / 2 - 98, y + 8, 16777215);
            index++;
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        searchField.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton == 0) {
            int yStart = 65;
            int index = 0;
            for (FriendItem friend : filteredFriends) {
                int y = yStart + (index * 24);
                if (y > this.height - 40) break;

                if (mouseX > this.width / 2 - 100 && mouseX < this.width / 2 + 100 && mouseY > y && mouseY < y + 24) {
                    String steamIdStr = String.valueOf(friend.steamId);
                    if (onSelected != null) {
                        onSelected.accept(steamIdStr);
                    }
                    if (targetField != null) {
                        targetField.setText(steamIdStr);
                    }
                    Minecraft.getMinecraft().displayGuiScreen(parent);
                    return;
                }
                index++;
            }
        }
    }
}

