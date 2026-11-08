package me.neznamy.tab.shared.features.bossbar;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Class storing bossbar data for players.
 */
public class BossBarPlayerData {

    /** Whether player wishes to see boss bars or not */
    public boolean visible;

    /** Boss bars this player can currently see */
    public final Map<BossBarLine, BossBarLinePlayerProperties> visibleBossBars = new IdentityHashMap<>();

    /** Boss bars whose display condition was met for this player when last checked */
    public final Set<BossBarLine> conditionsMet = Collections.newSetFromMap(new IdentityHashMap<>());
}
