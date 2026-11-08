package me.neznamy.tab.platforms.bungeecord;

import me.neznamy.tab.shared.ProtocolVersion;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.cpu.CpuManager;
import me.neznamy.tab.shared.cpu.TimedCaughtTask;
import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.platform.EventListener;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.platform.decorators.SafeBossBar;
import me.neznamy.tab.shared.platform.decorators.SafeScoreboard;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import org.jetbrains.annotations.NotNull;

/**
 * The core for BungeeCord forwarding events into all enabled features
 */
public class BungeeEventListener implements EventListener<ProxiedPlayer>, Listener {

    /**
     * Listens to player disconnecting from the server.
     *
     * @param   e
     *          Disconnect event
     */
    @EventHandler
    public void onQuit(PlayerDisconnectEvent e) {
        quit(e.getPlayer().getUniqueId());
    }

    /**
     * Listens to player connecting to a backend server. This handles
     * both initial connections and server switch.
     *
     * @param   e
     *          Server switch event
     */
    @EventHandler
    public void onSwitch(ServerSwitchEvent e) {
        TAB tab = TAB.getInstance();
        if (tab.isPluginDisabled()) return;

        // Avoid 1.20.3+ client crash on scoreboard packets, do it sync to prevent packet being sent after event, but before processing
        // Avoid 1.20.5+ client disconnect with "Network Protocol Error"
        TabPlayer p = tab.getPlayer(e.getPlayer().getUniqueId());
        if (p != null && p.getVersionId() >= ProtocolVersion.V1_20_2.getNetworkId()) {
            ((SafeScoreboard<?>)p.getScoreboard()).setFrozen(true);
            ((SafeBossBar<?>)p.getBossBar()).freeze();
        }

        tab.getCPUManager().runTask(() -> {
            TabPlayer player = tab.getPlayer(e.getPlayer().getUniqueId());
            if (player == null) {
                player = createPlayer(e.getPlayer());

                // Things will get cleared immediately after, so no point in sending it, also someone said it fixed some warn from Geyser
                // Sending these packets before login packet will also crash the client on 1.20.3
                if (player.getVersionId() >= ProtocolVersion.V1_20_2.getNetworkId()) {
                    ((SafeScoreboard<?>)player.getScoreboard()).setFrozen(true);
                    ((SafeBossBar<?>)player.getBossBar()).freeze();
                }
                tab.getFeatureManager().onJoin(player);
            } else {
                tab.getFeatureManager().onServerChange(player.getUniqueId(), Server.byName(e.getPlayer().getServer().getInfo().getName()));
                if (player.getVersionId() < ProtocolVersion.V1_20_2.getNetworkId()) {
                    // For versions below 1.20.2 the tablist is already clean when this event is called
                    // For 1.20.2+ this event is called before, so we listen to Login packet instead
                    tab.getFeatureManager().onTabListClear(player);
                }
            }
        });
    }

    /**
     * Unfreezes scoreboard and boss bars of 1.20.2+ players frozen in {@link #onSwitch(ServerSwitchEvent)}
     * when pipeline injection is disabled, since otherwise it is done when Login packet is sent,
     * which can only be detected with pipeline injection. For 1.20.2+ this event is called
     * right before the Login packet is sent to the player.
     *
     * @param   e
     *          Server connected event
     */
    @EventHandler
    public void onConnected(ServerConnectedEvent e) {
        TAB tab = TAB.getInstance();
        if (tab.isPluginDisabled()) return;
        if (tab.getFeatureManager().isFeatureEnabled(TabConstants.Feature.PIPELINE_INJECTION)) return; // Handled on Login packet
        CpuManager cpu = tab.getCPUManager();
        cpu.getProcessingThread().executeLater(new TimedCaughtTask(cpu, () -> {
            TabPlayer player = tab.getPlayer(e.getPlayer().getUniqueId());
            if (player == null || player.getVersionId() < ProtocolVersion.V1_20_2.getNetworkId()) return;
            ((SafeScoreboard<?>)player.getScoreboard()).setFrozen(false);
            player.getScoreboard().resend();
            // For 1.20.2+ we need to do this, because server switch event is called before tablist is cleared
            tab.getFeatureManager().onTabListClear(player);
            ((SafeBossBar<?>)player.getBossBar()).unfreezeAndResend();
        }, "Server connect", TabConstants.CpuUsageCategory.PACKET_LOGIN), 200);
    }

    /**
     * Listens to plugin messages.
     *
     * @param   e
     *          Plugin message event
     */
    @EventHandler
    public void onPluginMessage(PluginMessageEvent e) {
        if (!e.getTag().equals(TabConstants.PLUGIN_MESSAGE_CHANNEL_NAME)) return;
        e.setCancelled(true); // Also cancel messages from players to prevent exploits
        if (TAB.getInstance().isPluginDisabled()) return;
        if (e.getReceiver() instanceof ProxiedPlayer) {
            pluginMessage(((ProxiedPlayer) e.getReceiver()).getUniqueId(), e.getData());
        }
    }

    @Override
    @NotNull
    public TabPlayer createPlayer(@NotNull ProxiedPlayer player) {
        return new BungeeTabPlayer((BungeePlatform) TAB.getInstance().getPlatform(), player);
    }
}