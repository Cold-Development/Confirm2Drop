package dev.padrewin.confirm2Drop.commands;

import java.util.Collections;
import java.util.List;
import dev.padrewin.confirm2Drop.Confirm2Drop;
import dev.padrewin.confirm2Drop.manager.CommandManager;
import dev.padrewin.confirm2Drop.manager.LocaleManager;
import org.bukkit.command.CommandSender;

public class ReloadCommand extends BaseCommand {

    public ReloadCommand() {
        super("reload", CommandManager.CommandAliases.RELOAD);
    }

    @Override
    public void execute(Confirm2Drop plugin, CommandSender sender, String[] args) {
        if (!sender.hasPermission("confirm2drop.reload")) {
            plugin.getManager(LocaleManager.class).sendMessage(sender, "no-permission");
            return;
        }

        if (args.length > 0) {
            plugin.getManager(LocaleManager.class).sendMessage(sender, "command-reload-usage");
            return;
        }

        plugin.reloadConfig();
        plugin.reload();
        plugin.getManager(LocaleManager.class).sendMessage(sender, "command-reload-success");
    }

    @Override
    public List<String> tabComplete(Confirm2Drop plugin, CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
