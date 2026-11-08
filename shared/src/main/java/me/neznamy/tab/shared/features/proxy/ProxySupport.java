package me.neznamy.tab.shared.features.proxy;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import lombok.Getter;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants.CpuUsageCategory;
import me.neznamy.tab.shared.cpu.TimedCaughtTask;
import me.neznamy.tab.shared.data.Server;
import me.neznamy.tab.shared.features.proxy.message.*;
import me.neznamy.tab.shared.features.types.*;
import me.neznamy.tab.shared.platform.TabPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Feature synchronizing player display data between
 * multiple servers connected with a proxy messenger.
 */
@SuppressWarnings("UnstableApiUsage")
@Getter
public abstract class ProxySupport extends TabFeature implements JoinListener, QuitListener,
        Loadable, UnLoadable, ServerSwitchListener,
        VanishListener {

    /** Name of the messaging channel */
    @NotNull
    private final String channelName;

    /** Proxy players on other proxies by their UUID */
    @NotNull protected final Map<UUID, ProxyPlayer> proxyPlayers = new ConcurrentHashMap<>();

    /** Queued data of players on other proxies by their UUID */
    @NotNull private final Map<UUID, QueuedData> queuedData = new ConcurrentHashMap<>();

    /** UUID of this proxy to ignore messages coming from the same proxy */
    @NotNull private final UUID proxy = UUID.randomUUID();

    @NotNull private final Map<String, Function<ByteArrayDataInput, ProxyMessage>> stringToClass = new HashMap<>();
    @NotNull private final Map<Class<? extends ProxyMessage>, String> classToString = new HashMap<>();

    /** ID generator for messages requiring an ID */
    private final AtomicLong idCounter = new AtomicLong(0);

    protected ProxySupport(@NotNull String channelName) {
        this.channelName = channelName;
        registerMessage(Load.class, Load::new);
        registerMessage(LoadRequest.class, in -> new LoadRequest());
        registerMessage(PlayerJoin.class, PlayerJoin::new);
        registerMessage(PlayerQuit.class, PlayerQuit::new);
        registerMessage(ServerSwitch.class, ServerSwitch::new);
        registerMessage(UpdateVanishStatus.class, UpdateVanishStatus::new);
        TAB.getInstance().debug("[Proxy Support] Using channel name: " + channelName);
    }

    @NotNull
    @Override
    public String getFeatureName() {
        return "ProxySupport";
    }

    /**
     * Processes incoming proxy message
     *
     * @param   msg
     *          json message to process
     */
    public synchronized void processMessage(@NotNull String msg) {
        ByteArrayDataInput in = ByteStreams.newDataInput(Base64.getDecoder().decode(msg));
        String proxy = in.readUTF();
        if (proxy.equals(this.proxy.toString())) return; // Message coming from current proxy
        String action = in.readUTF();
        Function<ByteArrayDataInput, ProxyMessage> function = stringToClass.get(action);
        if (function == null) {
            TAB.getInstance().getErrorManager().unknownProxyMessage(action);
            return;
        }
        ProxyMessage proxyMessage;
        try {
            proxyMessage = function.apply(in);
            proxyMessage.setSourceProxy(proxy);
            TAB.getInstance().debug("[Proxy Support] Decoded message " + proxyMessage);
        } catch (Exception e) {
            TAB.getInstance().getErrorManager().printError("Failed to decode proxy message \"" + new String(Base64.getDecoder().decode(msg)) + "\" ", e);
            return;
        }

        // Queue the task to make sure it does not execute before plugin fully loads, causing NPE
        TAB.getInstance().getCpu().runMeasuredTask(getFeatureName(), CpuUsageCategory.PROXY_MESSAGE, () -> {
            if (proxyMessage.getCustomThread() != null) {
                proxyMessage.getCustomThread().execute(new TimedCaughtTask(TAB.getInstance().getCpu(), () -> proxyMessage.process(this), getFeatureName(), CpuUsageCategory.PROXY_MESSAGE));
            } else {
                proxyMessage.process(this);
            }
        });
    }

    /**
     * Sends message to all proxies
     *
     * @param   message
     *          message to send
     */
    public abstract void sendMessage(@NotNull String message);

    /**
     * Registers event and proxy message listeners
     */
    public abstract void register();

    /**
     * Unregisters event and proxy message listeners
     */
    public abstract void unregister();

    @Override
    public void load() {
        register();
        for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) onJoin(p);
        sendMessage(new LoadRequest());
    }

    @Override
    public void unload() {
        for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) onQuit(p);
        unregister();
    }

    @Override
    public void onJoin(@NotNull TabPlayer p) {
        sendMessage(new PlayerJoin(p));
    }

    @Override
    public void onServerChange(@NotNull TabPlayer p, @NotNull Server from, @NotNull Server to) {
        sendMessage(new ServerSwitch(p.getUniqueId(), to));
    }

    @Override
    public void onQuit(@NotNull TabPlayer p) {
        sendMessage(new PlayerQuit(p.getUniqueId()));
    }

    /**
     * Sends message to other proxies.
     *
     * @param   message
     *          Message to send
     */
    public void sendMessage(@NotNull ProxyMessage message) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(proxy.toString());
        out.writeUTF(classToString.get(message.getClass()));
        TAB.getInstance().debug("[Proxy Support] Encoding message " + message);
        message.write(out);
        sendMessage(Base64.getEncoder().encodeToString(out.toByteArray()));
    }

    /**
     * Registers proxy message.
     *
     * @param   clazz
     *          Message class
     * @param   function
     *          Message function
     */
    public void registerMessage(@NotNull Class<? extends ProxyMessage> clazz, @NotNull Function<ByteArrayDataInput, ProxyMessage> function) {
        stringToClass.put(clazz.getSimpleName(), function);
        classToString.put(clazz, clazz.getSimpleName());
    }

    @Override
    public void onVanishStatusChange(@NotNull TabPlayer player) {
        sendMessage(new UpdateVanishStatus(player.getUniqueId(), player.isVanished()));
    }

    /**
     * Returns proxy player with given UUID if they are connected to the proxy which sent the given message.
     * If the player is connected to a different proxy, the message belongs to a different session of the
     * player (a late message from the previous proxy or a message from the new proxy which arrived before
     * its join message), so {@code null} is returned and the data should be queued instead.
     *
     * @param   playerId
     *          UUID of the player
     * @param   message
     *          Message which is being processed
     * @return  Proxy player connected to the proxy which sent the message or {@code null} if there is none
     */
    @Nullable
    public ProxyPlayer getProxyPlayer(@NotNull UUID playerId, @NotNull ProxyMessage message) {
        ProxyPlayer player = proxyPlayers.get(playerId);
        if (player == null || !isSameProxy(player.getProxyId(), message.getSourceProxy())) return null;
        return player;
    }

    /**
     * Removes queued data which has been waiting for player join for too long. Such data belongs
     * to players who will never join (for example it arrived after the player already quit).
     */
    public void purgeExpiredQueuedData() {
        long now = System.currentTimeMillis();
        queuedData.values().removeIf(data -> now - data.getCreationTime() > QueuedData.EXPIRE_MILLIS);
    }

    /**
     * Returns {@code true} if both proxy UUIDs represent the same proxy. If any of them is {@code null}
     * (unknown sender), {@code true} is returned.
     *
     * @param   proxy1
     *          First proxy UUID
     * @param   proxy2
     *          Second proxy UUID
     * @return  {@code true} if proxies are the same or one of them is unknown, {@code false} otherwise
     */
    public static boolean isSameProxy(@Nullable String proxy1, @Nullable String proxy2) {
        return proxy1 == null || proxy2 == null || proxy1.equals(proxy2);
    }
}