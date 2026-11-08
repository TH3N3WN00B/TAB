package me.neznamy.tab.shared.proxy;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import me.neznamy.tab.api.integration.VanishIntegration;
import me.neznamy.tab.api.placeholder.PlayerPlaceholder;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.cpu.CpuManager;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.proxy.message.outgoing.OutgoingMessage;
import me.neznamy.tab.shared.proxy.message.outgoing.PermissionRequest;
import me.neznamy.tab.shared.proxy.message.outgoing.PlayerJoin;
import me.neznamy.tab.shared.task.PluginMessageEncodeTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract class for player on proxy containing variables and methods
 * shared between proxies.
 */
@Getter @Setter
public abstract class ProxyTabPlayer extends TabPlayer {

    /**
     * Time in milliseconds for which a permission request without a response is considered pending.
     * Another request for the same permission is not sent during this time.
     */
    private static final long PERMISSION_REQUEST_TIMEOUT = 500;

    /** Player's vanish status from backend server */
    public boolean vanished;

    /** Player's disguise status from backend server */
    private boolean disguised;

    /** Player's invisibility potion status from backend server */
    private boolean invisibilityPotion;

    /** Timestamp when join plugin message was sent to track how long bridge took to respond */
    private long bridgeRequestTime;

    /** Flag for marking if this player has received join response from bridge or not */
    private boolean bridgeConnected;

    /** Player's gamemode */
    private int gamemode;

    /** Map of player's requested permissions */
    private final Map<String, Boolean> permissions = new ConcurrentHashMap<>();

    /**
     * Permissions requested from the bridge on current server, mapped to the time of the last request
     * which did not receive a response yet, or {@code 0} if a response was received.
     * It is not initialized in declaration, because permissions may already be checked from superclass constructor.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Map<String, Long> permissionRequests;

    /** Map of placeholders sent by the bridge */
    private final Map<String, String> placeholders = new ConcurrentHashMap<>();

    /** View distance of this player's belowname */
    @Nullable
    private Double belowNameDistance;

    /**
     * Constructs new instance with given parameters and sends a message
     * to bridge about this player joining with join data
     *
     * @param   platform
     *          Server platform
     * @param   player
     *          platform-specific player object
     * @param   uniqueId
     *          Player's unique ID
     * @param   name
     *          Player's name
     * @param   server
     *          Player's server
     * @param   protocolVersion
     *          Player's protocol network id
     */
    protected ProxyTabPlayer(@NotNull ProxyPlatform platform, @NotNull Object player, @NotNull UUID uniqueId,
                             @NotNull String name, @NotNull String server, int protocolVersion) {
        super(platform, player, uniqueId, name, server, "N/A", protocolVersion, TAB.getInstance().getConfiguration().getConfig().isOnlineUuidInTabList());
        if (permissionRequests == null) permissionRequests = new ConcurrentHashMap<>(); // Might have been created by superclass constructor already
        sendJoinPluginMessage();
    }

    @Override
    public void setBelowNameDistance(double distance) {
        belowNameDistance = distance;
        // TODO implement this on proxy
    }

    /**
     * Sends plugin message to backend server that this player has
     * joined, containing all plugin configuration data.
     */
    public void sendJoinPluginMessage() {
        bridgeConnected = false; // Reset on server switch
        placeholders.clear();
        // Permissions are per-server, ask the new server again for everything after sending join message.
        // Previous values are intentionally kept until the new server responds, because features process
        // the server switch immediately and would otherwise see every permission as missing
        // (e.g. staff losing visibility of vanished players on every server switch).
        List<String> knownPermissions = new ArrayList<>(permissionRequests.keySet());
        permissionRequests.clear();
        sendPluginMessage(new PlayerJoin(
                TAB.getInstance().getGroupManager().getPermissionPlugin().contains("Vault") &&
                    !TAB.getInstance().getConfiguration().getConfig().isGroupsByPermissions(),
                TAB.getInstance().getPlaceholderManager().getBridgePlaceholders(),
                TAB.getInstance().getConfiguration().getConfig().getReplacements().getValues()
        ));
        for (String permission : knownPermissions) {
            requestPermission(permission);
        }
        expansionData.resendAllValues();
        bridgeRequestTime = System.currentTimeMillis();
    }

    /**
     * Sets permission presence status to provided value.
     * Responses for permissions which were not requested on current server are ignored.
     *
     * @param   permission
     *          Requested permission node
     * @param   value
     *          Permission value
     */
    public void setHasPermission(@NotNull String permission, boolean value) {
        Long requestTime = permissionRequests.get(permission);
        if (requestTime == null) return; // Not requested on this server
        permissionRequests.replace(permission, requestTime, 0L); // Response received, no longer pending
        permissions.put(permission, value);
    }

    /**
     * Sends permission request to the bridge, unless a request for the same permission
     * was sent recently and did not receive a response yet.
     *
     * @param   permission
     *          Permission to request
     */
    private void requestPermission(@NotNull String permission) {
        if (permissions == null) {
            // Called from superclass constructor before fields were initialized, request it after sending join message
            if (permissionRequests == null) permissionRequests = new ConcurrentHashMap<>();
            permissionRequests.put(permission, 0L);
            return;
        }
        long now = System.currentTimeMillis();
        Long lastRequest = permissionRequests.get(permission);
        if (lastRequest != null && now - lastRequest < PERMISSION_REQUEST_TIMEOUT) return; // Request already pending
        boolean claimed = lastRequest == null ?
                permissionRequests.putIfAbsent(permission, now) == null :
                permissionRequests.replace(permission, lastRequest, now);
        if (claimed) sendPluginMessage(new PermissionRequest(permission));
    }

    /**
     * Updates player's tracked gamemode for internal logic and placeholders.
     *
     * @param   gamemode
     *          New gamemode
     */
    public void setGamemode(int gamemode) {
        if (this.gamemode == gamemode) return; // Player join with player in survival mode
        this.gamemode = gamemode;
        ((PlayerPlaceholder) TAB.getInstance().getPlaceholderManager().getPlaceholder(TabConstants.Placeholder.GAMEMODE)).update(this);
        TAB.getInstance().getFeatureManager().onGameModeChange(this);
    }

    /**
     * Updates player's tracked invisibility potion value for internal logic and placeholders.
     *
     * @param   invisibilityPotion
     *          Whether player has invisibility potion or not
     */
    public void setInvisibilityPotion(boolean invisibilityPotion) {
        if (this.invisibilityPotion == invisibilityPotion) return; // Player join without invisibility potion
        this.invisibilityPotion = invisibilityPotion;
        ((PlayerPlaceholder) TAB.getInstance().getPlaceholderManager().getPlaceholder(TabConstants.Placeholder.INVISIBLE)).update(this);
    }

    /**
     * Performs platform-specific permission check and returns the result
     *
     * @param   permission
     *          Permission to check for
     * @return  Result from hasPermission call
     */
    public abstract boolean hasPermission0(String permission);

    /**
     * Sends plugin message to this player
     *
     * @param   message
     *          message to send
     */
    public abstract void sendPluginMessage(byte[] message);

    @Override
    public boolean hasInvisibilityPotion() {
        return invisibilityPotion;
    }

    @Override
    public boolean hasPermission(@NotNull String permission) {
        if (TAB.getInstance().getConfiguration().getConfig().isBukkitPermissions()) {
            requestPermission(permission);
            return permissions != null && permissions.getOrDefault(permission, false);
        }
        return hasPermission0(permission);
    }

    /**
     * Sends plugin message to the player.
     *
     * @param   message
     *          Plugin message to send
     */
    public void sendPluginMessage(@NotNull OutgoingMessage message) {
        CpuManager.getPluginMessageEncodeThread().execute(new PluginMessageEncodeTask(this, message));
    }

    @Override
    public boolean isVanished() {
        if (!VanishIntegration.getHandlers().isEmpty()) {
            for (VanishIntegration integration : VanishIntegration.getHandlers()) {
                if (integration.isVanished(this)) return true;
            }
            return false;
        }
        return vanished;
    }

    @NotNull
    public Map<String, Object> dump() {
        Map<String, Object> map = super.dump();
        map.put("bridge data", new LinkedHashMap<String, Object>() {{
            put("Vanished", vanished);
            put("Disguised", disguised);
            put("Has invisibility potion", invisibilityPotion);
            put("Gamemode", gamemode);
            put("Permissions", permissions);
            if (bridgeConnected) {
                put("Bridge connection", "Connected");
            } else {
                char versionRequired = TabConstants.PLUGIN_MESSAGE_CHANNEL_NAME.charAt(TabConstants.PLUGIN_MESSAGE_CHANNEL_NAME.length()-1);
                put("Bridge connection", "Not connected (requires Bridge version " + versionRequired + ".x.x installed)");
            }
            put("Placeholders", placeholders);
        }});
        return map;
    }
}