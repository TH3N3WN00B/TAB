package me.neznamy.tab.platforms.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import me.neznamy.tab.shared.ProjectVariables;
import me.neznamy.tab.shared.TAB;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main class for Forge TAB implementation.
 */
@OnlyIn(Dist.DEDICATED_SERVER)
@Mod(ProjectVariables.PLUGIN_ID)
public class ForgeTAB {

	/** Command dispatcher instance for later command registration. */
	public static CommandDispatcher<CommandSourceStack> COMMAND_DISPATCHER;

	/** Registered commands, which need to be added again when a datapack reload creates a new command dispatcher */
	private static final Map<String, LiteralCommandNode<CommandSourceStack>> COMMANDS = new LinkedHashMap<>();

	/**
	 * Constructs new instance and registers necessary events.
	 */
	public ForgeTAB() {
		RegisterCommandsEvent.BUS.addListener(event -> setCommandDispatcher(event.getDispatcher()));
		ServerStartingEvent.BUS.addListener(event -> TAB.create(new ForgePlatform(event.getServer())));
		ServerStoppingEvent.BUS.addListener(event -> TAB.getInstance().unload());
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
