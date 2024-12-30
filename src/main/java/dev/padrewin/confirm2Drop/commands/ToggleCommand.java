package dev.padrewin.confirm2Drop.commands;

import dev.padrewin.confirm2Drop.Confirm2Drop;
import dev.padrewin.confirm2Drop.listeners.DropListener;
import dev.padrewin.confirm2Drop.manager.CommandManager;
import dev.padrewin.confirm2Drop.manager.LocaleManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class ToggleCommand extends BaseCommand {

    public ToggleCommand() {
        super("toggle", CommandManager.CommandAliases.TOGGLE);
    }

    @Override
    public void execute(Confirm2Drop plugin, CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            plugin.getManager(LocaleManager.class).sendMessage(sender, "player-only-command");
            return;
        }

        Player player = (Player) sender;
        LocaleManager localeManager = plugin.getManager(LocaleManager.class);
        String uuid = player.getUniqueId().toString();
        String playerName = player.getName();

        if (!plugin.getConfig().getBoolean("confirm2drop", true)) {
            localeManager.sendMessage(player, "plugin-disabled-message");
            return;
        }

        boolean toggleWarning = plugin.getConfig().getBoolean("toggle-warning", true);
        if (toggleWarning && args.length == 0) {
            localeManager.sendMessage(player, "command-toggle-warning");
            return;
        }

        if (toggleWarning && (!args[0].equalsIgnoreCase("confirm") || args.length > 1)) {
            localeManager.sendMessage(player, "command-toggle-usage");
            return;
        }

        boolean currentPreference = plugin.getDatabaseManager().getPlayerPreference(uuid);
        boolean newPreference = !currentPreference;
        plugin.getDatabaseManager().savePlayerPreference(uuid, playerName, newPreference);

        DropListener dropListener = plugin.getDropListener();
        dropListener.resetPendingConfirmation(player);

        String messageKey = newPreference ? "command-toggle-enabled" : "command-toggle-disabled";
        localeManager.sendMessage(player, messageKey);
    }


    @Override
    public List<String> tabComplete(Confirm2Drop plugin, CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
