package me.neznamy.tab.shared.config.mysql;

import lombok.NonNull;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.config.PropertyConfiguration;
import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.data.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.sql.rowset.CachedRowSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MySQLGroupConfiguration implements PropertyConfiguration {

    private final MySQL mysql;

    private final Map<String, Map<String, Object>> values = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Map<String, Object>>> perWorld = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Map<String, Object>>> perServer = new ConcurrentHashMap<>();

    public MySQLGroupConfiguration(@NonNull MySQL mysql) throws SQLException {
        this.mysql = mysql;
        mysql.execute("create table if not exists tab_groups (`group` varchar(64), `property` varchar(16), `value` varchar(1024), world varchar(64), server varchar(64))");
        CachedRowSet crs = mysql.getCRS("select * from tab_groups");
        if (crs.size() == 0) {
            TAB.getInstance().getConfigHelper().startup().startupWarn("[MySQL] Using MySQL to store groups and users, however, the database is empty. " +
                    "You can get started by uploading existing data in files using \"/" + TAB.getInstance().getPlatform().getCommand() + " mysql upload\". Further modifications can " +
                    "be done using property commands (/" + TAB.getInstance().getPlatform().getCommand() + " <group / player> <name> <property> <value...>).");
            return;
        }
        loadRows(crs, true);
    }

    /**
     * Loads all rows from given result set into memory.
     *
     * @param   crs
     *          Result set with rows from the group table
     * @param   startup
     *          Whether this is called on startup (to print warns as startup warns) or not
     * @throws  SQLException
     *          If reading the result set fails
     */
    private void loadRows(@NonNull CachedRowSet crs, boolean startup) throws SQLException {
        while (crs.next()) {
            String group = crs.getString("group");
            String property = crs.getString("property");
            if (group == null || property == null) continue;
            group = lowercase(group);
            String value = crs.getString("value");
            String world = crs.getString("world");
            String server = crs.getString("server");
            setProperty0(group, property, Server.byName(server), World.byName(world), value);
            checkProperty("MySQL", "group", group, property, server, world, startup);
        }
    }

    /**
     * Reloads all group data from the database, replacing data in memory.
     *
     * @throws  SQLException
     *          If loading data fails
     */
    public void reload() throws SQLException {
        CachedRowSet crs = mysql.getCRS("select * from tab_groups");
        values.clear();
        perWorld.clear();
        perServer.clear();
        loadRows(crs, false);
    }

    /**
     * Inserts a row directly into the database without updating values in memory.
     * Group name is converted to lowercase. This is intended to be used inside a transaction,
     * followed by {@link #reload()}.
     *
     * @param   group
     *          Group name
     * @param   property
     *          Property name
     * @param   world
     *          World name or {@code null} for global value
     * @param   server
     *          Server name or {@code null} for global value
     * @param   value
     *          Property value
     * @throws  SQLException
     *          If the query fails
     */
    public void insert(@NonNull String group, @NonNull String property, @Nullable String world, @Nullable String server, @NonNull String value) throws SQLException {
        mysql.execute("insert into `tab_groups` (`group`, `property`, `value`, `world`, `server`) values (?, ?, ?, ?, ?)",
                lowercase(group), property, value, world, server);
    }

    @Override
    public void setProperty(@NonNull String group, @NonNull String property, @Nullable Server server, @Nullable World world, @Nullable String value) {
        String lowercaseGroup = lowercase(group);
        String worldName = world == null ? null : world.getName();
        String serverName = server == null ? null : server.getName();
        setProperty0(lowercaseGroup, property, server, world, value);
        TAB.getInstance().getCPUManager().getMysqlThread().execute(() -> {
            try {
                mysql.executeInTransaction(() -> {
                    mysql.execute("delete from `tab_groups` where `group` = ? and `property` = ? and world " + querySymbol(worldName == null) + " ? and server " + querySymbol(serverName == null) + " ?",
                            lowercaseGroup, property, worldName, serverName);
                    if (value != null) mysql.execute("insert into `tab_groups` (`group`, `property`, `value`, `world`, `server`) values (?, ?, ?, ?, ?)",
                            lowercaseGroup, property, value, worldName, serverName);
                });
            } catch (SQLException e) {
                TAB.getInstance().getErrorManager().mysqlQueryFailed(e);
            }
        });
    }

    private String querySymbol(boolean isNull) {
        return isNull ? "is" : "=";
    }

    /**
     * Converts group name to lowercase, unless it is the default group.
     *
     * @param   group
     *          Group name to convert
     * @return  Group name in the format it is stored in
     */
    @NotNull
    private String lowercase(@NonNull String group) {
        return group.equals(TabConstants.DEFAULT_GROUP) ? group : group.toLowerCase(Locale.US);
    }

    private void setProperty0(@NonNull String group, @NonNull String property, @Nullable Server server, @Nullable World world, @Nullable String value) {
        Map<String, Object> properties;
        if (world != null) {
            properties = perWorld.computeIfAbsent(world.getName(), w -> new ConcurrentHashMap<>()).computeIfAbsent(group, g -> new ConcurrentHashMap<>());
        } else if (server != null) {
            properties = perServer.computeIfAbsent(server.getName(), s -> new ConcurrentHashMap<>()).computeIfAbsent(group, g -> new ConcurrentHashMap<>());
        } else {
            properties = values.computeIfAbsent(group, g -> new ConcurrentHashMap<>());
        }
        if (value == null) {
            properties.remove(property);
        } else {
            properties.put(property, value);
        }
    }

    @Nullable
    private Object getValue(@NonNull Map<String, Map<String, Map<String, Object>>> map, @Nullable String key, @NonNull String group, @NonNull String property) {
        if (key == null) return null;
        Map<String, Map<String, Object>> groups = map.get(key);
        if (groups == null) return null;
        return getValue(groups, group, property);
    }

    @Nullable
    private Object getValue(@NonNull Map<String, Map<String, Object>> map, @NonNull String group, @NonNull String property) {
        Map<String, Object> properties = map.get(group);
        return properties == null ? null : properties.get(property);
    }

    @Override
    public String[] getProperty(@NonNull String group, @NonNull String property, @Nullable Server server, @Nullable World world) {
        String lowercaseGroup = lowercase(group);
        String worldName = world == null ? null : world.getName();
        String serverName = server == null ? null : server.getName();
        Object value;
        if ((value = getValue(perWorld, worldName, lowercaseGroup, property)) != null) {
            return new String[] {toString(value), String.format("group=%s,world=%s", lowercaseGroup, worldName)};
        }
        if ((value = getValue(perWorld, worldName, TabConstants.DEFAULT_GROUP, property)) != null) {
            return new String[] {toString(value), String.format("group=%s,world=%s", TabConstants.DEFAULT_GROUP, worldName)};
        }
        if ((value = getValue(perServer, serverName, lowercaseGroup, property)) != null) {
            return new String[] {toString(value), String.format("group=%s,server=%s", lowercaseGroup, serverName)};
        }
        if ((value = getValue(perServer, serverName, TabConstants.DEFAULT_GROUP, property)) != null) {
            return new String[] {toString(value), String.format("group=%s,server=%s", TabConstants.DEFAULT_GROUP, serverName)};
        }
        if ((value = getValue(values, lowercaseGroup, property)) != null) {
            return new String[] {toString(value), String.format("group=%s", lowercaseGroup)};
        }
        if ((value = getValue(values, TabConstants.DEFAULT_GROUP, property)) != null) {
            return new String[] {toString(value), String.format("group=%s", TabConstants.DEFAULT_GROUP)};
        }
        return new String[0];
    }

    @Override
    public void remove(@NonNull String group) {
        String lowercaseGroup = lowercase(group);
        values.remove(lowercaseGroup);
        perWorld.values().forEach(groups -> groups.remove(lowercaseGroup));
        perServer.values().forEach(groups -> groups.remove(lowercaseGroup));
        TAB.getInstance().getCPUManager().getMysqlThread().execute(() -> {
            try {
                mysql.execute("delete from `tab_groups` where `group` = ?", lowercaseGroup);
            } catch (SQLException e) {
                TAB.getInstance().getErrorManager().mysqlQueryFailed(e);
            }
        });
    }

    @Override
    @NotNull
    public Map<String, Object> getGlobalSettings(@NonNull String name) {
        return values.getOrDefault(lowercase(name), Collections.emptyMap());
    }

    @Override
    @NotNull
    public Map<String, Map<String, Object>> getPerWorldSettings(@NonNull String name) {
        return convertMap(perWorld, lowercase(name));
    }

    @Override
    @NotNull
    public Map<String, Map<String, Object>> getPerServerSettings(@NonNull String name) {
        return convertMap(perServer, lowercase(name));
    }

    @Override
    @NotNull
    public Set<String> getAllEntries() {
        Set<String> set = new HashSet<>(values.keySet());
        perWorld.values().forEach(map -> set.addAll(map.keySet()));
        perServer.values().forEach(map -> set.addAll(map.keySet()));
        return set;
    }
}