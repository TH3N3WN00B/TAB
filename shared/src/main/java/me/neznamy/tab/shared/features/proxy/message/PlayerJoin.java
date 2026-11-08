package me.neznamy.tab.shared.features.proxy.message;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import lombok.ToString;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.features.proxy.ProxyPlayer;
import me.neznamy.tab.shared.features.proxy.ProxySupport;
import me.neznamy.tab.shared.features.proxy.QueuedData;
import me.neznamy.tab.shared.platform.TabList;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Message sent by another proxy when a player joins.
 */
@ToString
public class PlayerJoin extends ProxyMessage {

    @NotNull private final UUID uniqueId;
    @NotNull private final UUID tablistId;
    @NotNull private final String name;
    @NotNull private final Server server;
    private final boolean vanished;
    private final boolean staff;
    @Nullable private final TabList.Skin skin;

    /**
     * Creates new instance from given player data.
     *
     * @param   encodedPlayer
     *          Player data to encode
     */
    public PlayerJoin(@NotNull TabPlayer encodedPlayer) {
        uniqueId = encodedPlayer.getUniqueId();
        tablistId = encodedPlayer.getTablistId();
        name = encodedPlayer.getName();
        server = encodedPlayer.server;
        vanished = encodedPlayer.isVanished();
        staff = encodedPlayer.hasPermission(TabConstants.Permission.STAFF);
        skin = encodedPlayer.getTabList().getSkin();
    }

    /**
     * Creates new instance and reads data from byte input.
     *
     * @param   in
     *          Input stream to read from
     */
    public PlayerJoin(@NotNull ByteArrayDataInput in) {
        uniqueId = readUUID(in);
        tablistId = readUUID(in);
        name = in.readUTF();
        server = Server.byName(in.readUTF());
        vanished = in.readBoolean();
        staff = in.readBoolean();
        skin = readSkin(in);
    }

    @Override
    public void write(@NotNull ByteArrayDataOutput out) {
        writeUUID(out, uniqueId);
        writeUUID(out, tablistId);
        out.writeUTF(name);
        out.writeUTF(server.getName());
        out.writeBoolean(vanished);
        out.writeBoolean(staff);
        writeSkin(out, skin);
    }

    @Override
    public void process(@NotNull ProxySupport proxySupport) {
        process(proxySupport, true);
    }

    /**
     * Processes this join message.
     *
     * @param   proxySupport
     *          Proxy support feature
     * @param   replace
     *          {@code true} if this is a live join which should replace a player connected to a different
     *          proxy (previous proxy did not send quit yet or crashed), {@code false} if this join is a
     *          part of a {@link Load} snapshot, which should not override newer joins
     */
    public void process(@NotNull ProxySupport proxySupport, boolean replace) {
        ProxyPlayer existing = proxySupport.getProxyPlayers().get(uniqueId);
        if (existing != null) {
            if (!replace || ProxySupport.isSameProxy(existing.getProxyId(), getSourceProxy())) {
                TAB.getInstance().debug("[Proxy Support] The proxy player " + name + " is already connected, cannot process join.");
                return;
            }
            TAB.getInstance().debug("[Proxy Support] The proxy player " + name + " joined proxy " + getSourceProxy() +
                    " while still being registered as connected to proxy " + existing.getProxyId() + ", replacing old entry.");
            TAB.getInstance().getFeatureManager().onQuit(existing);
            proxySupport.getProxyPlayers().remove(uniqueId, existing);
        }
        ProxyPlayer decodedPlayer = new ProxyPlayer(uniqueId, tablistId, name, server, vanished, staff, skin);
        decodedPlayer.setProxyId(getSourceProxy());
        proxySupport.getProxyPlayers().put(decodedPlayer.getUniqueId(), decodedPlayer);
        QueuedData data = proxySupport.getQueuedData().remove(decodedPlayer.getUniqueId());
        if (data != null) {
            // Only apply data sent by the same proxy, data from other proxies belongs to a different session
            if (data.getBelowname() != null && data.getBelowname().isFromSameProxy(this)) {
                decodedPlayer.setBelowname(data.getBelowname());
            }
            if (data.getTabFormat() != null && data.getTabFormat().isFromSameProxy(this)) {
                decodedPlayer.setTabFormat(data.getTabFormat());
            }
            if (data.getNametag() != null && data.getNametag().isFromSameProxy(this)) {
                decodedPlayer.setNametag(data.getNametag());
            }
            if (data.getPlayerlist() != null && data.getPlayerlist().isFromSameProxy(this)) {
                decodedPlayer.setPlayerlist(data.getPlayerlist());
            }
            if (data.getVanished() != null && ProxySupport.isSameProxy(data.getVanishedProxy(), getSourceProxy())) {
                decodedPlayer.setVanished(data.getVanished());
            }
            // Queued server is not applied, join message carries the current server and a queued value
            // may come from a previous session of the player on the same proxy
        }
        proxySupport.purgeExpiredQueuedData();
        if (TAB.getInstance().getPlayer(decodedPlayer.getUniqueId()) == null) {
            TAB.getInstance().getFeatureManager().onJoin(decodedPlayer);
        }
    }
}
