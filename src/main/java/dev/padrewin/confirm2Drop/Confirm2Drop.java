package dev.padrewin.confirm2Drop;

import dev.padrewin.colddev.ColdPlugin;
import dev.padrewin.colddev.config.ColdSetting;
import dev.padrewin.colddev.database.DatabaseConnector;
import dev.padrewin.colddev.database.SQLiteConnector;
import dev.padrewin.colddev.manager.Manager;
import dev.padrewin.colddev.manager.PluginUpdateManager;
import dev.padrewin.confirm2Drop.database.DatabaseManager;
import dev.padrewin.confirm2Drop.manager.CommandManager;
import dev.padrewin.confirm2Drop.manager.LocaleManager;
import dev.padrewin.confirm2Drop.setting.SettingKey;
import dev.padrewin.confirm2Drop.listeners.DropListener;

import org.bukkit.Bukkit;

import java.io.File;
import java.util.List;

public final class Confirm2Drop extends ColdPlugin {

    /**
     * Console colors
     */
    String ANSI_RESET = "\u001B[0m";
    String ANSI_CHINESE_PURPLE = "\u001B[38;5;93m";
    String ANSI_PURPLE = "\u001B[35m";
    String ANSI_GREEN = "\u001B[32m";
    String ANSI_RED = "\u001B[31m";
    String ANSI_AQUA = "\u001B[36m";
    String ANSI_PINK = "\u001B[35m";
    String ANSI_YELLOW = "\u001B[33m";

    private static Confirm2Drop instance;
    private DatabaseManager databaseManager;
    private DropListener dropListener;

    public Confirm2Drop() {
        super("Cold-Development", "Confirm2Drop", 24248, null, LocaleManager.class, null);
        instance = this;
    }

    @Override
    public void enable() {
        instance = this;

        dropListener = new DropListener(this);
        Bukkit.getPluginManager().registerEvents(dropListener, this);

        // Initialize DatabaseManager
        databaseManager = new DatabaseManager(this, "confirm2drop.db");
        DatabaseConnector connector = new SQLiteConnector(this);
        String databasePath = connector.getDatabasePath();
        getLogger().info(ANSI_GREEN + "Database path: " + ANSI_YELLOW + databasePath + ANSI_RESET);

        getManager(PluginUpdateManager.class);

        String name = getDescription().getName();
        getLogger().info("");
        getLogger().info(ANSI_CHINESE_PURPLE + "  ____ ___  _     ____  " + ANSI_RESET);
        getLogger().info(ANSI_PINK + " / ___/ _ \\| |   |  _ \\ " + ANSI_RESET);
        getLogger().info(ANSI_CHINESE_PURPLE + "| |  | | | | |   | | | |" + ANSI_RESET);
        getLogger().info(ANSI_PINK + "| |__| |_| | |___| |_| |" + ANSI_RESET);
        getLogger().info(ANSI_CHINESE_PURPLE + " \\____\\___/|_____|____/ " + ANSI_RESET);
        getLogger().info("    " + ANSI_GREEN + name + ANSI_RED + " v" + getDescription().getVersion() + ANSI_RESET);
        getLogger().info(ANSI_PURPLE + "    Author(s): " + ANSI_PURPLE + getDescription().getAuthors().get(0) + ANSI_RESET);
        getLogger().info(ANSI_AQUA + "    (c) Cold Development ❄" + ANSI_RESET);
        getLogger().info("");

        File configFile = new File(getDataFolder(), "en_US.yml");
        if (!configFile.exists()) {
            saveDefaultConfig();
        }

        saveDefaultConfig();

        Bukkit.getPluginManager().registerEvents(new DropListener(this), this);
    }

    @Override
    public void disable() {
        if (databaseManager != null) {
            databaseManager.closeConnection();
        }
        getLogger().info("Confirm2Drop unloaded.");
    }

    @Override
    protected List<Class<? extends Manager>> getManagerLoadPriority() {
        return List.of(
                CommandManager.class
        );
    }

    @Override
    protected List<ColdSetting<?>> getColdConfigSettings() {
        return SettingKey.getKeys();
    }

    @Override
    protected String[] getColdConfigHeader() {
        return new String[] {
                "  ____  ___   _      ____   ",
                " / ___|/ _ \\ | |    |  _ \\  ",
                "| |   | | | || |    | | | | ",
                "| |___| |_| || |___ | |_| | ",
                " \\____|\\___/ |_____|_____/  ",
                "                           "
        };
    }

    public static Confirm2Drop getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Confirm2Drop instance is not initialized!");
        }
        return instance;
    }

    public DropListener getDropListener() {
        return dropListener;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}
