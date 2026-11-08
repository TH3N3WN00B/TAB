package me.neznamy.tab.shared.config.mysql;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import me.neznamy.tab.shared.config.file.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * This class represents MySQL configuration section.
 */
@Getter
@RequiredArgsConstructor
public class MySQLConfiguration {
    
    @NonNull private final String host;
    private final int port;
    @NonNull private final String database;
    @NonNull private final String username;
    @NonNull private final String password;
    private final boolean useSSL;

    /**
     * Returns instance of this class created from given configuration section. If there are
     * issues in the configuration, console warns are printed.
     *
     * @param   section
     *          Configuration section to load from
     * @return  Loaded instance from given configuration section
     */
    @NotNull
    public static MySQLConfiguration fromSection(@NonNull ConfigurationSection section) {
        // Check keys
        section.checkForUnknownKey(Arrays.asList("enabled", "host", "port", "database", "username", "password", "usessl"));

        return new MySQLConfiguration(
                getScalar(section, "host", "127.0.0.1"),
                getPort(section),
                getScalar(section, "database", "tab"),
                getScalar(section, "username", "user"),
                getScalar(section, "password", "password"),
                getUseSSL(section)
        );
    }

    /**
     * Returns value of given option as a string. Any scalar value (such as a number) is accepted
     * and converted to string. Values are never printed in warnings, as they may be sensitive.
     *
     * @param   section
     *          Configuration section to load from
     * @param   path
     *          Name of the option
     * @param   defaultValue
     *          Value to use if option is missing or invalid
     * @return  Configured value as a string or default value if missing or invalid
     */
    @NotNull
    private static String getScalar(@NonNull ConfigurationSection section, @NonNull String path, @NonNull String defaultValue) {
        Object value = section.getObject(path);
        if (value == null) {
            section.startupWarn("Missing option \"" + path + "\", using default value.");
            return defaultValue;
        }
        if (value instanceof String) {
            return section.getString(path, defaultValue);
        }
        if (value instanceof Map || value instanceof Collection) {
            section.startupWarn("Option \"" + path + "\" is expected to be a single value, but was " +
                    value.getClass().getSimpleName() + ". Using default value.");
            return defaultValue;
        }
        return String.valueOf(value);
    }

    /**
     * Returns configured port. Values are never printed in warnings.
     *
     * @param   section
     *          Configuration section to load from
     * @return  Configured port or {@code 3306} if missing or invalid
     */
    private static int getPort(@NonNull ConfigurationSection section) {
        Object value = section.getObject("port");
        if (value instanceof Integer) return (Integer) value;
        String port = getScalar(section, "port", "3306");
        try {
            return Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            section.startupWarn("Option \"port\" is expected to be a whole number. Using default value 3306.");
            return 3306;
        }
    }

    /**
     * Returns configured SSL usage. Values are never printed in warnings.
     *
     * @param   section
     *          Configuration section to load from
     * @return  Configured SSL usage or {@code true} if missing or invalid
     */
    private static boolean getUseSSL(@NonNull ConfigurationSection section) {
        Object value = section.getObject("useSSL");
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String && (value.toString().equalsIgnoreCase("true") || value.toString().equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(value.toString());
        }
        section.startupWarn((value == null ? "Missing option \"useSSL\"" : "Option \"useSSL\" is expected to be true or false") + ", using default value true.");
        return true;
    }
}
