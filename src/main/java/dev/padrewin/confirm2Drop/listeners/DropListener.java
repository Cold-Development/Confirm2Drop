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
import java.util.Map;
import java.util.UUID;

public class DropListener implements Listener {

    private final Confirm2Drop plugin;

    // Asociem jucătorul cu detalii despre itemul care necesită confirmare și timpul de expirare
    private final Map<UUID, ItemStack> pendingConfirmation = new HashMap<>();
    private final Map<UUID, Long> confirmationTimeouts = new HashMap<>();

    public DropListener(Confirm2Drop plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Verificăm dacă pluginul este dezactivat global
        if (!plugin.getConfig().getBoolean("confirm2drop", true)) {
            return; // Permitem drop-ul fără cerințe
        }

        // Ignorăm confirmarea pentru jucătorii aflați în Creative
        if (player.getGameMode() == GameMode.CREATIVE) {
            return; // Permitem direct drop-ul fără cerințe
        }

        // Verificăm dacă jucătorul are confirmarea dezactivată
        boolean isToggleDisabled = !plugin.getDatabaseManager().getPlayerPreference(playerUUID.toString());
        if (isToggleDisabled) {
            return; // Nu cerem confirmare dacă preferința este dezactivată
        }

        ItemStack item = event.getItemDrop().getItemStack();

        // Dacă există deja o cerere activă pentru acest item
        if (pendingConfirmation.containsKey(playerUUID)) {
            ItemStack pendingItem = pendingConfirmation.get(playerUUID);

            // Verificăm dacă este același item și timeout-ul nu a expirat
            long currentTime = System.currentTimeMillis();
            long timeoutEnd = confirmationTimeouts.getOrDefault(playerUUID, 0L);

            if (areItemsEqual(pendingItem, item) && currentTime < timeoutEnd) {
                // Confirmare completă, permite drop-ul
                pendingConfirmation.remove(playerUUID);
                confirmationTimeouts.remove(playerUUID);
                return; // Permitem drop-ul fără cerere suplimentară
            } else if (!areItemsEqual(pendingItem, item)) {
                // Dacă este un item diferit, eliminăm cererea pentru itemul anterior
                pendingConfirmation.remove(playerUUID);
                confirmationTimeouts.remove(playerUUID);
            }
        }

        // Dacă itemul curent nu necesită confirmare, permite drop-ul
        if (!shouldRequireConfirmation(item)) {
            return;
        }

        // Cerem confirmare pentru itemul curent
        event.setCancelled(true);
        requestConfirmation(player, item);
    }

    private void requestConfirmation(Player player, ItemStack item) {
        UUID playerUUID = player.getUniqueId();
        pendingConfirmation.put(playerUUID, item.clone()); // Stocăm o copie pentru comparații ulterioare

        int timeoutSeconds = plugin.getConfig().getInt("confirmation-timeout", 10); // Timp default de 10 secunde
        long timeoutEnd = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        confirmationTimeouts.put(playerUUID, timeoutEnd); // Setăm timpul de expirare

        plugin.getManager(LocaleManager.class).sendMessage(player, "drop-confirmation-message");
    }

    private boolean shouldRequireConfirmation(ItemStack item) {
        boolean toolsBlacklist = plugin.getConfig().getBoolean("blacklist.tools", true);
        boolean armorBlacklist = plugin.getConfig().getBoolean("blacklist.armor", true);
        boolean spawnEggsBlacklist = plugin.getConfig().getBoolean("blacklist.spawn-eggs", true);
        boolean enchantedItemsBlacklist = plugin.getConfig().getBoolean("blacklist.enchanted-items", true);

        // Verificăm fiecare setare și tipul itemului
        if (toolsBlacklist && isTool(item.getType())) return true;
        if (armorBlacklist && isArmor(item.getType())) return true;
        if (spawnEggsBlacklist && item.getType().toString().endsWith("_SPAWN_EGG")) return true;
        if (enchantedItemsBlacklist && item.getEnchantments().size() > 0) return true;

        return false; // Dacă nu este pe lista neagră sau setările sunt dezactivate
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
        // Verificăm dacă itemele sunt similare și au aceeași cantitate
        return item1.isSimilar(item2) && item1.getAmount() == item2.getAmount();
    }

    public void resetPendingConfirmation(Player player) {
        UUID playerUUID = player.getUniqueId();
        pendingConfirmation.remove(playerUUID);
        confirmationTimeouts.remove(playerUUID);
    }
}
