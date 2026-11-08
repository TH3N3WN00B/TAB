package me.neznamy.tab.shared.config.mysql;

import lombok.NonNull;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.config.PropertyConfiguration;
import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.data.World;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.sql.rowset.CachedRowSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MySQLUserConfiguration implements PropertyConfiguration {

    private final MySQL mysql;

    private final Map<TabPlayer, Map<String, Object>> values = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<World, Map<TabPlayer, Map<String, Object>>> perWorld = new ConcurrentHashMap<>();
    private final Map<Server, Map<TabPlayer, Map<String, Object>>> perServer = new ConcurrentHashMap<>();

    public MySQLUserConfiguration(@NonNull MySQL mysql) throws SQLException {
        this.mysql = mysql;
        mysql.execute("create table if not exists tab_users (`user` varchar(64), `property` varchar(16), `value` varchar(1024), world varchar(64), server varchar(64))");
    }

    /**
     * Inserts a row directly into the database without updating values in memory.
     * User name is converted to lowercase. This is intended to be used inside a transaction,
     * followed by {@link #reload()}.
     *
     * @param   user
     *          User name or UUID
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
    public void insert(@NonNull String user, @NonNull String property, @Nullable String world, @Nullable String server, @NonNull String value) throws SQLException {
        mysql.execute("insert into `tab_users` (`user`, `property`, `value`, `world`, `server`) values (?, ?, ?, ?, ?)",
                user.toLowerCase(), property, value, world, server);
    }

    /**
     * Clears all user data in memory and loads it again from the database for all online players.
     */
    public void reload() {
        values.clear();
        perWorld.clear();
        perServer.clear();
        for (TabPlayer player : TAB.getInstance().getOnlinePlayers()) {
            load(player);
        }
    }

    @Override
    public void setProperty(@NonNull String user, @NonNull String property, @Nullable Server server, @Nullable World world, @Nullable String value) {
        TabPlayer p = getPlayer(user);
        String lowercaseUser = user.toLowerCase();
        String worldName = world == null ? null : world.getName();
        String serverName = server == null ? null : server.getName();
        if (p != null) setProperty0(p, property, server, world, value);
        TAB.getInstance().getCPUManager().getMysqlThread().execute(() -> {
            try {
                mysql.executeInTransaction(() -> {
                    mysql.execute("delete from `tab_users` where `user` = ? and `property` = ? and world " + querySymbol(worldName == null) + " ? and server " + querySymbol(serverName == null) + " ?",
                            lowercaseUser, property, worldName, serverName);
                    if (value != null) mysql.execute("insert into `tab_users` (`user`, `property`, `value`, `world`, `server`) values (?, ?, ?, ?, ?)",
                            lowercaseUser, property, value, worldName, serverName);
                });
            } catch (SQLException e) {
                TAB.getInstance().getErrorManager().mysqlQueryFailed(e);
            }
        });
    }

    private String querySymbol(boolean isNull) {
        return isNull ? "is" : "=";
    }

    private void setProperty0(@NonNull TabPlayer user, @NonNull String property, @Nullable Server server, @Nullable World world, @Nullable String value) {
        Map<String, Object> properties;
        if (world != null) {
            properties = perWorld.computeIfAbsent(world, w -> Collections.synchronizedMap(new WeakHashMap<>())).computeIfAbsent(user, g -> new ConcurrentHashMap<>());
        } else if (server != null) {
            properties = perServer.computeIfAbsent(server, s -> Collections.synchronizedMap(new WeakHashMap<>())).computeIfAbsent(user, g -> new ConcurrentHashMap<>());
        } else {
            properties = values.computeIfAbsent(user, g -> new ConcurrentHashMap<>());
        }
        if (value == null) {
            properties.remove(property);
        } else {
            properties.put(property, value);
        }
    }

    @Nullable
    private Object getValue(@Nullable Map<TabPlayer, Map<String, Object>> map, @Nullable TabPlayer player, @NonNull String property) {
        if (map == null || player == null) return null;
        Map<String, Object> properties = map.get(player);
        return properties == null ? null : properties.get(property);
    }

    @Override
    public String[] getProperty(@NonNull String user, @NonNull String property, @Nullable Server server, @Nullable World world) {
        TabPlayer p = getPlayer(user);
        Object value;
        if (world != null && (value = getValue(perWorld.get(world), p, property)) != null) {
            return new String[] {toString(value), String.format("user=%s,world=%s", user, world.getName())};
        }
        if (server != null && (value = getValue(perServer.get(server), p, property)) != null) {
            return new String[] {toString(value), String.format("user=%s,server=%s", user, server.getName())};
        }
        if ((value = getValue(values, p, property)) != null) {
            return new String[] {toString(value), String.format("user=%s", user)};
        }
        return new String[0];
    }

    @Override
    public void remove(@NonNull String player) {
        String lowercaseUser = player.toLowerCase();
        TAB.getInstance().getCPUManager().getMysqlThread().execute(() -> {
            try {
                mysql.execute("delete from `tab_users` where `user` = ?", lowercaseUser);
            } catch (SQLException e) {
                TAB.getInstance().getErrorManager().mysqlQueryFailed(e);
            }
        });
        TabPlayer user = getPlayer(player);
        if (user == null) return;
        values.remove(user);
        for (Map<TabPlayer, Map<String, Object>> worldValues : perWorld.values()) {
            worldValues.remove(user);
        }
        for (Map<TabPlayer, Map<String, Object>> serverValues : perServer.values()) {
            serverValues.remove(user);
        }
    }

    @Override
    @NotNull
    public Map<String, Object> getGlobalSettings(@NonNull String name) {
        throw new UnsupportedOperationException("Not supported for users");
    }

    @Override
    @NotNull
    public Map<String, Map<String, Object>> getPerWorldSettings(@NonNull String name) {
        throw new UnsupportedOperationException("Not supported for users");
    }

    @Override
    @NotNull
    public Map<String, Map<String, Object>> getPerServerSettings(@NonNull String name) {
        throw new UnsupportedOperationException("Not supported for users");
    }

    @Override
    @NotNull
    public Set<String> getAllEntries() {
        throw new UnsupportedOperationException("Not supported for users");
    }

    @Nullable
    private TabPlayer getPlayer(@NonNull String string) {
        TabPlayer p = TAB.getInstance().getPlayer(string);
        if (p == null) {
            try {
                p = TAB.getInstance().getPlayer(UUID.fromString(string));
            } catch (IllegalArgumentException ex) {
                //not a valid uuid
            }
        }
        return p;
    }

    public void load(@NonNull TabPlayer player) {
        TAB.getInstance().getCPUManager().getMysqlThread().execute(() -> {

            try {
                CachedRowSet crs = mysql.getCRS("select * from `tab_users` where `user` = ?", player.getName().toLowerCase());
                while (crs.next()) {
                    String user = crs.getString("user");
                    String property = crs.getString("property");
                    String value = crs.getString("value");
                    String world = crs.getString("world");
                    String server = crs.getString("server");
                    TAB.getInstance().debug("Loaded user line: " + String.format("%s, %s, %s, %s, %s", user, property, value, world, server));
                    if (user == null || property == null) continue;
                    checkProperty("MySQL", "player", user, property, server, world, false);
                    setProperty0(player, property, Server.byName(server), World.byName(world), value);
                }
                CachedRowSet crs2 = mysql.getCRS("select * from `tab_users` where `user` = ?", player.getUniqueId().toString());
                while (crs2.next()) {
                    String user = crs2.getString("user");
                    String property = crs2.getString("property");
                    String value = crs2.getString("value");
                    String world = crs2.getString("world");
                    String server = crs2.getString("server");
                    TAB.getInstance().debug("Loaded user line: " + String.format("%s, %s, %s, %s, %s", user, property, value, world, server));
                    if (user == null || property == null) continue;
                    checkProperty("MySQL", "player", user, property, server, world, false);
                    setProperty0(player, property, Server.byName(server), World.byName(world), value);
                }
                TAB.getInstance().debug("Loaded MySQL data of " + player.getName());
                if (crs.size() > 0 || crs2.size() > 0) {
                    TAB.getInstance().getFeatureManager().onGroupChange(player);
                }
            } catch (SQLException e) {
                TAB.getInstance().getErrorManager().mysqlQueryFailed(e);
            }
        });
    }
}