package me.neznamy.tab.shared.features.proxy.message;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import lombok.Getter;
import lombok.Setter;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.cpu.ThreadExecutor;
import me.neznamy.tab.shared.features.proxy.ProxySupport;
import me.neznamy.tab.shared.platform.TabList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public abstract class ProxyMessage {

    /**
     * UUID of the proxy which sent this message, taken from the message envelope (it is not part of
     * the message body). {@code null} for messages created by this proxy.
     */
    @Getter
    @Setter
    @Nullable
    private String sourceProxy;

    @Nullable
    public ThreadExecutor getCustomThread() {
        return null;
    }

    public void writeUUID(@NotNull ByteArrayDataOutput out, @NotNull UUID id) {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    public UUID readUUID(@NotNull ByteArrayDataInput in) {
        return new UUID(in.readLong(), in.readLong());
    }

    public void writeSkin(@NotNull ByteArrayDataOutput out, @Nullable TabList.Skin skin) {
        out.writeBoolean(skin != null);
        if (skin != null) {
            out.writeUTF(skin.getValue());
            out.writeBoolean(skin.getSignature() != null);
            if (skin.getSignature() != null) {
                out.writeUTF(skin.getSignature());
            }
        }
    }

    @Nullable
    public TabList.Skin readSkin(@NotNull ByteArrayDataInput in) {
        if (!in.readBoolean()) return null;
        String value = in.readUTF();
        String signature = null;
        if (in.readBoolean()) {
            signature = in.readUTF();
        }
        return new TabList.Skin(value, signature);
    }

    public abstract void write(@NotNull ByteArrayDataOutput out);

    public abstract void process(@NotNull ProxySupport proxySupport);

    public void unknownPlayer(@NotNull String playerId, @NotNull String action) {
        TAB.getInstance().debug("[Proxy Support] Unable to process " + action + " of proxy player " + playerId + ", because no such player exists. Queueing data.");
    }

    /**
     * Returns {@code true} if this message was sent by the same proxy as the other message.
     * Message IDs are only comparable between messages from the same proxy, because every
     * proxy (and every restart of a proxy) has its own ID counter.
     *
     * @param   other
     *          Other message to compare source proxy with
     * @return  {@code true} if both messages come from the same proxy, {@code false} if not
     */
    public boolean isFromSameProxy(@NotNull ProxyMessage other) {
        return ProxySupport.isSameProxy(sourceProxy, other.sourceProxy);
    }
}
