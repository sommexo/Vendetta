package dev.prc.vendetta;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import java.util.UUID;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** Creates and authenticates physical Marks. Visible item text is never used as proof. */
final class CurrencyService {
    static final long DYE_VALUE = 10;
    static final long STAR_VALUE = 1_000;
    private final NamespacedKey idKey;
    private final NamespacedKey denominationKey;
    private final NamespacedKey returnOwnerKey;

    CurrencyService(mark plugin) {
        idKey = new NamespacedKey(plugin, "currency_id");
        denominationKey = new NamespacedKey(plugin, "currency_denomination");
        returnOwnerKey = new NamespacedKey(plugin, "currency_return_owner");
    }

    ItemStack create(long denomination, int amount) {
        if ((denomination != DYE_VALUE && denomination != STAR_VALUE) || amount < 1)
            throw new IllegalArgumentException("Invalid currency denomination or amount");
        ItemStack item = new ItemStack(denomination == DYE_VALUE ? Material.PURPLE_DYE : Material.NETHER_STAR, amount);
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.setDisplayName(denomination == DYE_VALUE ? "§5§lVendetta Mark (10)" : "§d§lVendetta Mark (1,000)");
        meta.setLore(List.of("§7Currency value: §e" + denomination + " Marks", "§8Authentic VenMark currency"));
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, "vendetta_marks_v1");
        meta.getPersistentDataContainer().set(denominationKey, PersistentDataType.LONG, denomination);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack createOwnerReturn(long denomination, int amount, UUID owner) {
        ItemStack item = create(denomination, amount);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(returnOwnerKey, PersistentDataType.STRING, owner.toString());
        item.setItemMeta(meta);
        return item;
    }

    List<ItemStack> ownerReturnItems(long value, UUID owner) {
        if (value < 0 || value % DYE_VALUE != 0) throw new IllegalArgumentException("Currency value must be a non-negative multiple of 10");
        List<ItemStack> result = new ArrayList<>();
        appendOwnerStacks(result, STAR_VALUE, value / STAR_VALUE, owner);
        appendOwnerStacks(result, DYE_VALUE, (value % STAR_VALUE) / DYE_VALUE, owner);
        return result;
    }

    String returnOwner(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(returnOwnerKey, PersistentDataType.STRING);
    }

    void unlockOwnerReturn(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().remove(returnOwnerKey);
        item.setItemMeta(meta);
    }

    long denomination(ItemStack item) {
        if (item == null || item.getAmount() <= 0 || !item.hasItemMeta()) return 0;
        ItemMeta meta = item.getItemMeta();
        String id = meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        Long value = meta.getPersistentDataContainer().get(denominationKey, PersistentDataType.LONG);
        if (!"vendetta_marks_v1".equals(id) || value == null) return 0;
        if (value == DYE_VALUE && item.getType() == Material.PURPLE_DYE) return DYE_VALUE;
        if (value == STAR_VALUE && item.getType() == Material.NETHER_STAR) return STAR_VALUE;
        return 0;
    }

    long value(ItemStack item) {
        long denomination = denomination(item);
        return denomination == 0 ? 0 : denomination * item.getAmount();
    }

    long inventoryValue(Player player) {
        long total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) total = Math.addExact(total, value(item));
        total = Math.addExact(total, value(player.getInventory().getItemInOffHand()));
        return total;
    }

    boolean takeExact(Player player, long requested) {
        if (requested < 0 || requested % DYE_VALUE != 0) return false;
        ItemStack[] storage = player.getInventory().getStorageContents();
        ItemStack offhand = player.getInventory().getItemInOffHand();
        long total = 0;
        for (ItemStack item : storage) total += value(item);
        total += value(offhand);
        if (total < requested) return false;

        long remaining = requested;
        int[] removals = new int[storage.length];
        int offhandRemoval = 0;
        for (int i = 0; i < storage.length && remaining >= STAR_VALUE; i++) {
            ItemStack item = storage[i];
            if (denomination(item) != STAR_VALUE) continue;
            int count = (int) Math.min(item.getAmount(), remaining / STAR_VALUE);
            removals[i] = count;
            remaining -= (long) count * STAR_VALUE;
        }
        if (remaining >= STAR_VALUE && denomination(offhand) == STAR_VALUE) {
            offhandRemoval = (int) Math.min(offhand.getAmount(), remaining / STAR_VALUE);
            remaining -= (long) offhandRemoval * STAR_VALUE;
        }
        for (int i = 0; i < storage.length && remaining >= DYE_VALUE; i++) {
            ItemStack item = storage[i];
            if (denomination(item) != DYE_VALUE) continue;
            int count = (int) Math.min(item.getAmount(), remaining / DYE_VALUE);
            removals[i] += count;
            remaining -= (long) count * DYE_VALUE;
        }
        if (remaining >= DYE_VALUE && denomination(offhand) == DYE_VALUE) {
            int count = (int) Math.min(offhand.getAmount(), remaining / DYE_VALUE);
            offhandRemoval += count;
            remaining -= (long) count * DYE_VALUE;
        }
        if (remaining != 0) return false;
        for (int i = 0; i < storage.length; i++) {
            if (removals[i] == 0) continue;
            ItemStack item = storage[i];
            if (item.getAmount() == removals[i]) storage[i] = null;
            else item.setAmount(item.getAmount() - removals[i]);
        }
        player.getInventory().setStorageContents(storage);
        if (offhandRemoval > 0) {
            if (offhand.getAmount() == offhandRemoval) player.getInventory().setItemInOffHand(null);
            else { offhand.setAmount(offhand.getAmount() - offhandRemoval); player.getInventory().setItemInOffHand(offhand); }
        }
        return true;
    }

    List<ItemStack> itemsFor(long value) {
        if (value < 0 || value % DYE_VALUE != 0) throw new IllegalArgumentException("Currency value must be a non-negative multiple of 10");
        List<ItemStack> result = new ArrayList<>();
        long stars = value / STAR_VALUE;
        long dyes = (value % STAR_VALUE) / DYE_VALUE;
        appendStacks(result, STAR_VALUE, stars);
        appendStacks(result, DYE_VALUE, dyes);
        return result;
    }

    private void appendStacks(List<ItemStack> output, long denomination, long count) {
        while (count > 0) {
            int stack = (int) Math.min(count, 64);
            output.add(create(denomination, stack));
            count -= stack;
        }
    }

    private void appendOwnerStacks(List<ItemStack> output, long denomination, long count, UUID owner) {
        while (count > 0) {
            int stack = (int) Math.min(count, 64);
            output.add(createOwnerReturn(denomination, stack, owner));
            count -= stack;
        }
    }
}
