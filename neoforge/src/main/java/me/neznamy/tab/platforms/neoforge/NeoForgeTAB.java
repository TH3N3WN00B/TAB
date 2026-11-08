package me.neznamy.tab.platforms.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import me.neznamy.tab.shared.TAB;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main class for NeoForge TAB implementation.
 */
@Mod(value = "tab", dist = Dist.DEDICATED_SERVER)
public class NeoForgeTAB {

	/** Command dispatcher instance for later command registration. */
	public static CommandDispatcher<CommandSourceStack> COMMAND_DISPATCHER;

	/** Registered commands, which need to be added again when a datapack reload creates a new command dispatcher */
	private static final Map<String, LiteralCommandNode<CommandSourceStack>> COMMANDS = new LinkedHashMap<>();

	/**
	 * Constructs new instance and registers necessary events.
	 */
	public NeoForgeTAB() {
		IEventBus EVENT_BUS = NeoForge.EVENT_BUS;
		EVENT_BUS.addListener((RegisterCommandsEvent event) -> setCommandDispatcher(event.getDispatcher()));
		EVENT_BUS.addListener((ServerStartingEvent event) -> TAB.create(new NeoForgePlatform(event.getServer())));
		EVENT_BUS.addListener((ServerStoppingEvent event) -> TAB.getInstance().unload());
	}

	/**
	 * Saves new command dispatcher and adds all previously registered commands into it,
	 * because a datapack reload creates a new dispatcher without them.
	 *
	 * @param   dispatcher
	 *          New command dispatcher
	 */
	private static synchronized void setCommandDispatcher(@NotNull CommandDispatcher<CommandSourceStack> dispatcher) {
		if (dispatcher == COMMAND_DISPATCHER) return; // Commands are already registered in this dispatcher
		COMMAND_DISPATCHER = dispatcher;
		for (LiteralCommandNode<CommandSourceStack> command : COMMANDS.values()) {
			dispatcher.getRoot().addChild(command);
		}
	}

	/**
	 * Registers command into the current command dispatcher and remembers it
	 * to register it again into new dispatcher on datapack reload.
	 *
	 * @param   command
	 *          Command to register
	 */
	public static synchronized void registerCommand(@NotNull LiteralCommandNode<CommandSourceStack> command) {
		COMMANDS.put(command.getName(), command);
		COMMAND_DISPATCHER.getRoot().addChild(command);
	}

	/**
	 * Gets level name with dimension suffix to match Bukkit's behavior.
	 *
	 * @param   level
	 *          Level to get name of
	 * @return  Level name with dimension suffix
	 */
	@NotNull
	public static String getLevelName(@NotNull Level level) {
		String path = level.dimension().identifier().getPath();
		return ((ServerLevelData)level.getLevelData()).getLevelName() + switch (path) {
			case "overworld" -> ""; // No suffix for overworld
			case "the_nether" -> "_nether";
			default -> "_" + path; // End + default behavior for other dimensions created by mods
		};
	}
}
