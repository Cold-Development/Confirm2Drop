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
            plugin.getManager(LocaleManager.class).sendMessage(sender, "command-player-only");
            return;
        }

        Player player = (Player) sender;
        String uuid = player.getUniqueId().toString();
        String playerName = player.getName();

        // Obține preferința curentă
        boolean currentPreference = plugin.getDatabaseManager().getPlayerPreference(uuid);

        // Inversăm preferința
        boolean newPreference = !currentPreference;
        plugin.getDatabaseManager().savePlayerPreference(uuid, playerName, newPreference);

        // Resetăm confirmările
        DropListener dropListener = plugin.getDropListener();
        dropListener.resetPendingConfirmation(player);

        // Trimiterea mesajului pe baza noii setări
        String messageKey = newPreference ? "command-toggle-enabled" : "command-toggle-disabled";
        plugin.getManager(LocaleManager.class).sendMessage(player, messageKey);
    }

    @Override
    public List<String> tabComplete(Confirm2Drop plugin, CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
