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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class DropListener implements Listener {

    /**
     * Sentinel for a cursor drop whose origin inventory/slot can't be traced (e.g.
     * the item was already on the cursor before any pickup we saw). Matches only
     * another untraceable drop, falling back to item identity alone - see the
     * bothUnknown check in {@link #handleGuiDrop}.
     */
    private static final int UNKNOWN_SLOT = -1;

    private final Confirm2Drop plugin;

    /**
     * Pending confirmations for the one drop path that has no InventoryClickEvent
     * to intercept: pressing Q on the hotbar while no inventory screen is open.
     * Matched by held-item slot, since that's the only source info available.
     * Cancelling the resulting PlayerDropItemEvent is safe here because the slot
     * the item just left is always free again, so the server's automatic
     * restore-to-inventory-on-cancel can never fail for lack of space.
     */
    private final Map<UUID, PendingHotbarDrop> pendingHotbarConfirmation = new HashMap<>();

    /**
     * Pending confirmations for every drop that goes through an open inventory
     * screen - Q on a slot, or dragging the cursor out of the window - whether the
     * item's source is the player's own inventory or a foreign one (chest, ender
     * chest, backpack, ...). These are intercepted and cancelled directly at the
     * InventoryClickEvent stage, before the item ever leaves its slot, rather than
     * relying on the PlayerDropItemEvent auto-restore: that restore always puts
     * the item back into the player's OWN inventory regardless of where it came
     * from, which silently loses the item if that inventory happens to be full at
     * the time (see {@link #handleGuiDrop}).
     */
    private final Map<UUID, PendingGuiDrop> pendingGuiConfirmation = new HashMap<>();

    /**
     * Inventory + slot that the item currently on the player's cursor was picked
     * up from. Lets a cursor drop (DROP_*_CURSOR, which has no clicked slot of its
     * own) be matched to a pending confirmation by origin, the same way Q-drops
     * already are. Cleared whenever the cursor's contents stop being traceable to
     * a single origin slot (placing back, multi-slot collect, dragging across
     * slots), on inventory close, or once a drop is actually confirmed.
     */
    private final Map<UUID, CursorOrigin> cursorOrigin = new HashMap<>();

    /**
     * Marks a player whose GUI drop was just confirmed and allowed to proceed
     * unmodified. Consumed by the very next PlayerDropItemEvent so it isn't
     * re-evaluated (and potentially re-cancelled) by {@link #onItemDrop}.
     */
    private final Set<UUID> bypassNextDrop = new HashSet<>();

    public DropListener(Confirm2Drop plugin) {
        this.plugin = plugin;
    }

    private static final class PendingHotbarDrop {
        final int slot;
        final ItemStack item;
        final long expiry;

        PendingHotbarDrop(int slot, ItemStack item, long expiry) {
            this.slot = slot;
            this.item = item;
            this.expiry = expiry;
        }
    }

    private record CursorOrigin(Inventory inventory, int slot) {
    }

    private static final class PendingGuiDrop {
        final Inventory inventory;
        final int slot;
        final ItemStack item;
        final long expiry;

        PendingGuiDrop(Inventory inventory, int slot, ItemStack item, long expiry) {
            this.inventory = inventory;
            this.slot = slot;
            this.item = item;
            this.expiry = expiry;
        }
    }

    /**
     * Handles ONLY the hotbar-Q-with-no-inventory-screen-open case; every drop
     * that goes through an open inventory screen is intercepted earlier, in
     * {@link #onInventoryClick}, and never reaches this handler as something
     * still needing a confirmation decision (see {@link #bypassNextDrop}).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        if (bypassNextDrop.remove(playerUUID)) {
            debug("Player " + player.getName() + "'s already-confirmed GUI drop is proceeding.");
            return;
        }

        if (!isConfirmationEligible(player)) {
            return;
        }

        ItemStack item = event.getItemDrop().getItemStack();
        debug("Player " + player.getName() + " is trying to drop item: " + item.getType() + " x" + item.getAmount());

        int sourceSlot = player.getInventory().getHeldItemSlot();

        PendingHotbarDrop pending = pendingHotbarConfirmation.get(playerUUID);
        if (pending != null) {
            long currentTime = System.currentTimeMillis();
            boolean sameSlot = sourceSlot == pending.slot;
            boolean sameItem = areItemsEqual(pending.item, item);

            if (sameSlot && sameItem && currentTime < pending.expiry) {
                pendingHotbarConfirmation.remove(playerUUID);

                if (isInventoryFull(player)) {
                    dropItemToGround(player, item);
                    event.getItemDrop().remove();
                } else {
                    debug("Player " + player.getName() + " confirmed the drop for item: " + item.getType());
                }
                return;
            } else {
                debug("Player " + player.getName() + " attempted to drop a different item/slot. Resetting pending confirmation.");
                pendingHotbarConfirmation.remove(playerUUID);
            }
        }

        if (!shouldRequireConfirmation(item)) {
            debug("No confirmation required for item: " + item.getType());
            return;
        }

        debug("Confirmation required for item: " + item.getType());
        cancelAndRestore(event);
        requestConfirmation(player, item, sourceSlot);
    }

    /**
     * Priority HIGH (not MONITOR): this handler actively cancels drop-related
     * clicks - see {@link #handleGuiDrop}.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        InventoryAction action = event.getAction();
        UUID playerUUID = player.getUniqueId();
        Inventory clickedInventory = event.getClickedInventory();

        switch (action) {
            case DROP_ONE_SLOT, DROP_ALL_SLOT -> {
                if (clickedInventory != null) {
                    handleGuiDrop(event, player, clickedInventory, event.getSlot(), event.getCurrentItem());
                }
            }
            case DROP_ONE_CURSOR, DROP_ALL_CURSOR -> {
                // Use the inventory/slot the cursor item was picked up from, if we
                // managed to trace it, so this click-drag drop can be matched to a
                // pending confirmation by origin instead of always being unknown.
                CursorOrigin origin = cursorOrigin.get(playerUUID);
                Inventory originInventory = origin != null ? origin.inventory() : null;
                int originSlot = origin != null ? origin.slot() : UNKNOWN_SLOT;
                handleGuiDrop(event, player, originInventory, originSlot, player.getItemOnCursor());
            }
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE, SWAP_WITH_CURSOR -> {
                if (clickedInventory != null) {
                    cursorOrigin.put(playerUUID, new CursorOrigin(clickedInventory, event.getSlot()));
                } else {
                    cursorOrigin.remove(playerUUID);
                }
            }
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, COLLECT_TO_CURSOR ->
                    // Cursor content changed (placed back, or gathered from multiple
                    // slots) - origin is no longer a single traceable slot.
                    cursorOrigin.remove(playerUUID);
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryDrag(InventoryDragEvent event) {
        // Dragging spreads/consumes the cursor across multiple slots outside our
        // tracking; treat the origin as no longer traceable rather than risk a
        // stale slot number being reused by a later cursor drop.
        cursorOrigin.remove(event.getWhoClicked().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID playerUUID = event.getPlayer().getUniqueId();
        // The slot a pending GUI confirmation or cursor origin points at is no
        // longer meaningful once that inventory is closed; drop it rather than
        // risk it being confirmed against a different container instance later.
        pendingGuiConfirmation.remove(playerUUID);
        cursorOrigin.remove(playerUUID);
    }

    /**
     * Handles a drop (Q on a slot, or dragging the cursor out of the window) that
     * goes through an open inventory screen, whether the item's source is the
     * player's own inventory or a foreign one (chest, ender chest, backpack, ...).
     * <p>
     * Cancelling the resulting PlayerDropItemEvent instead - the way the pure
     * hotbar-Q case in {@link #onItemDrop} is handled - is NOT safe here: on
     * cancellation the server restores the item by adding it back to the player's
     * OWN inventory, not to its original slot. For a hotbar drop that's fine,
     * because the slot the item just left is always free again. But for a GUI
     * drop, if the player's own inventory happens to be full, that restore has
     * nowhere to put the item and it is silently lost - even when the item came
     * from a chest slot that has nothing to do with the player's inventory space.
     * Cancelling the click here instead stops the item from ever leaving its
     * source slot in the first place, so there is nothing to restore.
     *
     * @param sourceInventory the inventory the item is being dropped from, or
     *                         {@code null} if its origin could not be traced (see
     *                         {@link #UNKNOWN_SLOT})
     */
    private void handleGuiDrop(InventoryClickEvent event, Player player, Inventory sourceInventory,
                                int sourceSlot, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        UUID playerUUID = player.getUniqueId();

        if (!isConfirmationEligible(player)) {
            return;
        }

        debug("Player " + player.getName() + " is trying to drop item via GUI: " + item.getType() + " x" + item.getAmount());

        PendingGuiDrop pending = pendingGuiConfirmation.get(playerUUID);
        long currentTime = System.currentTimeMillis();

        if (pending != null) {
            // Both unknown means the origin couldn't be traced on either attempt;
            // fall back to matching by item identity alone rather than refusing to
            // ever confirm.
            boolean bothUnknown = sourceInventory == null && pending.inventory == null;
            boolean sameSource = bothUnknown
                    || (sourceInventory != null && sourceInventory == pending.inventory && sourceSlot == pending.slot);

            if (sameSource && areItemsEqual(pending.item, item) && currentTime < pending.expiry) {
                pendingGuiConfirmation.remove(playerUUID);
                cursorOrigin.remove(playerUUID);
                bypassNextDrop.add(playerUUID);
                debug("Player " + player.getName() + " confirmed the GUI drop for item: " + item.getType());
                return;
            }
            debug("Player " + player.getName() + " attempted to drop a different item/slot. Resetting pending confirmation.");
            pendingGuiConfirmation.remove(playerUUID);
        }

        if (!shouldRequireConfirmation(item)) {
            debug("No confirmation required for item: " + item.getType());
            return;
        }

        debug("Confirmation required for item: " + item.getType());
        event.setCancelled(true);

        int timeoutSeconds = plugin.getConfig().getInt("confirmation-timeout", 10);
        long timeoutEnd = currentTime + (timeoutSeconds * 1000L);
        pendingGuiConfirmation.put(playerUUID, new PendingGuiDrop(sourceInventory, sourceSlot, item.clone(), timeoutEnd));

        debug("Confirmation request sent to player " + player.getName() + " for item: " + item.getType()
                + " in GUI slot " + sourceSlot + ". Timeout: " + timeoutSeconds + " seconds.");
        plugin.getManager(LocaleManager.class).sendMessage(player, "drop-confirmation-message");
    }

    /**
     * Cancelling PlayerDropItemEvent is enough on its own: the server already
     * restores the item to the player's inventory and discards the dropped
     * entity as part of handling the cancellation. Manually re-adding the item
     * here as well used to duplicate it (server restore + our own addItem()).
     * Only safe for the hotbar-Q-with-no-screen-open case handled by
     * {@link #onItemDrop} - see {@link #handleGuiDrop} for why every other drop
     * path is handled differently.
     */
    private void cancelAndRestore(PlayerDropItemEvent event) {
        event.setCancelled(true);
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
        pendingHotbarConfirmation.put(playerUUID, new PendingHotbarDrop(sourceSlot, item.clone(), timeoutEnd));

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

    private boolean isConfirmationEligible(Player player) {
        if (!plugin.getConfig().getBoolean("confirm2drop", true)) {
            debug("Confirm2Drop is globally disabled. Ignoring drop event.");
            return false;
        }

        if (player.getGameMode() == GameMode.CREATIVE) {
            debug("Player " + player.getName() + " is in Creative mode. Ignoring drop event.");
            return false;
        }

        boolean isToggleDisabled = !plugin.getDatabaseManager().getPlayerPreference(player.getUniqueId().toString());
        if (isToggleDisabled) {
            debug("Player " + player.getName() + " has disabled Confirm2Drop for themselves. Ignoring drop event.");
            return false;
        }

        return true;
    }

    public void resetPendingConfirmation(Player player) {
        UUID playerUUID = player.getUniqueId();
        pendingHotbarConfirmation.remove(playerUUID);
        pendingGuiConfirmation.remove(playerUUID);
        cursorOrigin.remove(playerUUID);
        bypassNextDrop.remove(playerUUID);
        debug("Pending confirmation reset for player " + player.getName());
    }

    private void debug(String message) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            Bukkit.getLogger().info("[Confirm2Drop DEBUG] " + message);
        }
    }
}
