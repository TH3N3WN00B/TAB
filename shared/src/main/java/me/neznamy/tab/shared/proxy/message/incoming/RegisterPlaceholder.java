package me.neznamy.tab.shared.proxy.message.incoming;

import com.google.common.io.ByteArrayDataInput;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.chat.TabTextColor;
import me.neznamy.tab.shared.chat.component.TabTextComponent;
import me.neznamy.tab.shared.features.PlaceholderManagerImpl;
import me.neznamy.tab.shared.proxy.ProxyTabPlayer;
import org.jetbrains.annotations.NotNull;

public class RegisterPlaceholder implements IncomingMessage {

    /** Maximum amount of bridge placeholders, new placeholders requested by backend servers above this limit are ignored */
    private static final int MAX_BRIDGE_PLACEHOLDERS = 1000;

    /** Flag tracking whether warning about reaching the limit was already printed or not */
    private static boolean limitWarned;

    private String identifier;

    @Override
    public void read(@NotNull ByteArrayDataInput in) {
        identifier = in.readUTF();
    }

    @Override
    public void process(@NotNull ProxyTabPlayer player) {
        PlaceholderManagerImpl manager = TAB.getInstance().getPlaceholderManager();
        if (manager.getPlaceholderRaw(identifier) == null && manager.getBridgePlaceholders().size() >= MAX_BRIDGE_PLACEHOLDERS) {
            // Do not let backend servers register an unlimited amount of placeholders, each one is sent to all players
            if (!limitWarned) {
                limitWarned = true;
                TAB.getInstance().getPlatform().logWarn(new TabTextComponent("Backend server of player " + player.getName() +
                        " requested placeholder " + identifier + ", but the limit of " + MAX_BRIDGE_PLACEHOLDERS +
                        " placeholders forwarded from backend servers was reached. This and further new placeholders will be ignored.", TabTextColor.RED));
            }
            return;
        }
        manager.addUsedPlaceholder(identifier);
    }
}
