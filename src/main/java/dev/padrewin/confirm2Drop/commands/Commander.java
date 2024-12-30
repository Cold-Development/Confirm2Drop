package dev.padrewin.confirm2Drop.commands;

import dev.padrewin.colddev.utils.StringPlaceholders;
import dev.padrewin.confirm2Drop.Confirm2Drop;
import dev.padrewin.confirm2Drop.manager.CommandManager;
import dev.padrewin.confirm2Drop.manager.LocaleManager;
import dev.padrewin.confirm2Drop.setting.SettingKey;
import org.bukkit.command.CommandSender;

/**
 * Handles the commands for the root command.
 *
 * @author padrewin
 */
public class Commander extends CommandHandler {

    public Commander(Confirm2Drop plugin) {
        super(plugin, "confirm2drop", CommandManager.CommandAliases.ROOT);

        // Register commands.
        this.registerCommand(new HelpCommand(this));
        this.registerCommand(new ReloadCommand());
        this.registerCommand(new VersionCommand());
        this.registerCommand(new ToggleCommand());

    }

    @Override
    public void noArgs(CommandSender sender) {
        try {
            String redirect = SettingKey.BASE_COMMAND_REDIRECT.get().trim().toLowerCase();
            BaseCommand command = this.registeredCommands.get(redirect);
            CommandHandler handler = this.registeredHandlers.get(redirect);
            if (command != null) {
                if (!command.hasPermission(sender)) {
                    this.plugin.getManager(LocaleManager.class).sendMessage(sender, "no-permission");
                    return;
                }
                command.execute(this.plugin, sender, new String[0]);
            } else if (handler != null) {
                if (!handler.hasPermission(sender)) {
                    this.plugin.getManager(LocaleManager.class).sendMessage(sender, "no-permission");
                    return;
                }
                handler.noArgs(sender);
            } else {
                VersionCommand.sendInfo(this.plugin, sender);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void unknownCommand(CommandSender sender, String[] args) {
        this.plugin.getManager(LocaleManager.class).sendMessage(sender, "unknown-command", StringPlaceholders.of("input", args[0]));
    }

}