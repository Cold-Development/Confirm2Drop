package dev.padrewin.confirm2Drop.listeners;

import dev.padrewin.confirm2Drop.Confirm2Drop;
import dev.padrewin.confirm2Drop.manager.LocaleManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DropListener implements Listener {

    private final Confirm2Drop plugin;

    private final Map<UUID, ItemStack> pendingConfirmation = new HashMap<>();
    private final Map<UUID, Long> confirmationTimeouts = new HashMap<>();

    public DropListener(Confirm2Drop plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        if (!plugin.getConfig().getBoolean("confirm2drop", true)) {
            debug("Confirm2Drop is globally disabled. Ignoring drop event.");
            return;
        }

        if (player.getGameMode() == GameMode.CREATIVE) {
            debug("Player " + player.getName() + " is in Creative mode. Ignoring drop event.");
            return;
        }

        boolean isToggleDisabled = !plugin.getDatabaseManager().getPlayerPreference(playerUUID.toString());
        if (isToggleDisabled) {
            debug("Player " + player.getName() + " has disabled Confirm2Drop for themselves. Ignoring drop event.");
            return;
        }

        ItemStack item = event.getItemDrop().getItemStack();
        debug("Player " + player.getName() + " is trying to drop item: " + item.getType() + " x" + item.getAmount());

        if (pendingConfirmation.containsKey(playerUUID)) {
            ItemStack pendingItem = pendingConfirmation.get(playerUUID);

            long currentTime = System.currentTimeMillis();
            long timeoutEnd = confirmationTimeouts.getOrDefault(playerUUID, 0L);

            if (areItemsEqual(pendingItem, item) && currentTime < timeoutEnd) {
                debug("Player " + player.getName() + " confirmed the drop for item: " + item.getType());
                pendingConfirmation.remove(playerUUID);
                confirmationTimeouts.remove(playerUUID);
                return;
            } else if (!areItemsEqual(pendingItem, item)) {
                debug("Player " + player.getName() + " attempted to drop a different item. Resetting pending confirmation.");
                pendingConfirmation.remove(playerUUID);
                confirmationTimeouts.remove(playerUUID);
            }
        }

        if (!shouldRequireConfirmation(item)) {
            debug("No confirmation required for item: " + item.getType());
            return;
        }

        debug("Confirmation required for item: " + item.getType());
        event.setCancelled(true);
        requestConfirmation(player, item);
    }

    private void requestConfirmation(Player player, ItemStack item) {
        UUID playerUUID = player.getUniqueId();
        pendingConfirmation.put(playerUUID, item.clone());

        int timeoutSeconds = plugin.getConfig().getInt("confirmation-timeout", 10);
        long timeoutEnd = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        confirmationTimeouts.put(playerUUID, timeoutEnd);

        debug("Confirmation request sent to player " + player.getName() + " for item: " + item.getType() + ". Timeout: " + timeoutSeconds + " seconds.");
        plugin.getManager(LocaleManager.class).sendMessage(player, "drop-confirmation-message");
    }

    private boolean shouldRequireConfirmation(ItemStack item) {
        boolean toolsBlacklist = plugin.getConfig().getBoolean("blacklist.tools", true);
        boolean armorBlacklist = plugin.getConfig().getBoolean("blacklist.armor", true);
        boolean spawnEggsBlacklist = plugin.getConfig().getBoolean("blacklist.spawn-eggs", true);
        boolean enchantedItemsBlacklist = plugin.getConfig().getBoolean("blacklist.enchanted-items", true);

        if (toolsBlacklist && isTool(item.getType())) {
            debug("Item " + item.getType() + " is a tool and requires confirmation.");
            return true;
        }
        if (armorBlacklist && isArmor(item.getType())) {
            debug("Item " + item.getType() + " is armor and requires confirmation.");
            return true;
        }
        if (spawnEggsBlacklist && item.getType().toString().endsWith("_SPAWN_EGG")) {
            debug("Item " + item.getType() + " is a spawn egg and requires confirmation.");
            return true;
        }
        if (enchantedItemsBlacklist && item.getEnchantments().size() > 0) {
            debug("Item " + item.getType() + " is enchanted and requires confirmation.");
            return true;
        }

        List<String> otherItems = plugin.getConfig().getStringList("blacklist.others");
        if (otherItems.contains(item.getType().toString().toLowerCase())) {
            debug("Item " + item.getType() + " is in the custom blacklist and requires confirmation.");
            return true;
        }

        return false;
    }

    private boolean isTool(Material material) {
        return material.toString().endsWith("_AXE") || material.toString().endsWith("_PICKAXE")
                || material.toString().endsWith("_SHOVEL") || material.toString().endsWith("_HOE")
                || material.toString().endsWith("_SWORD");
    }

    private boolean isArmor(Material material) {
        return material.toString().endsWith("_HELMET") || material.toString().endsWith("_CHESTPLATE")
                || material.toString().endsWith("_LEGGINGS") || material.toString().endsWith("_BOOTS");
    }

    private boolean areItemsEqual(ItemStack item1, ItemStack item2) {
        return item1.isSimilar(item2) && item1.getAmount() == item2.getAmount();
    }

    public void resetPendingConfirmation(Player player) {
        UUID playerUUID = player.getUniqueId();
        pendingConfirmation.remove(playerUUID);
        confirmationTimeouts.remove(playerUUID);
        debug("Pending confirmation reset for player " + player.getName());
    }

    private void debug(String message) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            Bukkit.getLogger().info("[Confirm2Drop DEBUG] " + message);
        }
    }
}
