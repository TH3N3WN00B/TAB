package me.neznamy.tab.shared.command;

import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.data.World;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.config.Configs;
import me.neznamy.tab.shared.config.mysql.MySQL;
import me.neznamy.tab.shared.config.mysql.MySQLGroupConfiguration;
import me.neznamy.tab.shared.config.mysql.MySQLUserConfiguration;
import me.neznamy.tab.shared.config.file.YamlPropertyConfigurationFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.sql.rowset.CachedRowSet;
import java.io.File;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MySQLCommand extends SubCommand {

    /**
     * Constructs new instance
     */
    protected MySQLCommand() {
        super("mysql", null);
    }

    @Override
    public void execute(@Nullable TabPlayer sender, @NotNull String[] args) {
        if (args.length == 0) {
            sendMessages(sender, getMessages().getMySQLHelpMenu());
            return;
        }
        if (args[0].equalsIgnoreCase("download")) {
            if (hasPermission(sender, TabConstants.Permission.COMMAND_MYSQL_DOWNLOAD)) {
                download(sender);
            } else {
                sendMessage(sender, getMessages().getNoPermission());
            }
        } else if (args[0].equalsIgnoreCase("upload")) {
            if (hasPermission(sender, TabConstants.Permission.COMMAND_MYSQL_UPLOAD)) {
                upload(sender);
            } else {
                sendMessage(sender, getMessages().getNoPermission());
            }
        } else {
            for (String message : getMessages().getMySQLHelpMenu()) {
                sendMessage(sender, message);
            }
        }
    }

    private void download(@Nullable TabPlayer sender) {
        MySQL mysql = TAB.getInstance().getConfiguration().getMysql();
        if (mysql == null) {
            sendMessage(sender, getMessages().getMySQLFailNotEnabled());
            return;
        }
        TAB.getInstance().getCPUManager().getMysqlThread().execute(() -> {
            try {
                YamlPropertyConfigurationFile groupFile = new YamlPropertyConfigurationFile(Configs.class.getClassLoader().getResourceAsStream("config/groups.yml"), new File(TAB.getInstance().getDataFolder(), "groups.yml"));
                YamlPropertyConfigurationFile userFile = new YamlPropertyConfigurationFile(Configs.class.getClassLoader().getResourceAsStream("config/users.yml"), new File(TAB.getInstance().getDataFolder(), "users.yml"));
                CachedRowSet crs = mysql.getCRS("select * from tab_groups");
                while (crs.next()) {
                    groupFile.setProperty(crs.getString("group"), crs.getString("property"),
                            Server.byName(crs.getString("server")), World.byName(crs.getString("world")), crs.getString("value"));
                }
                crs = mysql.getCRS("select * from tab_users");
                while (crs.next()) {
                    userFile.setProperty(crs.getString("user"), crs.getString("property"),
                            Server.byName(crs.getString("server")), World.byName(crs.getString("world")), crs.getString("value"));
                }
                sendMessage(sender, getMessages().getMySQLDownloadSuccess());
            } catch (Exception e) {
                sendMessage(sender, getMessages().getMySQLFailError());
                TAB.getInstance().getErrorManager().criticalError("MySQL download failed", e);
            }
        });
    }

    private void upload(@Nullable TabPlayer sender) {
        Configs configs = TAB.getInstance().getConfiguration();
        MySQL mysql = configs.getMysql();
        if (mysql == null || !(configs.getGroups() instanceof MySQLGroupConfiguration) || !(configs.getUsers() instanceof MySQLUserConfiguration)) {
            sendMessage(sender, getMessages().getMySQLFailNotEnabled());
            return;
        }
        MySQLGroupConfiguration groups = (MySQLGroupConfiguration) configs.getGroups();
        MySQLUserConfiguration users = (MySQLUserConfiguration) configs.getUsers();
        TAB.getInstance().getCPUManager().getMysqlThread().execute(() -> {
            try {
                YamlPropertyConfigurationFile groupFile = new YamlPropertyConfigurationFile(Configs.class.getClassLoader().getResourceAsStream("config/groups.yml"), new File(TAB.getInstance().getDataFolder(), "groups.yml"));
                YamlPropertyConfigurationFile userFile = new YamlPropertyConfigurationFile(Configs.class.getClassLoader().getResourceAsStream("config/users.yml"), new File(TAB.getInstance().getDataFolder(), "users.yml"));
                // Everything in a single transaction, so any failure leaves the database untouched
                mysql.executeInTransaction(() -> {
                    mysql.execute("DELETE FROM tab_groups");
                    mysql.execute("DELETE FROM tab_users");
                    upload(groupFile, groups::insert);
                    upload(userFile, users::insert);
                });
            } catch (Throwable e) {
                sendMessage(sender, getMessages().getMySQLFailError());
                TAB.getInstance().getErrorManager().criticalError("MySQL upload failed", e);
                return;
            }
            try {
                // Load uploaded data into memory
                groups.reload();
                users.reload();
                for (TabPlayer player : TAB.getInstance().getOnlinePlayers()) {
                    TAB.getInstance().getFeatureManager().onGroupChange(player);
                }
                sendMessage(sender, getMessages().getMySQLUploadSuccess());
            } catch (Throwable e) {
                sendMessage(sender, getMessages().getMySQLFailError());
                TAB.getInstance().getErrorManager().criticalError("MySQL upload succeeded, but uploaded data failed to load", e);
            }
        });
    }

    private void upload(@NotNull YamlPropertyConfigurationFile file, @NotNull RowInserter inserter) throws SQLException {
        for (String name : file.getAllEntries()) {
            for (Map.Entry<String, Object> property : file.getGlobalSettings(name).entrySet()) {
                insert(inserter, name, property.getKey(), null, null, property.getValue());
            }
            for (Map.Entry<String, Map<String, Object>> world : file.getPerWorldSettings(name).entrySet()) {
                if (world.getValue() == null) continue;
                for (Map.Entry<String, Object> property : world.getValue().entrySet()) {
                    insert(inserter, name, property.getKey(), world.getKey(), null, property.getValue());
                }
            }
            for (Map.Entry<String, Map<String, Object>> server : file.getPerServerSettings(name).entrySet()) {
                if (server.getValue() == null) continue;
                for (Map.Entry<String, Object> property : server.getValue().entrySet()) {
                    insert(inserter, name, property.getKey(), null, server.getKey(), property.getValue());
                }
            }
        }
    }

    /**
     * Inserts a row using given inserter, skipping it if property or value is {@code null}.
     *
     * @param   inserter
     *          Inserter to use
     * @param   name
     *          Group or user name
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
    private void insert(@NotNull RowInserter inserter, @NotNull String name, @Nullable Object property,
                        @Nullable String world, @Nullable String server, @Nullable Object value) throws SQLException {
        if (property == null || value == null) return;
        inserter.insert(name, String.valueOf(property), world, server, toString(value));
    }

    @SuppressWarnings("unchecked")
    @NotNull
    private String toString(@NotNull Object obj) {
        if (obj instanceof List) {
            return ((List<Object>)obj).stream().map(String::valueOf).collect(Collectors.joining("\n"));
        }
        return obj.toString();
    }

    @Override
    public @NotNull List<String> complete(@Nullable TabPlayer sender, @NotNull String[] arguments) {
        return getStartingArgument(Arrays.asList("download", "upload"), arguments[0]);
    }

    /**
     * Function inserting a single row into the database.
     */
    @FunctionalInterface
    private interface RowInserter {

        /**
         * Inserts a row into the database.
         *
         * @param   name
         *          Group or user name
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
        void insert(@NotNull String name, @NotNull String property, @Nullable String world, @Nullable String server, @NotNull String value) throws SQLException;
    }
}
