package me.neznamy.tab.platforms.velocity;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.neznamy.tab.shared.ProtocolVersion;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.platform.EventListener;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.platform.decorators.SafeBossBar;
import org.jetbrains.annotations.NotNull;

/**
 * The core for Velocity forwarding events into all enabled features
 */
public class VelocityEventListener implements EventListener<Player> {

    /**
     * Listens to player disconnecting from the server.
     *
     * @param   e
     *          Disconnect event
     */
    @Subscribe
    public void onQuit(@NotNull DisconnectEvent e) {
        TAB tab = TAB.getInstance();
        if (tab.isPluginDisabled()) return;
        tab.getCPUManager().runTask(() -> {
            // Only process the disconnect if the loaded player belongs to this exact connection to avoid processing
            // disconnect of a different session with the same UUID that is still connected (because players are mapped by UUID in TAB)
            TabPlayer player = tab.getPlayer(e.getPlayer().getUniqueId());
            if (player != null && player.getPlayer() == e.getPlayer()) {
                tab.getFeatureManager().onQuit(player);
            }
        });
    }

    /**
     * Freezes Boss bar for 1.20.2+ players due to bug with adventure that causes disconnect
     * on 1.20.5+ with "Network Protocol Error". Listening with the last priority to see
     * the final result after other plugins possibly denied or redirected the connection.
     *
     * @param   e
     *          Event fired before player switches server for proper freezing
     */
    @Subscribe(order = PostOrder.LAST)
    public void preConnect(@NotNull ServerPreConnectEvent e) {
        if (TAB.getInstance().isPluginDisabled()) return;
        RegisteredServer target = e.getResult().getServer().orElse(null);
        if (target == null) return; // Connection was denied
        // Velocity refuses connection to the server the player is already on without calling any other event
        if (e.getPlayer().getCurrentServer().map(s -> s.getServerInfo().equals(target.getServerInfo())).orElse(false)) return;
        TabPlayer p = TAB.getInstance().getPlayer(e.getPlayer().getUniqueId());
        if (p != null && p.getVersionId() >= ProtocolVersion.V1_20_2.getNetworkId()) {
            ((SafeBossBar<?>)p.getBossBar()).freeze();
        }
    }

    /**
     * Unfreezes Boss bar for 1.20.2+ players if connection to another server failed and
     * the player stays on their current server, because {@link ServerPostConnectEvent}
     * is not called in that case. This is done directly in the event to make sure it is
     * processed before a possible redirect to another server freezes the Boss bar again.
     *
     * @param   e
     *          Event fired when connection to a server failed
     */
    @Subscribe
    public void onKick(@NotNull KickedFromServerEvent e) {
        if (TAB.getInstance().isPluginDisabled()) return;
        // Kicked from current server, player will be redirected to another server or disconnected
        if (!e.kickedDuringServerConnect()) return;
        TabPlayer p = TAB.getInstance().getPlayer(e.getPlayer().getUniqueId());
        if (p != null && p.getPlayer() == e.getPlayer() && p.getVersionId() >= ProtocolVersion.V1_20_2.getNetworkId()) {
            ((SafeBossBar<?>)p.getBossBar()).unfreezeAndSynchronize();
        }
    }

    /**
     * Listens to player connecting to a backend server. This handles
     * both initial connections and server switch.
     *
     * @param   e
     *          Server switch event
     */
    @Subscribe
    public void onConnect(@NotNull ServerPostConnectEvent e) {
        TAB tab = TAB.getInstance();
        if (tab.isPluginDisabled()) return;
        tab.getCPUManager().runTask(() -> {
            TabPlayer player = tab.getPlayer(e.getPlayer().getUniqueId());
            if (player != null && player.getPlayer() != e.getPlayer()) {
                // Player from a previous session with the same UUID whose disconnect was not processed yet, remove it
                tab.getFeatureManager().onQuit(player);
                player = null;
            }
            if (player == null) {
                if (!e.getPlayer().isActive()) return; // Player disconnected before this task ran, do not create a ghost player
                tab.getFeatureManager().onJoin(createPlayer(e.getPlayer()));
            } else {
                if (!(player.getScoreboard() instanceof VelocityScoreboard)) player.getScoreboard().resend();
                tab.getFeatureManager().onServerChange(
                        player.getUniqueId(),
                        Server.byName(e.getPlayer().getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("null"))
                );
                tab.getFeatureManager().onTabListClear(player);
                if (player.getVersionId() >= ProtocolVersion.V1_20_2.getNetworkId()) {
                    ((SafeBossBar<?>)player.getBossBar()).unfreezeAndSynchronize();
                }
            }
        });
    }

    /**
     * Listens to plugin messages.
     *
     * @param   e
     *          Plugin message event
     */
    @Subscribe
    public void onPluginMessageEvent(@NotNull PluginMessageEvent e) {
        if (!e.getIdentifier().getId().equals(TabConstants.PLUGIN_MESSAGE_CHANNEL_NAME)) return;
        e.setResult(PluginMessageEvent.ForwardResult.handled()); // Also cancel messages from players to prevent exploits
        if (TAB.getInstance().isPluginDisabled()) return;
        if (e.getTarget() instanceof Player) {
            pluginMessage(((Player) e.getTarget()).getUniqueId(), e.getData());
        }
    }

    @Override
    @NotNull
    public TabPlayer createPlayer(@NotNull Player player) {
        return new VelocityTabPlayer((VelocityPlatform) TAB.getInstance().getPlatform(), player);
    }
}
