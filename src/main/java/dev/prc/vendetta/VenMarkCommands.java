package dev.prc.vendetta;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class VenMarkCommands implements BasicCommand {
    private final mark plugin;
    private final ClassSelectionService classes;
    private final RelicService relics;
    private final VanguardProgression progression;

    VenMarkCommands(mark plugin, ClassSelectionService classes, RelicService relics, VanguardProgression progression) {
        this.plugin = plugin;
        this.classes = classes;
        this.relics = relics;
        this.progression = progression;
    }

    @Override public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) { help(sender); return; }
        String root = args[0].toLowerCase(Locale.ROOT);
        switch (root) {
            case "choose" -> choose(sender, args);
            case "deposit", "withdraw" -> bankTransaction(sender, args, root);
            case "currency", "relic", "class", "marks", "abuse" -> admin(sender, args, root);
            case "berserker" -> abilityDescription(sender, args, "berserker");
            case "bloodthirst" -> abilityDescription(sender, args, "bloodthirst");
            case "blood" -> {
                if (args.length == 3 && args[1].equalsIgnoreCase("blood") && args[2].equalsIgnoreCase("blood"))
                    abilityDescription(sender, new String[]{"ultimate"}, "ultimate");
                else abilityDescription(sender, args, "ultimate");
            }
            case "ultimate", "bloodbloodblood", "blood-blood-blood", "blood_blood_blood" -> abilityDescription(sender, args, "ultimate");
            default -> { sender.sendMessage(plugin.message("&cUnknown command. Use /venmark for help.")); }
        }
    }

    private void bankTransaction(CommandSender sender, String[] args, String operation) {
        if (!sender.hasPermission("venmark.use")) {
            sender.sendMessage(plugin.message("&cYou don't have permission: venmark.use"));
            return;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("This command is for players."); return; }
        if (args.length != 2) {
            sender.sendMessage(plugin.message(operation.equals("deposit")
                    ? "&eUsage: /venmark deposit <amount>" : "&eUsage: /venmark withdraw <amount>"));
            return;
        }
        Long amount = positive(args[1]);
        if (amount == null || amount % CurrencyService.DYE_VALUE != 0) {
            sender.sendMessage(plugin.message("&cAmount must be a positive multiple of 10 Marks."));
            return;
        }
        if (operation.equals("deposit")) {
            if (!plugin.depositFromInventory(player, amount)) {
                player.sendMessage(plugin.message("&cDeposit failed. Check genuine currency and bank capacity; nothing was consumed."));
                return;
            }
            player.sendMessage(plugin.message("&aDeposited " + amount + " Marks. Bank balance: " + plugin.getMarks(player.getUniqueId()) + "."));
            return;
        }
        long fee = amount / 20 + (amount % 20 == 0 ? 0 : 1);
        if (!plugin.withdrawPhysical(player, amount, fee)) {
            player.sendMessage(plugin.message("&cWithdrawal failed. Check your bank balance and inventory space; no changes were made."));
            return;
        }
        player.sendMessage(plugin.message("&aWithdrew " + amount + " Marks. Fee: " + fee + ". Total bank deduction: " + (amount + fee) + "."));
    }

    private void abilityDescription(CommandSender sender, String[] args, String ability) {
        if (args.length != 1) { sender.sendMessage(plugin.message("&eUsage: /venmark " + (ability.equals("ultimate") ? "ultimate" : ability))); return; }
        String title;
        String description;
        switch (ability) {
            case "berserker" -> {
                title = "Berserker — Passive";
                description = "Below 50% of max health, qualifying sword/axe hits deal "
                        + plugin.getConfig().getDouble("vanguard.berserker.dormant-multiplier", 1.10) + "x at Dormant, "
                        + plugin.getConfig().getDouble("vanguard.berserker.imperfect-multiplier", 1.25) + "x at Imperfect, and "
                        + plugin.getConfig().getDouble("vanguard.berserker.perfect-multiplier", 1.50) + "x at Perfect. Perfect rises to "
                        + plugin.getConfig().getDouble("vanguard.berserker.perfect-critical-multiplier", 1.75) + "x below 25% health.";
            }
            case "bloodthirst" -> {
                title = "Bloodthirst — Counter";
                description = "Right-click with a sword to arm, then crouch when hit. Dormant window: "
                        + plugin.getConfig().getLong("vanguard.bloodthirst.dormant-window-ms", 750) / 1000.0
                        + "s; Imperfect window: " + plugin.getConfig().getLong("vanguard.bloodthirst.imperfect-window-ms", 1000) / 1000.0
                        + "s. The hit still deals damage; a successful counter heals to full. A missed window starts a full cooldown of "
                        + plugin.getConfig().getLong("vanguard.bloodthirst.cooldown-seconds", 30) + "s.";
            }
            default -> {
                title = "BLOOD BLOOD BLOOD — Ultimate";
                long dormantCooldown = plugin.getConfig().getLong("vanguard.ultimate.dormant-cooldown-seconds", 240);
                long awakenedCooldown = plugin.getConfig().getLong("vanguard.ultimate.awakened-cooldown-seconds", 480);
                description = "A totem pop activates it when ready; another pop while active ends it. Dormant grants Strength I + Speed I; Perfect grants Strength II + Speed II + Fire Resistance. "
                        + "Every stage lasts until combat ends after " + plugin.getConfig().getLong("pvp.timeout-seconds", 15)
                        + "s without qualifying PvP damage. Cooldowns: Dormant " + dormantCooldown / 60 + "m, Perfect " + awakenedCooldown / 60 + "m. Ascendant grants kill-streak Strength up to IV and has a "
                        + plugin.getConfig().getLong("vanguard.ultimate.ascendant-cooldown-seconds", 2400) / 60 + "m cooldown.";
            }
        }
        sender.sendMessage(plugin.message("&6" + title));
        sender.sendMessage(plugin.message("&7" + description));
        if (sender instanceof Player player && "vanguard".equals(plugin.selectedClass(player.getUniqueId()))) {
            VanguardProgression.PlayerData data = progression.get(player.getUniqueId());
            VanguardProgression.Upgrade tier = switch (ability) {
                case "berserker" -> data.berserker;
                case "bloodthirst" -> data.bloodthirst;
                default -> data.ultimate;
            };
            sender.sendMessage(plugin.message("&7Your current progression: &e" + tier));
        }
    }

    private void choose(CommandSender sender, String[] args) {
        if (!sender.hasPermission("venmark.use")) { sender.sendMessage(plugin.message("&cYou don't have permission: venmark.use")); return; }
        if (args.length != 2) { sender.sendMessage(plugin.message("&eUsage: /venmark choose <vanguard|phantom|warden>")); return; }
        if (!(sender instanceof Player player)) { sender.sendMessage("Only players can choose a class."); return; }
        if (!classes.choose(player.getUniqueId(), args[1], false)) {
            if (classes.get(player.getUniqueId()) != null) sender.sendMessage(plugin.message("&cYour class is locked. Contact an administrator for an authorized override."));
            else sender.sendMessage(plugin.message("&cInvalid class. Choose vanguard, phantom, or warden."));
            return;
        }
        player.closeInventory();
        player.sendMessage(plugin.message("&aClass selected and saved."));
    }

    private void admin(CommandSender sender, String[] args, String root) {
        if (root.equals("marks") && args.length == 2 && args[1].equalsIgnoreCase("abuse")) {
            if (!allowed(sender, "venmark.admin")) return;
            sender.sendMessage(plugin.message("&6Recent suspected PvP abuse records:"));
            List<String> records = plugin.abuseRecords();
            if (records.isEmpty()) sender.sendMessage(plugin.message("&7No records."));
            else records.forEach(record -> sender.sendMessage(plugin.message("&7" + record)));
            return;
        }
        if (root.equals("marks") && args.length == 4 && args[1].equalsIgnoreCase("give")) {
            if (!allowed(sender, "venmark.marks.give")) return;
            OfflinePlayer target = target(args[2]); Long amount = positive(args[3]);
            if (target == null || amount == null) { sender.sendMessage(plugin.message("&cUsage: /venmark marks give <player> <amount>")); return; }
            plugin.credit(target.getUniqueId(), amount, "admin grant by " + sender.getName());
            sender.sendMessage(plugin.message("&aGranted " + amount + " Marks to " + target.getName() + "."));
            return;
        }
        if (root.equals("marks") && args.length == 3 && args[1].equalsIgnoreCase("reset")) {
            if (!allowed(sender, "venmark.marks.reset")) return;
            OfflinePlayer target = target(args[2]);
            if (target == null) { sender.sendMessage(plugin.message("&cPlayer not found.")); return; }
            long before = plugin.getMarks(target.getUniqueId());
            plugin.resetMarks(target.getUniqueId(), sender.getName());
            sender.sendMessage(plugin.message("&aReset " + target.getName() + " from " + before + " Marks to 0."));
            return;
        }
        if (root.equals("currency") && args.length == 5 && args[1].equalsIgnoreCase("give")) {
            if (!allowed(sender, "venmark.relic.give")) return;
            OfflinePlayer target = target(args[2]); Long denomination = denomination(args[3]); Long count = positive(args[4]);
            if (target == null || denomination == null || count == null || count > 64) {
                sender.sendMessage(plugin.message("&cUsage: /venmark currency give <player> <dye|star> <1-64>")); return;
            }
            Player online = target.getPlayer();
            if (online == null) { sender.sendMessage(plugin.message("&cTarget must be online to receive items.")); return; }
            for (ItemStack item : plugin.currencyItems(denomination, count.intValue()))
                for (Map.Entry<Integer, ItemStack> overflow : online.getInventory().addItem(item).entrySet())
                    online.getWorld().dropItemNaturally(online.getLocation(), overflow.getValue());
            plugin.audit("currency grant " + count + "x" + denomination + " to " + target.getUniqueId() + " by " + sender.getName());
            sender.sendMessage(plugin.message("&aGenuine currency delivered."));
            return;
        }
        if (root.equals("relic") && args.length == 5 && args[1].equalsIgnoreCase("give")) {
            if (!allowed(sender, "venmark.relic.give")) return;
            String clazz = args[2].toLowerCase(Locale.ROOT);
            Integer tier = relicTier(args[3]);
            OfflinePlayer target = target(args[4]);
            if (target == null || target.getPlayer() == null || !List.of("vanguard", "phantom", "warden").contains(clazz) || tier == null) {
                sender.sendMessage(plugin.message("&cUsage: /venmark relic give <class> <ascendant|imperfect|perfect> <player>")); return;
            }
            String ability = clazz.equals("vanguard") ? (tier == 1 ? "select" : "ultimate") : "class_relic";
            giveRelic(sender, target, clazz, ability, tier);
            return;
        }
        // Keep the previous command form working for existing staff workflows.
        if (root.equals("relic") && args.length == 6 && args[1].equalsIgnoreCase("give")) {
            if (!allowed(sender, "venmark.relic.give")) return;
            OfflinePlayer target = target(args[2]); String clazz = args[3].toLowerCase(Locale.ROOT);
            String ability = args[4].toLowerCase(Locale.ROOT); Integer tier = integer(args[5]);
            if (target == null || target.getPlayer() == null || !List.of("vanguard", "phantom", "warden").contains(clazz)
                    || tier == null || !relics.validRelicDefinition(clazz, ability, tier)) {
                sender.sendMessage(plugin.message("&cUsage: /venmark relic give <player> <class> <ability> <tier-1-3>")); return;
            }
            giveRelic(sender, target, clazz, ability, tier);
            return;
        }
        if (root.equals("class") && args.length == 4 && args[1].equalsIgnoreCase("set")) {
            if (!allowed(sender, "venmark.class.admin")) return;
            OfflinePlayer target = target(args[2]);
            if (target == null || !classes.choose(target.getUniqueId(), args[3], true)) {
                sender.sendMessage(plugin.message("&cUsage: /venmark class set <player> <vanguard|phantom|warden>")); return;
            }
            plugin.audit("class override " + args[3] + " for " + target.getUniqueId() + " by " + sender.getName());
            sender.sendMessage(plugin.message("&aClass override saved."));
            return;
        }
        sender.sendMessage(plugin.message("&cInvalid syntax or permission. Use /venmark for syntax."));
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission) || sender.hasPermission("venmark.admin")) return true;
        sender.sendMessage(plugin.message("&cYou don't have permission: " + permission));
        return false;
    }
    private OfflinePlayer target(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer offline = plugin.getServer().getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline : null;
    }
    private Long positive(String value) { try { long n = Long.parseLong(value); return n > 0 ? n : null; } catch (NumberFormatException ex) { return null; } }
    private Integer integer(String value) { try { return Integer.valueOf(value); } catch (NumberFormatException ex) { return null; } }
    private Long denomination(String value) { return switch (value.toLowerCase(Locale.ROOT)) { case "dye", "purple-dye" -> CurrencyService.DYE_VALUE; case "star", "nether-star" -> CurrencyService.STAR_VALUE; default -> null; }; }
    private Integer relicTier(String value) { return switch (value.toLowerCase(Locale.ROOT)) { case "imperfect" -> 1; case "perfect" -> 2; case "ascendant" -> 3; default -> null; }; }
    private void giveRelic(CommandSender sender, OfflinePlayer target, String clazz, String ability, int tier) {
        Player recipient = target.getPlayer();
        ItemStack relic = relics.createRelic(clazz, ability, tier);
        if (!plugin.canDeliver(recipient, relic)) {
            sender.sendMessage(plugin.message("&c" + recipient.getName() + " has no inventory space; no relic was created or dropped."));
            return;
        }
        if (!plugin.deliver(recipient, relic)) {
            sender.sendMessage(plugin.message("&cRelic delivery failed; no item was consumed or dropped."));
            return;
        }
        plugin.audit("relic grant " + clazz + "/" + ability + "/" + tier + " to " + target.getUniqueId() + " by " + sender.getName());
        sender.sendMessage(plugin.message("&a" + tierName(tier) + " " + clazz + " relic delivered to " + recipient.getName() + "."));
        recipient.sendMessage(plugin.message("&dYou received a " + tierName(tier) + " " + clazz + " relic."));
    }
    private String tierName(int tier) { return switch (tier) { case 1 -> "Imperfect"; case 2 -> "Perfect"; default -> "Ascendant"; }; }
    private void help(CommandSender sender) {
        sender.sendMessage(plugin.message("&6VenMark: /venmark choose <class> | /venmark deposit <amount> | /venmark withdraw <amount>"));
        sender.sendMessage(plugin.message("&7Abilities: /venmark berserker | /venmark bloodthirst | /venmark ultimate"));
        sender.sendMessage(plugin.message("&7Economy: /deposit <amount|hand|offhand> | /withdraw <amount> | /bounty <player>"));
        sender.sendMessage(plugin.message("&7Admin: /venmark marks give|reset, currency give, relic give, class set, marks abuse"));
    }

    @Override public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 1) return List.of("choose", "deposit", "withdraw", "berserker", "bloodthirst", "ultimate", "bloodbloodblood", "blood-blood-blood", "marks", "currency", "relic", "class");
        if (args.length == 2 && args[0].equalsIgnoreCase("blood")) return List.of("blood");
        if (args.length == 2 && (args[0].equalsIgnoreCase("deposit") || args[0].equalsIgnoreCase("withdraw"))) return List.of("10", "100", "1000");
        if (args.length == 2 && args[0].equalsIgnoreCase("choose")) return List.of("vanguard", "phantom", "warden");
        if (args.length == 2 && List.of("marks", "currency", "relic", "class").contains(args[0].toLowerCase(Locale.ROOT)))
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "marks" -> List.of("give", "reset", "abuse"); case "currency", "relic" -> List.of("give"); case "class" -> List.of("set"); default -> List.of();
            };
        if (args.length == 3 && args[0].equalsIgnoreCase("relic") && args[1].equalsIgnoreCase("give")) return List.of("vanguard", "phantom", "warden");
        if (args.length == 4 && args[0].equalsIgnoreCase("relic") && args[1].equalsIgnoreCase("give")) return List.of("ascendant", "imperfect", "perfect");
        if (args.length == 5 && args[0].equalsIgnoreCase("relic") && args[1].equalsIgnoreCase("give")) return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length >= 3) return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        return List.of();
    }

    @Override public boolean canUse(CommandSender sender) { return true; }
    @Override public String permission() { return null; }
}
