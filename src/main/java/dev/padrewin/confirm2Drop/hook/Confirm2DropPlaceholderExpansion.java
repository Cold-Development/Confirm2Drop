package dev.padrewin.confirm2Drop.hook;

import dev.padrewin.confirm2Drop.Confirm2Drop;
import dev.padrewin.confirm2Drop.manager.LocaleManager;
import org.bukkit.OfflinePlayer;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class Confirm2DropPlaceholderExpansion extends PlaceholderExpansion {

    private final Confirm2Drop confirm2Drop;
    private final LocaleManager localeManager;

    public Confirm2DropPlaceholderExpansion(Confirm2Drop confirm2Drop) {
        this.confirm2Drop = confirm2Drop;
        this.localeManager = this.confirm2Drop.getManager(LocaleManager.class);
    }

    @Override
    public String onRequest(OfflinePlayer player, String placeholder) {
        if (player != null && placeholder.equalsIgnoreCase("toggle_status")) {
            boolean isEnabled = this.confirm2Drop.getDatabaseManager().getPlayerPreference(player.getUniqueId().toString());
            return isEnabled
                    ? localeManager.getLocaleMessage("placeholder-status-enabled")
                    : localeManager.getLocaleMessage("placeholder-status-disabled");
        }
        return null;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String getIdentifier() {
        return "confirm2drop";
    }

    @Override
    public String getAuthor() {
        return this.confirm2Drop.getDescription().getAuthors().get(0);
    }

    @Override
    public String getVersion() {
        return this.confirm2Drop.getDescription().getVersion();
    }
}
