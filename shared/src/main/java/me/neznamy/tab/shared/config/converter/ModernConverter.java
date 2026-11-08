package me.neznamy.tab.shared.config.converter;

import lombok.NonNull;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.chat.TabTextColor;
import me.neznamy.tab.shared.chat.component.TabTextComponent;
import me.neznamy.tab.shared.config.file.ConfigurationFile;
import me.neznamy.tab.shared.config.file.ConfigurationSection;
import me.neznamy.tab.shared.placeholders.conditions.Condition;
import me.neznamy.tab.shared.placeholders.conditions.ConditionsSection;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Config converter that uses new config-version field.
 */
public class ModernConverter {

    /** Converters from one config version to another */
    private final Map<Integer, Consumer<ConfigurationFile>> converters = new HashMap<>();

    /**
     * Constructs new instance and initializes all converters.
     */
    public ModernConverter() {
        converters.put(0, config -> {
            // Only add missing options, do not overwrite existing ones (config-version may just be missing)
            config.setIfMissing("components.minimessage-support", true);
            config.setIfMissing("components.disable-shadow-for-heads", true);
        });
        converters.put(1, config -> {
            // Only convert if header-footer is in the old format, otherwise it would be overwritten by defaults
            ConfigurationSection oldHeaderFooter = config.getConfigurationSection("header-footer");
            if (oldHeaderFooter.getObject("designs") != null) return;
            if (!hasAnyKey(oldHeaderFooter, "header", "footer", "disable-condition", "per-world", "per-server")) return;

            // Warn users
            TAB.getInstance().getPlatform().logWarn(new TabTextComponent("Please note that header/footer conversion may not be 100% accurate and will not convert" +
                    " per-group settings and per-user settings." +
                    " Review your config to make sure it is set up the way you want.", TabTextColor.RED));

            // Read old data
            ConfigurationSection headerFooter = config.getConfigurationSection("header-footer");
            boolean enabled = headerFooter.getBoolean("enabled", true);
            List<String> defaultHeader = headerFooter.getStringList("header", new ArrayList<>());
            List<String> defaultFooter = headerFooter.getStringList("footer", new ArrayList<>());
            String disableCondition = headerFooter.getString("disable-condition", "%world%=disabledworld");
            ConfigurationSection perWorld = headerFooter.getConfigurationSection("per-world");
            ConfigurationSection perServer = headerFooter.getConfigurationSection("per-server");

            // Write new data
            Map<String, Object> designs = new LinkedHashMap<>();

            for (Object world : perWorld.getKeys()) {
                ConfigurationSection worldSection = perWorld.getConfigurationSection(world.toString());
                Map<String, Object> design = new LinkedHashMap<>();
                design.put("display-condition", Arrays.stream(world.toString().split(";")).map(part -> {
                    if (part.endsWith("*")) {
                        return "%world%|-" + part.substring(0, part.length() - 1);
                    } else if (part.startsWith("*")) {
                        return "%world%-|" + part.substring(1);
                    } else {
                        return "%world%=" + part;
                    }
                }).collect(Collectors.joining("|")));
                List<String> header = worldSection.getStringList("header");
                if (header == null) header = new ArrayList<>();
                List<String> footer = worldSection.getStringList("footer");
                if (footer == null) footer = new ArrayList<>();
                design.put("header", header);
                design.put("footer", footer);
                designs.put("world-" + world, design);
            }

            for (Object server : perServer.getKeys()) {
                ConfigurationSection serverSection = perServer.getConfigurationSection(server.toString());
                Map<String, Object> design = new LinkedHashMap<>();
                design.put("display-condition", Arrays.stream(server.toString().split(";")).map(part -> {
                    if (part.endsWith("*")) {
                        return "%server%|-" + part.substring(0, part.length() - 1);
                    } else if (part.startsWith("*")) {
                        return "%server%-|" + part.substring(1);
                    } else {
                        return "%server%=" + part;
                    }
                }).collect(Collectors.joining("|")));
                List<String> header = serverSection.getStringList("header");
                if (header == null) header = new ArrayList<>();
                List<String> footer = serverSection.getStringList("footer");
                if (footer == null) footer = new ArrayList<>();
                design.put("header", header);
                design.put("footer", footer);
                designs.put("server-" + server, design);
            }

            Map<String, Object> defaultDesign = new LinkedHashMap<>();
            if (!disableCondition.isEmpty()) {
                ConditionsSection conditions = ConditionsSection.fromSection(config.getConfigurationSection("conditions"));
                ConditionsSection.ConditionDefinition namedCondition = conditions.getConditions().get(disableCondition);
                if (namedCondition != null) {
                    // Named condition
                    defaultDesign.put("display-condition", "%condition:" + namedCondition.getName() + "%=" + namedCondition.getNo());
                } else {
                    // Short format
                    defaultDesign.put("display-condition", Condition.fromShortFormat(disableCondition).invert().toShortFormat());
                }
            }
            defaultDesign.put("header", defaultHeader);
            defaultDesign.put("footer", defaultFooter);
            designs.put("default", defaultDesign);

            Map<String, Object> newHeaderFooter = new LinkedHashMap<>();
            newHeaderFooter.put("enabled", enabled);
            newHeaderFooter.put("designs", designs);
            config.set("header-footer", newHeaderFooter);
        });
        converters.put(2, config -> {
            config.removeOption("scoreboard.use-numbers");
            config.removeOption("scoreboard.static-number");
        });
        converters.put(3, config -> config.setIfMissing("proxy-support.channel-name-suffix", "custom"));
        converters.put(4, config -> {
            ConfigurationSection layout = config.getConfigurationSection("layout");
            ConfigurationSection layouts = layout.getConfigurationSection("layouts");
            for (Object layoutName : layouts.getKeys()) {
                ConfigurationSection singleLayout = layouts.getConfigurationSection(layoutName.toString());
                // Only convert old "condition" key if present, otherwise the new "display-condition" would be removed
                if (hasAnyKey(singleLayout, "condition")) {
                    singleLayout.put("display-condition", singleLayout.getString("condition"));
                    singleLayout.remove("condition");
                }
                ConfigurationSection groups = singleLayout.getConfigurationSection("groups");
                for (Object groupName : groups.getKeys()) {
                    ConfigurationSection singleGroup = groups.getConfigurationSection(groupName.toString());
                    if (hasAnyKey(singleGroup, "condition")) {
                        singleGroup.put("display-condition", singleGroup.getString("condition"));
                        singleGroup.remove("condition");
                    }
                }
            }
        });
        converters.put(5, config -> {
            ConfigurationSection placeholders = config.getConfigurationSection("placeholders");
            if (!hasAnyKey(placeholders, "locale")) placeholders.put("locale", "en-US");
        });
        converters.put(6, config -> {
            ConfigurationSection belowname = config.getConfigurationSection("belowname-objective");
            if (!hasAnyKey(belowname, "view-distance")) belowname.put("view-distance", 10);
        });
    }

    /**
     * Returns {@code true} if given section contains at least one of the given keys (ignoring case).
     *
     * @param   section
     *          Section to check
     * @param   keys
     *          Keys to look for
     * @return  {@code true} if at least one key is present, {@code false} if not
     */
    private boolean hasAnyKey(@NonNull ConfigurationSection section, @NonNull String... keys) {
        for (Object sectionKey : section.getKeys()) {
            for (String key : keys) {
                if (key.equalsIgnoreCase(String.valueOf(sectionKey))) return true;
            }
        }
        return false;
    }

    /**
     * Converts config to the latest version.
     *
     * @param   config
     *          config file to convert
     */
    public void convert(@NonNull ConfigurationFile config) {
        int configVersion = config.getInt("config-version", 0);
        while (converters.containsKey(configVersion)) {
            TAB.getInstance().getPlatform().logInfo(new TabTextComponent("Performing configuration conversion from config version " + configVersion + " to " + (configVersion + 1), TabTextColor.YELLOW));
            converters.get(configVersion).accept(config);
            configVersion++;
            config.set("config-version", configVersion);
        }
    }
}
