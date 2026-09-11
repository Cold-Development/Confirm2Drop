package dev.padrewin.confirm2Drop.listeners;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import dev.padrewin.confirm2Drop.Confirm2Drop;
import dev.padrewin.confirm2Drop.database.DatabaseManager;
import dev.padrewin.confirm2Drop.manager.LocaleManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The invariant every test here defends: <b>Confirm2Drop must never make an item
 * disappear.</b>
 * <p>
 * Cancelling a {@link PlayerDropItemEvent} is not a neutral act. CraftBukkit
 * restores a cancelled drop by pushing the stack back through
 * {@code PlayerInventory#addItem} and discards whatever does not fit, so
 * cancelling a drop the player's inventory has no room for deletes the item
 * outright. Every scenario below is either a place where that can happen, or a
 * drop the plugin has no business touching at all.
 * <p>
 * These are decision tests: they assert what the listener does with an event,
 * which is the only part the plugin controls. MockBukkit is here purely to give
 * real {@link ItemStack}s a working {@code ItemFactory}; the player and its
 * inventory are Mockito mocks so each scenario's state is exact.
 */
class DropListenerTest {

    private static final int STORAGE_SLOTS = 36;
    private static final int HELD_SLOT = 0;

    /** Raw slot in the player's own inventory view, past the 5 crafting slots. */
    private static final int RAW_SLOT = 9;

    private ServerMock server;
    private Confirm2Drop plugin;
    private LocaleManager locale;
    private DropListener listener;

    private Player player;
    private PlayerInventory inventory;
    private World world;
    private Inventory topInventory;

    private ItemStack[] storage;
    private ItemStack cursor;
    private Item lastDroppedEntity;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();

        YamlConfiguration config = new YamlConfiguration();
        config.set("confirm2drop", true);
        config.set("confirmation-timeout", 10);
        config.set("debug", false);
        config.set("blacklist.tools", true);
        config.set("blacklist.armor", true);
        config.set("blacklist.spawn-eggs", true);
        config.set("blacklist.enchanted-items", true);

        DatabaseManager database = mock(DatabaseManager.class);
        when(database.getPlayerPreference(anyString())).thenReturn(true);

        this.locale = mock(LocaleManager.class);

        this.plugin = mock(Confirm2Drop.class);
        when(this.plugin.getName()).thenReturn("Confirm2Drop");
        when(this.plugin.isEnabled()).thenReturn(true);
        when(this.plugin.getConfig()).thenReturn(config);
        when(this.plugin.getDatabaseManager()).thenReturn(database);
        when(this.plugin.getManager(LocaleManager.class)).thenReturn(this.locale);

        this.storage = new ItemStack[STORAGE_SLOTS];
        this.cursor = new ItemStack(Material.AIR);

        this.inventory = mock(PlayerInventory.class);
        when(this.inventory.getStorageContents()).thenAnswer(call -> this.storage);
        when(this.inventory.getHeldItemSlot()).thenReturn(HELD_SLOT);
        when(this.inventory.firstEmpty()).thenAnswer(call -> {
            for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
                if (this.storage[slot] == null) {
                    return slot;
                }
            }
            return -1;
        });

        this.world = mock(World.class);
        this.topInventory = mock(Inventory.class);
        when(this.topInventory.getSize()).thenReturn(5);

        this.player = mock(Player.class);
        when(this.player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(this.player.getName()).thenReturn("Tester");
        when(this.player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(this.player.isOnline()).thenReturn(true);
        when(this.player.isDead()).thenReturn(false);
        when(this.player.getHealth()).thenReturn(20.0D);
        when(this.player.getInventory()).thenReturn(this.inventory);
        when(this.player.getWorld()).thenReturn(this.world);
        when(this.player.getLocation()).thenReturn(new Location(this.world, 0, 64, 0));
        when(this.player.getItemOnCursor()).thenAnswer(call -> this.cursor);

        this.listener = new DropListener(this.plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------
    // Scenario helpers
    // ------------------------------------------------------------------

    private ItemStack protectedItem() {
        return new ItemStack(Material.DIAMOND_SWORD);
    }

    private void fillInventory() {
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            this.storage[slot] = new ItemStack(Material.STONE, 64);
        }
    }

    private void nextTick() {
        this.server.getScheduler().performOneTick();
    }

    private PlayerDropItemEvent dropOf(ItemStack stack) {
        Item entity = mock(Item.class);
        when(entity.getItemStack()).thenReturn(stack);
        this.lastDroppedEntity = entity;
        return new PlayerDropItemEvent(this.player, entity);
    }

    /**
     * Asserts the plugin only ever read from {@code target}.
     * <p>
     * Checking method names rather than a list of forbidden calls is deliberate:
     * the invariant is that the listener changes nothing outside the event itself,
     * so a mutator nobody thought to forbid is caught just the same.
     */
    private void assertOnlyRead(Object target, String what) {
        String mutators = mockingDetails(target).getInvocations().stream()
                .map(invocation -> invocation.getMethod().getName())
                .filter(name -> name.startsWith("set") || name.startsWith("add")
                        || name.startsWith("remove") || name.startsWith("clear")
                        || name.startsWith("drop") || name.startsWith("spawn"))
                .distinct()
                .collect(Collectors.joining(", "));

        assertTrue(mutators.isEmpty(), what + " must be left untouched, but the plugin called: " + mutators);
    }

    /** Everything the plugin could duplicate an item through. */
    private void assertNothingWasDuplicated() {
        assertOnlyRead(this.world, "the world");
        assertOnlyRead(this.inventory, "the player's inventory");
        assertOnlyRead(this.player, "the player");
        if (this.lastDroppedEntity != null) {
            assertOnlyRead(this.lastDroppedEntity, "the dropped item entity");
        }
    }

    private ItemStack spawnEggs(int amount) {
        return new ItemStack(Material.ZOMBIE_SPAWN_EGG, amount);
    }

    /** Fires the drop through the listener and reports whether it was cancelled. */
    private boolean cancels(ItemStack stack) {
        PlayerDropItemEvent event = dropOf(stack);
        this.listener.onItemDrop(event);
        return event.isCancelled();
    }

    /** The close packet the server handles right before it throws leftover items. */
    private void closeInventory() {
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(this.player);
        this.listener.onInventoryClose(new InventoryCloseEvent(view));
    }

    /** Q pressed on an inventory slot while a screen is open. */
    private InventoryClickEvent slotDropClick(ItemStack current) {
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(this.player);
        when(view.getTopInventory()).thenReturn(this.topInventory);
        when(view.getBottomInventory()).thenReturn(this.inventory);
        when(view.getInventory(RAW_SLOT)).thenReturn(this.inventory);
        when(view.convertSlot(RAW_SLOT)).thenReturn(RAW_SLOT - 5);
        when(view.getItem(RAW_SLOT)).thenReturn(current);

        return new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, RAW_SLOT,
                ClickType.DROP, InventoryAction.DROP_ALL_SLOT);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Drops the server makes on the player's behalf")
    class ServerDrivenDrops {

        @Test
        @DisplayName("death loot is never cancelled, so keepInventory=false drops everything")
        void deathLootIsNeverTouched() {
            when(player.isDead()).thenReturn(true);
            when(player.getHealth()).thenReturn(0.0D);
            // The corpse's inventory is still full while the loot is thrown, so
            // every cancel here would be an outright deletion.
            fillInventory();

            List<ItemStack> loot = List.of(
                    new ItemStack(Material.DIAMOND_SWORD),
                    new ItemStack(Material.NETHERITE_CHESTPLATE),
                    new ItemStack(Material.DIAMOND_PICKAXE),
                    new ItemStack(Material.ZOMBIE_SPAWN_EGG),
                    new ItemStack(Material.COBBLESTONE, 64));

            for (ItemStack stack : loot) {
                assertFalse(cancels(stack), stack.getType() + " was swallowed instead of dropped on death");
            }

            verify(locale, never()).sendMessage(any(CommandSender.class), anyString());
        }

        @Test
        @DisplayName("a drop from a player who is already gone is never cancelled")
        void disconnectCleanupIsNeverTouched() {
            when(player.isOnline()).thenReturn(false);
            fillInventory();

            assertFalse(cancels(protectedItem()),
                    "restoring into a leaving player's inventory is not something we can rely on");
        }
    }

    @Nested
    @DisplayName("Items the server throws when an inventory screen closes")
    class CloseCleanupDrops {

        @Test
        @DisplayName("the cursor item is put back when there is room")
        void cursorItemIsRestoredWhenItFits() {
            cursor = protectedItem();
            closeInventory();

            assertTrue(cancels(protectedItem()), "there was room, so the item belongs back in the inventory");
        }

        @Test
        @DisplayName("the cursor item is left to fall when the inventory is full")
        void cursorItemFallsWhenInventoryIsFull() {
            // Picking the item onto the cursor freed its slot, a ground item was
            // auto-picked into it, and the inventory is full again by close time.
            fillInventory();
            cursor = protectedItem();
            closeInventory();

            assertFalse(cancels(protectedItem()), "cancelling here deletes the item instead of restoring it");
        }

        @Test
        @DisplayName("crafting-grid and container-input leftovers are left to fall too")
        void craftingLeftoversFallWhenInventoryIsFull() {
            // Nothing on the cursor: this is the 2x2 grid, or an anvil/grindstone
            // input, being emptied. placeItemBackInInventory only reaches a drop
            // once the inventory is already full, so a cancel is always fatal.
            fillInventory();
            closeInventory();

            assertFalse(cancels(new ItemStack(Material.DIAMOND_PICKAXE)),
                    "an anvil or 2x2 leftover would be deleted by the restore");
        }

        @Test
        @DisplayName("every item a single close throws is handled, not just the first")
        void allItemsThrownByOneCloseAreHandled() {
            fillInventory();
            cursor = protectedItem();
            closeInventory();

            List<ItemStack> thrown = List.of(
                    new ItemStack(Material.DIAMOND_SWORD),
                    new ItemStack(Material.IRON_HELMET),
                    new ItemStack(Material.GOLDEN_HOE));

            for (ItemStack stack : thrown) {
                assertFalse(cancels(stack), stack.getType() + " would be deleted by the restore");
            }
        }

        @Test
        @DisplayName("the close window does not leak into the next tick")
        void closeWindowExpires() {
            cursor = protectedItem();
            closeInventory();
            nextTick();

            assertTrue(cancels(protectedItem()), "an ordinary hotbar drop must still ask for confirmation");
            verify(locale).sendMessage(player, "drop-confirmation-message");
        }
    }

    @Nested
    @DisplayName("Confirmation bookkeeping")
    class ConfirmationState {

        @Test
        @DisplayName("an unused bypass does not leak into a later drop")
        void unusedBypassDoesNotLeak() {
            ItemStack sword = protectedItem();
            storage[RAW_SLOT - 5] = sword;

            InventoryClickEvent request = slotDropClick(sword);
            listener.onInventoryClick(request);
            assertTrue(request.isCancelled(), "the first Q asks for confirmation");

            InventoryClickEvent confirm = slotDropClick(sword);
            listener.onInventoryClick(confirm);
            assertFalse(confirm.isCancelled(), "the second Q lets the drop through");

            // Another plugin swallowed the click, so no PlayerDropItemEvent ever
            // arrived to consume the bypass.
            nextTick();

            assertTrue(cancels(protectedItem()),
                    "an unrelated later drop must still be confirmed, not waved through");
        }

        @Test
        @DisplayName("a confirmed hotbar drop is not re-spawned on the ground")
        void confirmedHotbarDropIsNotRespawned() {
            fillInventory();
            ItemStack sword = protectedItem();
            storage[HELD_SLOT] = sword;

            assertTrue(cancels(sword), "the first Q asks for confirmation");
            assertFalse(cancels(sword), "the second Q lets the drop through");

            // The event was never cancelled, so the item is already on the ground.
            // Spawning a copy and removing the original loses it outright if
            // anything cancels the resulting ItemSpawnEvent.
            verify(world, never()).dropItemNaturally(any(Location.class), any(ItemStack.class));
        }
    }

    @Nested
    @DisplayName("Nothing the plugin does can produce a second copy")
    class NeverDuplicates {

        /**
         * Cancelling is the plugin's only legitimate move. The server restores a
         * cancelled drop by itself, so any restore of our own is a second copy -
         * this is the bug that {@code cancelAndRestore} was written to close, and
         * the assertion that keeps it closed.
         */
        @Test
        @DisplayName("a cancelled drop is restored by the server alone")
        void cancellingNeverComesWithARestoreOfOurOwn() {
            storage[HELD_SLOT] = protectedItem();

            assertTrue(cancels(protectedItem()));
            assertNothingWasDuplicated();
        }

        @Test
        @DisplayName("an item put back on inventory close is put back by the server alone")
        void closeRestoreIsLeftEntirelyToTheServer() {
            cursor = protectedItem();
            closeInventory();

            assertTrue(cancels(protectedItem()));
            assertNothingWasDuplicated();
        }

        @Test
        @DisplayName("a drop left to fall is never also spawned on the ground by us")
        void anAllowedDropIsNeverSpawnedTwice() {
            fillInventory();
            cursor = protectedItem();
            closeInventory();

            assertFalse(cancels(protectedItem()));
            assertNothingWasDuplicated();
        }

        /**
         * The plugin listens at HIGH, so a plugin at a lower priority can still
         * cancel the drop after it has had its say. Anything the listener already
         * did to the world by then survives that cancellation and stands alongside
         * the server's restore - two copies from one item.
         */
        @Test
        @DisplayName("a confirmed drop leaves nothing behind for a later cancel to duplicate")
        void aConfirmedDropSurvivesALaterCancelWithoutDuplicating() {
            fillInventory();
            ItemStack sword = protectedItem();
            storage[HELD_SLOT] = sword;

            assertTrue(cancels(sword), "the first Q asks for confirmation");

            PlayerDropItemEvent confirmed = dropOf(sword);
            listener.onItemDrop(confirmed);
            assertFalse(confirmed.isCancelled(), "the second Q lets the drop through");

            // A MONITOR-priority plugin cancels it after us; the server now restores
            // the stack into the inventory.
            confirmed.setCancelled(true);

            assertNothingWasDuplicated();
        }

        @Test
        @DisplayName("death loot is passed through without a copy being made")
        void deathLootIsNeverCopied() {
            when(player.isDead()).thenReturn(true);
            when(player.getHealth()).thenReturn(0.0D);
            fillInventory();

            assertFalse(cancels(protectedItem()));
            assertNothingWasDuplicated();
        }

        @Test
        @DisplayName("a confirmed GUI drop is waved through without a copy being made")
        void confirmedGuiDropIsNeverCopied() {
            ItemStack sword = protectedItem();
            storage[RAW_SLOT - 5] = sword;

            listener.onInventoryClick(slotDropClick(sword));
            listener.onInventoryClick(slotDropClick(sword));

            PlayerDropItemEvent confirmed = dropOf(sword);
            listener.onItemDrop(confirmed);

            assertFalse(confirmed.isCancelled());
            assertNothingWasDuplicated();
        }

        /**
         * {@code addItem} fills what it can and discards the rest, so "some of it
         * fits" has to count as "it does not fit". Treating a partial fit as room
         * would quietly destroy the remainder on every close.
         */
        @Test
        @DisplayName("a stack that only partly fits is left to fall, not half-restored")
        void partiallyFittingStackIsNotCancelled() {
            fillInventory();
            storage[10] = spawnEggs(60);   // room for exactly 4 more
            closeInventory();

            assertFalse(cancels(spawnEggs(64)), "60 of the 64 would be discarded by the restore");
            assertNothingWasDuplicated();
        }

        @Test
        @DisplayName("a stack that fits exactly is restored")
        void exactlyFittingStackIsCancelled() {
            fillInventory();
            storage[10] = spawnEggs(60);   // room for exactly 4 more
            closeInventory();

            assertTrue(cancels(spawnEggs(4)), "all 4 fit, so the item belongs back in the inventory");
            assertNothingWasDuplicated();
        }
    }

    @Nested
    @DisplayName("Ordinary hotbar drops still behave")
    class HotbarDrops {

        @Test
        @DisplayName("a protected item asks for confirmation once")
        void protectedItemAsksForConfirmation() {
            storage[HELD_SLOT] = protectedItem();

            assertTrue(cancels(protectedItem()));
            verify(locale).sendMessage(player, "drop-confirmation-message");
        }

        @Test
        @DisplayName("an unprotected item drops straight away")
        void plainItemDropsImmediately() {
            assertFalse(cancels(new ItemStack(Material.COBBLESTONE, 64)));
            verify(locale, never()).sendMessage(any(CommandSender.class), anyString());
        }
    }
}
