package me.neznamy.tab.shared.features.proxy;

import lombok.Getter;
import lombok.Setter;
import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.features.belowname.BelowNameProxyPlayerData;
import me.neznamy.tab.shared.features.nametags.NameTagProxyPlayerData;
import me.neznamy.tab.shared.features.playerlist.PlayerListProxyPlayerData;
import me.neznamy.tab.shared.features.playerlistobjective.PlayerListObjectiveProxyPlayerData;
import org.jetbrains.annotations.Nullable;

/**
 * Class holding information about a player connected to another proxy before they joined in.
 * This is caused by messages being received in the wrong order, which cannot be otherwise fixed.
 */
@Getter
@Setter
public class QueuedData {

    /** Time in milliseconds after which queued data of a player who did not join is discarded */
    public static final long EXPIRE_MILLIS = 60000;

    /** Time when this data was created, used to discard data of players who never join */
    private final long creationTime = System.currentTimeMillis();

    /** Belowname data */
    @Nullable
    private BelowNameProxyPlayerData belowname;

    /** Playerlist objective data */
    @Nullable
    private PlayerListObjectiveProxyPlayerData playerlist;

    /** Tablist display name */
    @Nullable
    private PlayerListProxyPlayerData tabFormat;

    /** Nametag data */
    @Nullable
    private NameTagProxyPlayerData nametag;

    /** Whether player is vanished or not, {@code null} if no vanish status update was received */
    @Nullable
    private Boolean vanished;

    /** UUID of the proxy which sent the vanish status update */
    @Nullable
    private String vanishedProxy;

    /** Name of server the player is connected to, {@code null} if no server switch was received */
    @Nullable
    public Server server;

    /** UUID of the proxy which sent the server switch */
    @Nullable
    private String serverProxy;
}
