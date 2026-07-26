package dev.padrewin.confirm2Drop.listeners;

import dev.padrewin.confirm2Drop.Confirm2Drop;
import dev.padrewin.confirm2Drop.manager.LocaleManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DropListener implements Listener {

    /**
     * Sentinel for a drop whose source slot can't be determined (e.g. dropping
     * an item that was already sitting on the cursor from an earlier pickup).
     * Never matches a pending slot, so such drops can never auto-confirm.
     */
    private static final int UNKNOWN_SLOT = -1;

    private final Confirm2Drop plugin;

    private final Map<UUID, PendingDrop> pendingConfirmation = new HashMap<>();

    /**
     * Slot captured from the InventoryClickEvent that triggers a drop while the
     * player's inventory screen is open (armor/off-hand/main inventory all go
     * through here); consumed by the matching PlayerDropItemEvent right after.
     */
    private final Map<UUID, Integer> pendingClickSlot = new HashMap<>();

    public DropListener(Confirm2Drop plugin) {
        this.plugin = plugin;
    }

    private static final class PendingDrop {
        final int slot;
        final ItemStack item;
        final long expiry;

        PendingDrop(int slot, ItemStack item, long expiry) {
            this.slot = slot;
            this.item = item;
            this.expiry = expiry;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Always drain this, even on an early return below, so a slot recorded for
        // one drop attempt can never be misattributed to an unrelated later one.
        Integer clickSlot = pendingClickSlot.remove(playerUUID);

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

        int sourceSlot = clickSlot != null ? clickSlot : player.getInventory().getHeldItemSlot();

        if (pendingConfirmation.containsKey(playerUUID)) {
            PendingDrop pending = pendingConfirmation.get(playerUUID);

            long currentTime = System.currentTimeMillis();
            boolean sameSlot = sourceSlot != UNKNOWN_SLOT && sourceSlot == pending.slot;
            boolean sameItem = areItemsEqual(pending.item, item);

            if (sameSlot && sameItem && currentTime < pending.expiry) {
                pendingConfirmation.remove(playerUUID);

                if (isInventoryFull(player)) {
                    dropItemToGround(player, item);
                    event.getItemDrop().remove();
                } else {
                    debug("Player " + player.getName() + " confirmed the drop for item: " + item.getType());
                }
                return;
            } else if (!sameItem || !sameSlot) {
                debug("Player " + player.getName() + " attempted to drop a different item/slot. Resetting pending confirmation.");
                pendingConfirmation.remove(playerUUID);
            }
        }

        if (!shouldRequireConfirmation(item)) {
            debug("No confirmation required for item: " + item.getType());
            return;
        }

        debug("Confirmation required for item: " + item.getType());
        cancelAndRestore(event, player, item);
        requestConfirmation(player, item, sourceSlot);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        InventoryAction action = event.getAction();
        UUID playerUUID = player.getUniqueId();

        if (action == InventoryAction.DROP_ONE_SLOT || action == InventoryAction.DROP_ALL_SLOT) {
            if (event.getClickedInventory() instanceof PlayerInventory) {
                pendingClickSlot.put(playerUUID, event.getSlot());
            }
        } else if (action == InventoryAction.DROP_ONE_CURSOR || action == InventoryAction.DROP_ALL_CURSOR) {
            pendingClickSlot.put(playerUUID, UNKNOWN_SLOT);
        }
    }

    /**
     * Cancels the drop and restores the item ourselves instead of trusting the
     * server's implicit cancel-restore. That implicit restore is what let a
     * pending item survive a death that happened in the same window: the item
     * could still be "in flight" (removed from the inventory, not yet given
     * back) when death drops were calculated, so it never dropped like the
     * rest of the inventory and reappeared after respawn instead.
     */
    private void cancelAndRestore(PlayerDropItemEvent event, Player player, ItemStack item) {
        event.setCancelled(true);
        event.getItemDrop().remove();
        player.getInventory().addItem(item.clone());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        resetPendingConfirmation(event.getEntity());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        resetPendingConfirmation(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        resetPendingConfirmation(event.getPlayer());
    }


    private boolean isInventoryFull(Player player) {
        return player.getInventory().firstEmpty() == -1;
    }

    private void dropItemToGround(Player player, ItemStack item) {
        player.getWorld().dropItemNaturally(player.getLocation(), item);
        debug("Player " + player.getName() + "'s inventory is full. Dropped item " + item.getType() + " to the ground.");
        plugin.getManager(LocaleManager.class).sendMessage(player, "inventory-full-drop-message");
    }

    private void requestConfirmation(Player player, ItemStack item, int sourceSlot) {
        UUID playerUUID = player.getUniqueId();

        int timeoutSeconds = plugin.getConfig().getInt("confirmation-timeout", 10);
        long timeoutEnd = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        pendingConfirmation.put(playerUUID, new PendingDrop(sourceSlot, item.clone(), timeoutEnd));

        debug("Confirmation request sent to player " + player.getName() + " for item: " + item.getType() + " in slot " + sourceSlot + ". Timeout: " + timeoutSeconds + " seconds.");
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
        pendingClickSlot.remove(playerUUID);
        debug("Pending confirmation reset for player " + player.getName());
    }

    private void debug(String message) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            Bukkit.getLogger().info("[Confirm2Drop DEBUG] " + message);
        }
    }
}
