package dev.padrewin.confirm2Drop.setting;

import dev.padrewin.colddev.config.ColdSetting;
import dev.padrewin.colddev.config.ColdSettingSerializer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.padrewin.confirm2Drop.Confirm2Drop;

import static dev.padrewin.colddev.config.ColdSettingSerializers.*;

public class SettingKey {

    private static final List<ColdSetting<?>> KEYS = new ArrayList<>();

    public static final ColdSetting<String> BASE_COMMAND_REDIRECT = create("base-command-redirect", STRING, "",
            "Which command should we redirect to when using '/confirm2drop' with no subcommand specified?",
            "You can use a value here such as 'version' to show the output of '/confirm2drop version'",
            "If you have any aliases defined, do not use them here",
            "If left as blank, the default behavior of showing '/confirm2drop version' with bypassed permissions will be used",
            "");

    // Global toggle setting
    public static final ColdSetting<Boolean> GLOBAL_TOGGLE = create("confirm2drop", BOOLEAN, true,
            "Enable or disable the Confirm2Drop feature globally.",
            "If set to false, no confirmation will be required for dropping items,",
            "and the plugin will ignore all player-specific settings.",
            "No changes to the toggle preference can be made while the feature is disabled.");

    // Blacklist settings
    public static final ColdSetting<Boolean> TOOLS = create("blacklist.tools", BOOLEAN, true,
            "Enable or disable confirmation for all tools.",
            "If set to true, players will need to confirm dropping tools.");

    public static final ColdSetting<Boolean> ARMOR = create("blacklist.armor", BOOLEAN, true,
            "Enable or disable confirmation for all armor items.",
            "If set to true, players will need to confirm dropping armor.");

    public static final ColdSetting<Boolean> SPAWN_EGGS = create("blacklist.spawn-eggs", BOOLEAN, true,
            "Enable or disable confirmation for spawn eggs.",
            "If set to true, players will need to confirm dropping spawn eggs.");

    public static final ColdSetting<Boolean> ENCHANTED_ITEMS = create("blacklist.enchanted-items", BOOLEAN, true,
            "Enable or disable confirmation for enchanted items.",
            "If set to true, players will need to confirm dropping items with enchantments.");

    // Confirmation timeout
    public static final ColdSetting<Integer> CONFIRMATION_TIMEOUT = create("confirmation-timeout", INTEGER, 5,
            "Time in seconds before the confirmation request expires.",
            "If the player does not confirm the drop within this time, the request will be canceled.");

    // Debug setting
    public static final ColdSetting<Boolean> DEBUG = create("debug", BOOLEAN, false,
            "Enable or disable debug logging for the plugin.",
            "If set to true, debug messages will be shown in the console.");

    // Utility methods
    private static <T> ColdSetting<T> create(String key, ColdSettingSerializer<T> serializer, T defaultValue, String... comments) {
        ColdSetting<T> setting = ColdSetting.backed(Confirm2Drop.getInstance(), key, serializer, defaultValue, comments);
        KEYS.add(setting);
        return setting;
    }

    public static List<ColdSetting<?>> getKeys() {
        return Collections.unmodifiableList(KEYS);
    }

    private SettingKey() {}
}
