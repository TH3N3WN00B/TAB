package me.neznamy.tab.platforms.bukkit;

import io.netty.channel.Channel;
import me.neznamy.tab.platforms.bukkit.platform.BukkitPlatform;
import me.neznamy.tab.platforms.bukkit.platform.FoliaPlatform;
import me.neznamy.tab.shared.ProjectVariables;
import me.neznamy.tab.shared.ProtocolVersion;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.util.ReflectionUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Main class for Bukkit.
 */
public class BukkitTAB extends JavaPlugin {

    /** Name of the handler injected by NettyTabListEntryTracker */
    private static final String TABLIST_TRACKER_HANDLER_NAME = "TAB-TablistEntryTracker";

    @Override
    public void onEnable() {
        boolean folia = ReflectionUtils.classExists("io.papermc.paper.threadedregions.RegionizedServer");
        try {
            TAB.create(folia ? new FoliaPlatform(this) : new BukkitPlatform(this));
        } catch (IllegalStateException e) {
            Bukkit.getConsoleSender().sendMessage("§c[TAB] ================================================================================");
            if (isSpecialJar()) {
                Bukkit.getConsoleSender().sendMessage("§c[TAB] The " + ProjectVariables.PLUGIN_VERSION + " release consists of 2 jars due to Java version problems.");
                Bukkit.getConsoleSender().sendMessage("§c[TAB] One jar is for Paper 1.20.5 - 1.21.4, the other jar is for everything else.");
                Bukkit.getConsoleSender().sendMessage("§c[TAB] You have installed the jar that explicitly says \"Paper 1.20.5 - 1.21.4\", which does not support your server version (" + Bukkit.getBukkitVersion() + ").");
                Bukkit.getConsoleSender().sendMessage("§c[TAB] Use the other jar in the release instead.");
            } else {
                Bukkit.getConsoleSender().sendMessage("§c[TAB] Your server version (" + Bukkit.getBukkitVersion() + ") is not supported.");
                Bukkit.getConsoleSender().sendMessage("§c[TAB] This jar only supports 1.7.10, 1.8.8, 1.12.2, 1.16.5, 1.17.1, 1.18.2 and 1.19.4 - 26.3");
                Bukkit.getConsoleSender().sendMessage("§c[TAB] If you just updated to a new Minecraft version, check for TAB updates.");
                Bukkit.getConsoleSender().sendMessage("§c[TAB] If you are using an unsupported 1.x version, use an older version of TAB (latest TAB 5.x supports all MC 1.x versions).");
                Bukkit.getConsoleSender().sendMessage("§c[TAB] Thrown error message: " + e.getMessage());
            }
            Bukkit.getConsoleSender().sendMessage("§c[TAB] ================================================================================");
        }
    }

    private boolean isSpecialJar() {
        return !moduleExists("v1_8_R3") &&
                moduleExists("paper_1_20_5") && moduleExists("paper_1_21_2") && moduleExists("paper_1_21_4") &&
                !moduleExists("paper_1_21_9");
    }

    private boolean moduleExists(@NotNull String module) {
        return ReflectionUtils.classExists("me.neznamy.tab.platforms.bukkit." + module + ".NMSImplementationProvider");
    }

    @Override
    public void onDisable() {
        if (TAB.getInstance() == null) return;
        TAB.getInstance().unload();
        removeTablistTrackers();
    }

    /**
     * Removes tablist entry trackers from pipelines of all online players. They are kept
     * on TAB reloads, but must not stay injected after the plugin is disabled.
     */
    private void removeTablistTrackers() {
        BukkitPlatform platform = (BukkitPlatform) TAB.getInstance().getPlatform();
        if (platform.getServerVersionInfo().getServerVersion().getNetworkId() >= ProtocolVersion.V1_8.getNetworkId()) {
            for (Player player : platform.getOnlinePlayers()) {
                try {
                    Channel channel = platform.getServerVersionInfo().getImplementationProvider().getChannel(player);
                    if (channel != null && channel.pipeline().names().contains(TABLIST_TRACKER_HANDLER_NAME)) {
                        channel.pipeline().remove(TABLIST_TRACKER_HANDLER_NAME);
                    }
                } catch (Exception ignored) {
                    // Player disconnected in the meantime or fake player
                }
            }
        }
        TAB.getInstance().getTablistTrackers().clear();
    }
}