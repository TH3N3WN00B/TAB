package me.neznamy.tab.shared.placeholders.types;

import lombok.NonNull;
import me.neznamy.tab.api.placeholder.RelationalPlaceholder;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.chat.EnumChatFormat;
import me.neznamy.tab.shared.features.types.RefreshableFeature;
import me.neznamy.tab.shared.placeholders.PlaceholderIdentifier;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiFunction;

/**
 * Implementation of RelationalPlaceholder interface
 */
public class RelationalPlaceholderImpl extends TabPlaceholder implements RelationalPlaceholder {

    /** Placeholder function returning fresh output on request */
    @NonNull private final BiFunction<me.neznamy.tab.api.TabPlayer, me.neznamy.tab.api.TabPlayer, String> function;

    /**
     * Constructs new instance with given parameters
     *
     * @param   identifier
     *          placeholder identifier, must start with {@code %rel_} and end with {@code %}
     * @param   refresh
     *          refresh interval in milliseconds, must be divisible by {@link TabConstants.Placeholder#MINIMUM_REFRESH_INTERVAL}
     *          or equal to -1 to disable automatic refreshing
     * @param   function
     *          refresh function which returns new up-to-date output on request
     */
    public RelationalPlaceholderImpl(@NonNull String identifier, int refresh,
                                     @NonNull BiFunction<me.neznamy.tab.api.TabPlayer, me.neznamy.tab.api.TabPlayer, String> function) {
        super(identifier, refresh);
        if (!PlaceholderIdentifier.isRelational(identifier)) throw new IllegalArgumentException(
                "Relational placeholder identifiers must start with \"rel_\" (attempted to use \"" + identifier + "\")");
        this.function = function;
    }

    @Override
    public void update(@NonNull me.neznamy.tab.api.TabPlayer viewer, @NonNull me.neznamy.tab.api.TabPlayer target) {
        updateValue(viewer, target, request((TabPlayer) viewer, (TabPlayer) target));
    }

    @Override
    public void updateValue(@NonNull me.neznamy.tab.api.TabPlayer viewer, @NonNull me.neznamy.tab.api.TabPlayer target, @Nullable String value) {
        if (hasValueChanged((TabPlayer) viewer, (TabPlayer) target, value)) {
            for (RefreshableFeature r : reference.getUsedByFeatures()) {
                refreshFeature(r, (TabPlayer) target, true);
            }
        }
    }

    /**
     * Updates placeholder value and returns {@code true} if value changed, {@code false} if not.
     *
     * @param   viewer
     *          Player viewing the placeholder
     * @param   target
     *          Player the placeholder is displayed on
     * @param   value
     *          New value
     * @return  {@code true} if value changed, {@code false} if not
     */
    public boolean hasValueChanged(@NonNull TabPlayer viewer, @NonNull TabPlayer target, @Nullable String value) {
        if (value == null) return false; //bridge placeholders, they are updated using updateValue method
        if (value.equals(ERROR_VALUE)) return false; // Keep last known value
        String newValue = replacements.findReplacement(value);
        Map<TabPlayer, String> viewerMap = getViewerMap(viewer);
        if (!viewerMap.getOrDefault(target, identifier).equals(newValue)) {
            viewerMap.put(target, newValue);
            updateParents(viewer);
            updateParents(target);
            return true;
        }
        return false;
    }

    @Override
    public void updateFromNested(@NonNull TabPlayer target) {
        Set<TabPlayer> changedTargets = new HashSet<>();
        for (TabPlayer other : TAB.getInstance().getOnlinePlayers()) {
            // Every viewer -> this player
            String value = request(other, target);
            if (value != null && !value.equals(ERROR_VALUE)) { // Keep last known value
                getViewerMap(other).put(target, replacements.findReplacement(value));
            }

            // This player as viewer -> every other player (nested value may be used for the viewer, such as %viewer:...% in conditions)
            if (other == target) continue;
            String viewerValue = request(target, other);
            if (viewerValue == null || viewerValue.equals(ERROR_VALUE)) continue; // Keep last known value
            String newValue = replacements.findReplacement(viewerValue);
            String previousValue = getViewerMap(target).put(other, newValue);
            if (previousValue != null && !previousValue.equals(newValue)) {
                changedTargets.add(other);
            }
        }
        for (TabPlayer changedTarget : changedTargets) {
            if (!changedTarget.isLoaded()) continue; // Updated on join
            for (RefreshableFeature f : reference.getUsedByFeatures()) {
                refreshFeature(f, changedTarget, true);
            }
        }
        if (!target.isLoaded()) return; // Updated on join
        for (RefreshableFeature f : reference.getUsedByFeatures()) {
            refreshFeature(f, target, true);
        }
        updateParents(target);
    }

    /**
     * Returns map of last known values of this placeholder for given viewer, where key is the target.
     * If not present, it is created.
     *
     * @param   viewer
     *          Viewer to get values of
     * @return  Map of last known values for given viewer
     */
    @NotNull
    private Map<TabPlayer, String> getViewerMap(@NotNull TabPlayer viewer) {
        return viewer.lastRelationalValues.computeIfAbsent(this, v -> Collections.synchronizedMap(new WeakHashMap<>()));
    }

    /**
     * Relational placeholders can only be resolved for a viewer and target pair, which is done
     * separately for each viewer. Returns the identifier unchanged, so it remains in the text and
     * output replacements (such as "else") are not applied for all viewers at once.
     *
     * @param   player
     *          player to parse placeholder for
     * @return  identifier of this placeholder
     */
    @Override
    @NotNull
    public String parse(@Nullable TabPlayer player) {
        return identifier;
    }

    /**
     * Returns last known value for given players. First player is viewer,
     * second player is target.
     *
     * @param   viewer
     *          viewer of the placeholder
     * @param   target
     *          target who is the text displayed on
     * @return  last known value for entered player duo
     */
    public String getLastValue(@NonNull TabPlayer viewer, @NonNull TabPlayer target) {
        return setPlaceholders(
                EnumChatFormat.color(
                        getViewerMap(viewer).computeIfAbsent(target, t -> retrieveValue(viewer, target))
                ),
                target
        );
    }

    @NotNull
    private String retrieveValue(@NotNull TabPlayer viewer, @NotNull TabPlayer target) {
        String output = request(viewer, target);
        if (output == null || output.equals(ERROR_VALUE)) output = identifier;
        return evaluate(output, target);
    }

    @Override
    public @NotNull String getLastValue(@Nullable TabPlayer p) {
        return identifier;
    }

    @Override
    public @NotNull String getLastReturnedValue(@Nullable TabPlayer player) {
        return identifier;
    }

    @Override
    public @NotNull String getLastValueSafe(@NotNull TabPlayer player) {
        return identifier;
    }

    /**
     * Calls the placeholder request function and returns the output.
     * If the placeholder threw an exception, it is logged in {@code placeholder-errors.log}
     * file and {@link #ERROR_VALUE} is returned.
     *
     * @param   viewer
     *          player looking at output of the placeholder
     * @param   target
     *          player the placeholder is displayed on
     * @return  value placeholder returned or {@link #ERROR_VALUE} if it threw an error
     */
    public @Nullable String request(@NonNull TabPlayer viewer, @NonNull TabPlayer target) {
        long time = System.currentTimeMillis();
        try {
            return function.apply(viewer, target);
        } catch (Throwable t) {
            TAB.getInstance().getErrorManager().placeholderError("Relational placeholder " + identifier + " generated an error when setting for players " + viewer.getName() + " and " + target.getName(), t);
            return ERROR_VALUE;
        } finally {
            long timeDiff = System.currentTimeMillis() - time;
            if (timeDiff > TabConstants.Placeholder.RETURN_TIME_WARN_THRESHOLD) {
                TAB.getInstance().debug("Placeholder " + identifier + " took " + timeDiff + "ms to return value for " + viewer.getName() + " and " + target.getName());
            }
        }
    }
}