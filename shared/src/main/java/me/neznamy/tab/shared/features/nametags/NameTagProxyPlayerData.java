package me.neznamy.tab.shared.features.nametags;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.cpu.ThreadExecutor;
import me.neznamy.tab.shared.features.proxy.ProxyPlayer;
import me.neznamy.tab.shared.features.proxy.ProxySupport;
import me.neznamy.tab.shared.features.proxy.QueuedData;
import me.neznamy.tab.shared.features.proxy.message.ProxyMessage;
import me.neznamy.tab.shared.platform.Scoreboard;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.UUID;

/**
 * Proxy message to update team data of a player.
 */
@RequiredArgsConstructor
@ToString(exclude = "feature")
@Getter
public class NameTagProxyPlayerData extends ProxyMessage {

    @NotNull private final NameTag feature;
    private final long id;
    @NotNull private final UUID playerId;
    @NotNull private final String teamName;
    @NotNull private final String prefix;
    @NotNull private final String suffix;
    @NotNull private final Scoreboard.NameVisibility nameVisibility;
    @Nullable private String resolvedTeamName;
    private final boolean disabled;

    /**
     * Creates new instance and reads data from byte input.
     *
     * @param   in
     *          Input stream to read from
     * @param   feature
     *          Feature instance to use for processing
     */
    public NameTagProxyPlayerData(@NotNull ByteArrayDataInput in, @NotNull NameTag feature) {
        this.feature = feature;
        id = in.readLong();
        playerId = readUUID(in);
        teamName = in.readUTF();
        prefix = in.readUTF();
        suffix = in.readUTF();
        nameVisibility = Scoreboard.NameVisibility.getByName(in.readUTF());
        disabled = in.readBoolean();
    }

    @NotNull
    public ThreadExecutor getCustomThread() {
        return feature.getCustomThread();
    }

    @Override
    public void write(@NotNull ByteArrayDataOutput out) {
        out.writeLong(id);
        writeUUID(out, playerId);
        out.writeUTF(teamName);
        out.writeUTF(prefix);
        out.writeUTF(suffix);
        out.writeUTF(nameVisibility.toString());
        out.writeBoolean(disabled);
    }

    @Override
    public void process(@NotNull ProxySupport proxySupport) {
        // Data from a proxy the player is not connected to is queued and applied only if that proxy sends join
        ProxyPlayer target = proxySupport.getProxyPlayer(playerId, this);
        if (target == null) {
            unknownPlayer(playerId.toString(), "nametag update update");
            QueuedData data = proxySupport.getQueuedData().computeIfAbsent(playerId, k -> new QueuedData());
            // IDs are only comparable between messages from the same proxy
            if (data.getNametag() == null || !data.getNametag().isFromSameProxy(this) || data.getNametag().id < id) {
                resolvedTeamName = checkTeamName(null, teamName.substring(0, teamName.length()-1));
                data.setNametag(this);
            }
            return;
        }
        if (target.getNametag() != null && target.getNametag().isFromSameProxy(this) && target.getNametag().id > id) {
            TAB.getInstance().debug("Dropping nametag update action for player " + target.getName() + " due to newer action already being present");
            return;
        }
        NameTagProxyPlayerData oldData = target.getNametag();
        if (oldData != null && oldData.resolvedTeamName != null && oldData.teamName.equals(teamName)
                && !isTakenByLocalPlayer(oldData.resolvedTeamName)) {
            // Team name did not change and the resolved name is still free, no need to resolve it again
            resolvedTeamName = oldData.resolvedTeamName;
        } else {
            resolvedTeamName = checkTeamName(target, teamName.substring(0, teamName.length()-1));
        }
        target.setNametag(this);

        if (target.getConnectionState() == ProxyPlayer.ConnectionState.CONNECTED) {
            TabComponent prefix = feature.getPrefixCache().get(this.prefix);
            TabComponent lastColor = feature.getLastColorCache().get(this.prefix);
            TabComponent suffix = feature.getSuffixCache().get(this.suffix);
            for (TabPlayer viewer : feature.getOnlinePlayers().getPlayers()) {
                if (disabled) {
                    // Enabled to disabled (or still disabled / disabled from the start), make sure it is not registered
                    viewer.teamData.unregisterTeam(target);
                    continue;
                }
                if (oldData != null && oldData.disabled) {
                    // Disabled to enabled
                    viewer.teamData.registerTeam(
                            target,
                            resolvedTeamName,
                            prefix,
                            suffix,
                            nameVisibility,
                            Scoreboard.CollisionRule.ALWAYS,
                            Collections.singletonList(target.getNickname()),
                            feature.getTeamOptions(),
                            lastColor.getLastStyle().toEnumChatFormat()
                    );
                    continue;
                }
                if (oldData != null && resolvedTeamName.equals(oldData.resolvedTeamName)) {
                    // Property update
                    if (viewer.teamData.hasTeamRegistered(target)) {
                        viewer.getScoreboard().updateTeam(
                                oldData.resolvedTeamName,
                                prefix,
                                suffix,
                                nameVisibility,
                                Scoreboard.CollisionRule.ALWAYS,
                                feature.getTeamOptions(),
                                lastColor.getLastStyle().toEnumChatFormat()
                        );
                    }
                } else {
                    // Team rename
                    viewer.teamData.unregisterTeam(target);
                    viewer.teamData.registerTeam(
                            target,
                            resolvedTeamName,
                            prefix,
                            suffix,
                            nameVisibility,
                            Scoreboard.CollisionRule.ALWAYS,
                            Collections.singletonList(target.getNickname()),
                            feature.getTeamOptions(),
                            lastColor.getLastStyle().toEnumChatFormat()
                    );
                }
            }
        }
    }

    /**
     * Returns {@code true} if given team name is used by a player connected to this server,
     * either as their sorting team name, as a team name forced using the API or as the
     * team name currently registered by the NameTag feature.
     *
     * @param   teamName
     *          Team name to check
     * @return  {@code true} if the name is taken by a local player, {@code false} if not
     */
    private boolean isTakenByLocalPlayer(@NotNull String teamName) {
        for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
            if (teamName.equals(all.sortingData.shortTeamName) || teamName.equals(all.sortingData.forcedTeamName)
                    || teamName.equals(all.teamData.teamName)) {
                return true;
            }
        }
        return false;
    }

    @NotNull
    private String checkTeamName(@Nullable ProxyPlayer player, @NotNull String currentName15) {
        char id = 'A';
        while (true) {
            String potentialTeamName = currentName15 + id;
            boolean nameTaken = isTakenByLocalPlayer(potentialTeamName);
            if (!nameTaken && feature.getProxy() != null) {
                for (ProxyPlayer all : feature.getProxy().getProxyPlayers().values()) {
                    if (all == player) continue;
                    NameTagProxyPlayerData nametag = all.getNametag();
                    if (nametag != null && (potentialTeamName.equals(nametag.teamName) || potentialTeamName.equals(nametag.resolvedTeamName))) {
                        nameTaken = true;
                        break;
                    }
                }
            }
            if (!nameTaken && feature.getProxy() != null) {
                // Players still queued have a resolved name that will be used once they are known
                for (QueuedData queued : feature.getProxy().getQueuedData().values()) {
                    NameTagProxyPlayerData nametag = queued.getNametag();
                    if (nametag != null && nametag != this && !nametag.playerId.equals(playerId) && potentialTeamName.equals(nametag.resolvedTeamName)) {
                        nameTaken = true;
                        break;
                    }
                }
            }
            if (!nameTaken) {
                return potentialTeamName;
            }
            id++;
        }
    }
}