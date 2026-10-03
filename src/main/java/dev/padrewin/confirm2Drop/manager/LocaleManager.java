package dev.padrewin.confirm2Drop.manager;

import dev.padrewin.colddev.ColdPlugin;
import dev.padrewin.colddev.manager.AbstractLocaleManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.minecart.CommandMinecart;

public class LocaleManager extends AbstractLocaleManager {

    public LocaleManager(ColdPlugin coldPlugin) {
        super(coldPlugin); // Calls super constructor first
    }

    @Override
    protected void handleMessage(CommandSender sender, String message) {
        // Command blocks and minecarts must be messaged from their own region thread on Folia
        if (sender instanceof BlockCommandSender && !Bukkit.isPrimaryThread()) {
            Location location = ((BlockCommandSender) sender).getBlock().getLocation();
            this.coldPlugin.getScheduler().runTaskAtLocation(location, () -> super.handleMessage(sender, message));
        } else if (sender instanceof CommandMinecart && !Bukkit.isPrimaryThread()) {
            this.coldPlugin.getScheduler().runTaskAtEntity((CommandMinecart) sender, () -> super.handleMessage(sender, message));
        } else {
            super.handleMessage(sender, message);
        }
    }

}
