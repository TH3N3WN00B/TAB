package me.neznamy.tab.shared.features.scoreboard;

import lombok.Getter;
import lombok.NonNull;
import me.neznamy.tab.api.scoreboard.Line;
import me.neznamy.tab.shared.Property;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.chat.component.TabComponent;
import me.neznamy.tab.shared.cpu.ThreadExecutor;
import me.neznamy.tab.shared.features.scoreboard.ScoreboardConfiguration.ScoreboardDefinition;
import me.neznamy.tab.shared.features.scoreboard.lines.ScoreboardLineHolder;
import me.neznamy.tab.shared.features.types.Conditional;
import me.neznamy.tab.shared.features.types.CustomThreaded;
import me.neznamy.tab.shared.features.types.RefreshableFeature;
import me.neznamy.tab.shared.features.types.TabFeature;
import me.neznamy.tab.shared.placeholders.conditions.Condition;
import me.neznamy.tab.shared.platform.Scoreboard;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A class representing a scoreboard configured in config
 */
@Getter
public class ScoreboardImpl extends RefreshableFeature implements me.neznamy.tab.api.scoreboard.Scoreboard,
        CustomThreaded, Conditional {

    /** Highest line number supported by {@link ScoreboardLineHolder} */
    private static final int MAX_LINE_NUMBER = 99;

    //scoreboard manager
    private final ScoreboardManagerImpl manager;

    //name of this scoreboard
    private final String name;

    //scoreboard title
    private String title;

    //display condition
    private Condition displayCondition;

    /** Flag tracking whether this scoreboard was made using API or not */
    private final boolean api;

    //lines of scoreboard
    private final List<Line> lines = new ArrayList<>();

    //players currently seeing this scoreboard
    private final Set<TabPlayer> players = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Constructs new instance with given parameters and registers lines to feature manager
     *
     * @param   manager
     *          scoreboard manager
     * @param   name
     *          name of this scoreboard
     * @param   definition
     *          Scoreboard properties
     */
    public ScoreboardImpl(@NonNull ScoreboardManagerImpl manager, @NonNull String name, @NonNull ScoreboardDefinition definition) {
        this(manager, name, definition, false);
        displayCondition = TAB.getInstance().getPlaceholderManager().getConditionManager().getByNameOrExpression(definition.getDisplayCondition());
        if (displayCondition != null) {
            manager.addUsedPlaceholder(displayCondition.getPlaceholderIdentifier());
        }
    }

    /**
     * Constructs new instance with given parameters and registers lines to feature manager
     *
     * @param   manager
     *          scoreboard manager
     * @param   name
     *          name of this scoreboard
     * @param   definition
     *          Scoreboard properties
     * @param   api
     *          Whether this scoreboard was created using API or not
     */
    public ScoreboardImpl(@NonNull ScoreboardManagerImpl manager, @NonNull String name, @NonNull ScoreboardDefinition definition, boolean api) {
        this.manager = manager;
        this.name = name;
        this.api = api;
        title = definition.getTitle();
        for (int i = 0; i< definition.getLines().size(); i++) {
            String line = definition.getLines().get(i);
            if (line == null) line = "";
            ScoreboardLineHolder score = new ScoreboardLineHolder(this, line, i+1);
            lines.add(score);
            TAB.getInstance().getFeatureManager().registerFeature(getLineFeatureName(score), score);
        }
    }

    /**
     * Adds the player into scoreboard. This includes registering properties,
     * as well as scoreboard and all lines.
     *
     * @param   p
     *          Player to send this scoreboard to
     */
    public void addPlayer(@NonNull TabPlayer p) {
        if (p.scoreboardData.activeScoreboard == this) return; // already registered
        p.scoreboardData.titleProperty = new Property(this, p, title);
        p.getScoreboard().registerObjective(
                ScoreboardManagerImpl.OBJECTIVE_NAME,
                manager.getCache().get(p.scoreboardData.titleProperty.get()),
                Scoreboard.HealthDisplay.INTEGER,
                TabComponent.empty()
        );
        if (p.scoreboardData.otherPluginScoreboard == null) {
            p.getScoreboard().setDisplaySlot(ScoreboardManagerImpl.OBJECTIVE_NAME, Scoreboard.DisplaySlot.SIDEBAR);
        }
        for (Line s : lines) {
            ((ScoreboardLineHolder)s).register(p);
        }
        players.add(p);
        p.scoreboardData.activeScoreboard = this;
        p.expansionData.setScoreboardName(name);
    }

    /**
     * Unregisters player from this scoreboard.
     *
     * @param   p
     *          Player to unregister
     */
    public void removePlayer(@NonNull TabPlayer p) {
        if (p.scoreboardData.activeScoreboard != this) return; // not registered
        p.getScoreboard().unregisterObjective(ScoreboardManagerImpl.OBJECTIVE_NAME);
        for (Line l : lines) {
            p.getScoreboard().unregisterTeam(((ScoreboardLineHolder) l).getTeamName());
        }
        players.remove(p);
        p.scoreboardData.activeScoreboard = null;
        p.scoreboardData.titleProperty = null;
        p.scoreboardData.lineProperties.clear();
        p.expansionData.setScoreboardName("");
    }

    @NotNull
    @Override
    public String getRefreshDisplayName() {
        return "Updating Scoreboard title";
    }

    @Override
    public void refresh(@NotNull TabPlayer refreshed, boolean force) {
        if (refreshed.scoreboardData.activeScoreboard != this) return; //player has different scoreboard displayed
        refreshed.getScoreboard().updateObjective(
                ScoreboardManagerImpl.OBJECTIVE_NAME,
                manager.getCache().get(refreshed.scoreboardData.titleProperty.updateAndGet()),
                Scoreboard.HealthDisplay.INTEGER,
                TabComponent.empty()
        );
    }

    /**
     * Removes this player from list of players who can see it.
     *
     * @param   player
     *          Player to remove from set
     */
    public void removePlayerFromSet(@NonNull TabPlayer player) {
        players.remove(player);
    }

    /**
     * Unregisters this scoreboard and all of its lines from feature manager.
     * Called when this scoreboard is removed.
     */
    public void unregisterFeatures() {
        for (Line line : lines) {
            unregisterFeatureIfSame(getLineFeatureName((ScoreboardLineHolder) line), (ScoreboardLineHolder) line);
            TAB.getInstance().getPlaceholderManager().removeUsedFeature((ScoreboardLineHolder) line);
        }
        if (!api) {
            // Scoreboards from config are registered by the manager
            unregisterFeatureIfSame(TabConstants.Feature.scoreboardLine(name), this);
        }
        TAB.getInstance().getPlaceholderManager().removeUsedFeature(this);
    }

    /**
     * Unregisters feature with given name, but only if it is still the specified instance.
     * This prevents removal of a feature registered under the same name by a newer scoreboard
     * (such as when a scoreboard is removed and created again with the same name via API).
     *
     * @param   featureName
     *          Name of the feature
     * @param   feature
     *          Expected feature instance
     */
    private void unregisterFeatureIfSame(@NotNull String featureName, @NotNull TabFeature feature) {
        TabFeature registered = TAB.getInstance().getFeatureManager().getFeature(featureName);
        if (registered == feature) {
            TAB.getInstance().getFeatureManager().unregisterFeature(featureName);
        }
    }

    /**
     * Returns name under which specified line of this scoreboard is registered in feature manager.
     *
     * @param   line
     *          Line to get feature name of
     * @return  Feature name of the line
     */
    @NotNull
    private String getLineFeatureName(@NotNull ScoreboardLineHolder line) {
        return TabConstants.Feature.scoreboardLine(name, line.getLineNumber() - 1);
    }

    /**
     * Returns line number for a new line added to the bottom of this scoreboard.
     * Line numbers must be unique (they define team and score holder names), and increasing
     * from top to bottom (score holder names resolve score ties), so the number after
     * the last line's number is used. If it would exceed the supported maximum,
     * all lines are re-created with numbers starting at 1 first.
     *
     * @return  Line number for a new line
     */
    private int nextLineNumber() {
        if (lines.isEmpty()) return 1;
        int lastNumber = ((ScoreboardLineHolder) lines.get(lines.size() - 1)).getLineNumber();
        if (lastNumber < MAX_LINE_NUMBER || lines.size() >= MAX_LINE_NUMBER) return lastNumber + 1;
        List<String> texts = new ArrayList<>();
        for (Line line : lines) {
            texts.add(line.getText());
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            removeLine(i);
        }
        for (String text : texts) {
            addLine(text);
        }
        return lines.size() + 1;
    }

    @NotNull
    @Override
    public String getFeatureName() {
        return manager.getFeatureName();
    }

    // ------------------
    // API Implementation
    // ------------------

    @Override
    public void setTitle(@NonNull String title) {
        ensureActive();
        this.title = title;
        for (TabPlayer p : players) {
            p.scoreboardData.titleProperty.changeRawValue(title);
            p.getScoreboard().updateObjective(
                    ScoreboardManagerImpl.OBJECTIVE_NAME,
                    manager.getCache().get(p.scoreboardData.titleProperty.get()),
                    Scoreboard.HealthDisplay.INTEGER,
                    TabComponent.empty()
            );
        }
    }

    @Override
    public void addLine(@NonNull String text) {
        ensureActive();
        ScoreboardLineHolder line = new ScoreboardLineHolder(this, text, nextLineNumber());
        TAB.getInstance().getFeatureManager().registerFeature(getLineFeatureName(line), line);
        lines.add(line);
        for (TabPlayer p : players) {
            line.register(p);
        }
    }

    @Override
    public void removeLine(int index) {
        ensureActive();
        if (index < 0 || index >= lines.size()) throw new IndexOutOfBoundsException("Index " + index + " is out of range (0 - " + (lines.size()-1) + ")");
        ScoreboardLineHolder line = (ScoreboardLineHolder) lines.remove(index);
        for (TabPlayer p : players) {
            line.unregister(p);
            p.scoreboardData.lineProperties.remove(line); // Stop refreshing the removed line
        }
        unregisterFeatureIfSame(getLineFeatureName(line), line);
        TAB.getInstance().getPlaceholderManager().removeUsedFeature(line);
    }

    @Override
    public void setLines(@NonNull List<String> newLines) {
        ensureActive();
        int commonSize = Math.min(lines.size(), newLines.size());
        for (int i = 0; i < commonSize; i++) {
            String newText = newLines.get(i) == null ? "" : newLines.get(i);
            if (!lines.get(i).getText().equals(newText))
                lines.get(i).setText(newText);
        }
        for (int i = lines.size() - 1; i >= commonSize; i--)
            removeLine(i);
        for (int i = commonSize; i < newLines.size(); i++)
            addLine(newLines.get(i) == null ? "" : newLines.get(i));
    }

    @Override
    public void unregister() {
        ensureActive();
        for (TabPlayer all : players.toArray(new TabPlayer[0])) {
            removePlayer(all);
        }
        players.clear();
    }

    @Override
    @NotNull
    public ThreadExecutor getCustomThread() {
        return manager.getCustomThread();
    }
}
