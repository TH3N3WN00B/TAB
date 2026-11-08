package me.neznamy.tab.shared.config.skin;

import me.neznamy.tab.shared.config.file.ConfigurationFile;
import me.neznamy.tab.shared.platform.TabList.Skin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract class for skin sources for getting skins.
 */
public abstract class SkinSource {

    /** Timeout for connecting to skin APIs in milliseconds */
    protected static final int CONNECT_TIMEOUT = 5000;

    /** Timeout for reading responses from skin APIs in milliseconds */
    protected static final int READ_TIMEOUT = 10000;

    /** Configuration file for storing cache */
    @NotNull private final ConfigurationFile file;

    /** Cache path of this source */
    @NotNull private final String path;

    /** Current cache of this source, access must be synchronized on {@link #file}, which saves it */
    @NotNull private final Map<String, List<String>> cache;

    /** Cached skins as skin objects */
    @NotNull private final Map<String, Skin> skins = new ConcurrentHashMap<>();

    protected SkinSource(@NotNull ConfigurationFile file, @NotNull String path) {
        this.file = file;
        this.path = path;
        cache = file.getMap(path);
        for (Map.Entry<String, List<String>> entry : cache.entrySet()) {
            skins.put(entry.getKey(), new Skin(entry.getValue().get(0), entry.getValue().get(1)));
        }
    }

    /**
     * Returns skin using given skin definition.
     *
     * @param   skin
     *          Skin definition
     * @return  Skin from definition or null if invalid
     */
    @Nullable
    public Skin getSkin(@NotNull String skin) {
        if (skins.containsKey(skin)) {
            return skins.get(skin);
        }
        Skin downloaded = download(skin);
        if (downloaded != null) {
            skins.put(skin, downloaded);
            // All sources share the same file, synchronize so the cache is not modified while the file is being saved
            synchronized (file) {
                cache.put(skin, Arrays.asList(downloaded.getValue(), downloaded.getSignature()));
                file.set(path, cache);
            }
        }
        return downloaded;
    }

    /**
     * Downloads skin with given skin definition.
     *
     * @param   input
     *          Skin definition
     * @return  Downloaded skin or null if invalid
     */
    @Nullable
    public abstract Skin download(@NotNull String input);

    @NotNull
    protected JSONObject getResponse(@NotNull String url) throws IOException, ParseException {
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
            return (JSONObject) new JSONParser().parse(reader);
        }
    }
}
