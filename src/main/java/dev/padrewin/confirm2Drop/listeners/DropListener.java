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
     * Tick in which a player's GUI drop was confirmed and allowed to proceed
     * unmodified, so the PlayerDropItemEvent it causes isn't re-evaluated (and
     * potentially re-cancelled) by {@link #onItemDrop}. Scoped to that one tick
     * rather than left standing: the click and the drop it triggers always land in
     * the same tick, and if the drop never arrives - another plugin swallowed the
     * click - the mark must not wave through some unrelated drop minutes later.
     */
    private final Set<UUID> bypassDropForTick = new HashSet<>();

    /**
     * Tick in which a player's inventory screen was closed. Closing throws out
     * everything the screen was holding that has nowhere else to go: the cursor
     * item, the 2x2 crafting grid, and the input slots of an anvil, grindstone or
     * crafting table. Those arrive as PlayerDropItemEvents that never passed
     * through an InventoryClickEvent, and are the drops {@link #onItemDrop} must
     * not blindly cancel - see {@link #handleInventoryCloseDrop}. Unlike
     * {@link #bypassDropForTick} this mark is not consumed by the first drop: one
     * close can throw out the cursor item and several slots at once, and every one
     * of them needs the same treatment.
     */
    private final Set<UUID> inventoryCloseForTick = new HashSet<>();

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
     * Handles the drop paths that reach a PlayerDropItemEvent without a preceding
     * InventoryClickEvent: pressing Q on the hotbar with no inventory screen open,
     * and everything the server throws out when a screen is closed (see
     * {@link #handleInventoryCloseDrop}). Drops made through an open inventory
     * screen are intercepted earlier, in {@link #onInventoryClick}, and never
     * reach this handler as something still needing a confirmation decision (see
     * {@link #bypassDropForTick}).
     * <p>
     * Drops the server makes on the player's behalf are left strictly alone - see
     * {@link #isServerDrivenDrop}.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        if (isServerDrivenDrop(player)) {
            debug("Leaving " + player.getName() + "'s server-driven drop untouched.");
            return;
        }

        // Consumed unconditionally, so a mark can never be carried into a drop it
        // was not meant for.
        boolean alreadyConfirmed = bypassDropForTick.remove(playerUUID);
        boolean thrownByInventoryClose = inventoryCloseForTick.contains(playerUUID);

        if (alreadyConfirmed) {
            debug("Player " + player.getName() + "'s already-confirmed GUI drop is proceeding.");
            return;
        }

        if (!isConfirmationEligible(player)) {
            return;
        }

        ItemStack item = event.getItemDrop().getItemStack();
        debug("Player " + player.getName() + " is trying to drop item: " + item.getType() + " x" + item.getAmount());

        if (thrownByInventoryClose) {
            handleInventoryCloseDrop(event, player, item);
            return;
        }

        int sourceSlot = player.getInventory().getHeldItemSlot();

        PendingHotbarDrop pending = pendingHotbarConfirmation.get(playerUUID);
        if (pending != null) {
            long currentTime = System.currentTimeMillis();
            boolean sameSlot = sourceSlot == pending.slot;
            boolean sameItem = areItemsEqual(pending.item, item);

            if (sameSlot && sameItem && currentTime < pending.expiry) {
                pendingHotbarConfirmation.remove(playerUUID);
                // Nothing more to do: the event was never cancelled, so the item is
                // already on its way to the ground. Spawning a copy and removing the
                // original would lose it outright if anything cancelled the
                // resulting ItemSpawnEvent.
                debug("Player " + player.getName() + " confirmed the drop for item: " + item.getType());
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

        // Fires before the server empties the screen out, so mark the player now
        // for however many PlayerDropItemEvents that turns into.
        markForThisTick(inventoryCloseForTick, playerUUID);
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
                markForThisTick(bypassDropForTick, playerUUID);
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
     * Handles everything the server throws out when an inventory screen closes: the
     * item left on the cursor, the 2x2 crafting grid, and the input slots of an
     * anvil, grindstone, crafting table and friends.
     * <p>
     * Cancelling such a drop is only safe when the item can actually go back into
     * the player's inventory. CraftBukkit restores a cancelled drop of this kind
     * through {@code PlayerInventory#addItem} and throws the leftover away, so a
     * full inventory means the item is destroyed rather than restored. Both
     * sources reach that state routinely: picking an item onto the cursor frees its
     * slot, a ground item is auto-picked into it, and the inventory is full again
     * by closing time - while container input slots only ever spill onto the ground
     * once {@code placeItemBackInInventory} has already found the inventory full.
     * <p>
     * With no room the drop is therefore left alone: the item lands on the ground,
     * which is what vanilla would have done anyway, and stays recoverable.
     */
    private void handleInventoryCloseDrop(PlayerDropItemEvent event, Player player, ItemStack item) {
        if (!shouldRequireConfirmation(item)) {
            debug("No confirmation required for " + item.getType() + " thrown out on inventory close.");
            return;
        }

        if (!canFitInInventory(player, item)) {
            debug("Player " + player.getName() + "'s inventory has no room for " + item.getType()
                    + "; letting it drop rather than cancelling into a silent deletion.");
            plugin.getManager(LocaleManager.class).sendMessage(player, "inventory-full-drop-message");
            return;
        }

        // The server's own restore puts it back; re-adding it here as well would
        // duplicate it - see cancelAndRestore.
        event.setCancelled(true);
        debug("Player " + player.getName() + "'s protected item " + item.getType()
                + " was returned to their inventory on inventory close.");
    }

    /**
     * Whether the server is emptying the player out rather than the player throwing
     * something away: death loot when keepInventory is off, and the cleanup that
     * runs as someone disconnects.
     * <p>
     * Neither may be interfered with. Death loot has to reach the ground exactly as
     * the server decided - cancelling any of it would both destroy the item, since
     * the corpse's inventory is still full while the loot is thrown, and quietly
     * rewrite the server's own death rules. A disconnecting player's inventory is
     * on its way to disk, so nothing restored into it can be relied on either.
     */
    private boolean isServerDrivenDrop(Player player) {
        return !player.isOnline() || player.isDead() || player.getHealth() <= 0.0D;
    }

    /**
     * Marks a player in {@code marks} for the rest of the current tick only.
     * <p>
     * Everything these marks describe - a confirmed click and the drop it causes,
     * a close and the items it throws out - happens inside a single tick, so the
     * cleanup is simply the next tick's first task. Leaving a mark standing instead
     * would mean a drop it was never meant for eventually inherits it.
     * <p>
     * {@code Bukkit.getCurrentTick()} would say the same thing without a task, but
     * it is Paper-only; this works on Spigot too.
     */
    private void markForThisTick(Set<UUID> marks, UUID playerUUID) {
        if (!plugin.isEnabled()) {
            // Nothing could clear the mark again, so don't set one.
            return;
        }

        if (marks.add(playerUUID)) {
            Bukkit.getScheduler().runTask(plugin, () -> marks.remove(playerUUID));
        }
    }

    /**
     * Whether {@code item} fits entirely into the player's 36 storage slots,
     * mirroring how {@code PlayerInventory#addItem} tops up matching stacks before
     * using empty slots. Decides whether cancelling a cursor drop restores the item
     * or silently destroys it.
     */
    private boolean canFitInInventory(Player player, ItemStack item) {
        int remaining = item.getAmount();

        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType() == Material.AIR) {
                remaining -= item.getMaxStackSize();
            } else if (slot.isSimilar(item)) {
                remaining -= Math.max(0, slot.getMaxStackSize() - slot.getAmount());
            }

            if (remaining <= 0) {
                return true;
            }
        }

        return remaining <= 0;
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
        bypassDropForTick.remove(playerUUID);
        inventoryCloseForTick.remove(playerUUID);
        debug("Pending confirmation reset for player " + player.getName());
    }

    private void debug(String message) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            Bukkit.getLogger().info("[Confirm2Drop DEBUG] " + message);
        }
    }
}
