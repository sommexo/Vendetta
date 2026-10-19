package dev.prc.vendetta;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class VenMarkCommands implements BasicCommand {
    private static final List<String> ABILITY_NAMES = List.of(
            "berserker", "bloodthirst", "blood-blood-blood", "grim-reaper",
            "predators-instinct", "marked-prey", "into-the-void", "afterimage",
            "unshaken", "sculk-pulse", "ancient-guardian", "unyielding");
    private record AbilityDetails(String name, String description, String activation,
                                  String cooldown, String duration, String effects, String restrictions) { }
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
            case "ability" -> ability(sender, args);
            default -> { sender.sendMessage(plugin.message("&cUnknown command. Use /venmark for help.")); }
        }
    }

    private void ability(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.message("&eUsage: /venmark ability <ability name>"));
            if (sender instanceof Player player) {
                String clazz = plugin.selectedClass(player.getUniqueId());
                if (clazz == null) {
                    sender.sendMessage(plugin.message("&cChoose a class first with /venmark choose <vanguard|phantom|warden>."));
                } else {
                    sender.sendMessage(plugin.message("&7Your " + clazz + " abilities:"));
                    for (String name : abilitiesForClass(clazz))
                        sender.sendMessage(plugin.message("&f- " + displayName(name) + " &7(" + levelText(player, name) + ")"));
                }
            } else sender.sendMessage(plugin.message("&7Available: &f" + String.join(", ", ABILITY_NAMES)));
            return;
        }
        String requested = String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                .trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        String ability = switch (requested) {
            case "berserker", "bloodthirst", "afterimage", "unshaken", "unyielding" -> requested;
            case "blood-blood-blood", "bloodbloodblood", "blood_blood_blood" -> "ultimate";
            case "grim-reaper", "grimreaper", "grim_reaper" -> "grim-reaper";
            case "predators-instinct", "predators_instinct" -> "predators-instinct";
            case "marked-prey", "marked_prey" -> "marked-prey";
            case "into-the-void", "intothevoid", "into_the_void" -> "into-the-void";
            case "sculk-pulse", "sculk_pulse" -> "sculk-pulse";
            case "ancient-guardian", "ancient_guardian" -> "ancient-guardian";
            default -> {
                sender.sendMessage(plugin.message("&cUnknown ability '&f" + requested + "&c'."));
                sender.sendMessage(plugin.message("&7Valid names: &f" + String.join(", ", ABILITY_NAMES)));
                yield null;
            }
        };
        if (ability != null) abilityDescription(sender, ability);
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

    private void abilityDescription(CommandSender sender, String ability) {
        AbilityDetails details = abilityDetails(ability);
        sender.sendMessage(plugin.message("&6&l" + details.name()));
        sender.sendMessage(plugin.message("&7Description: &f" + details.description()));
        sender.sendMessage(plugin.message("&7Activation: &f" + details.activation()));
        sender.sendMessage(plugin.message("&7Cooldown: &f" + details.cooldown()));
        sender.sendMessage(plugin.message("&7Duration: &f" + details.duration()));
        sender.sendMessage(plugin.message("&7Effects: &f" + details.effects()));
        sender.sendMessage(plugin.message("&7Restrictions: &f" + details.restrictions()));
        if (sender instanceof Player player) {
            if (plugin.selectedClass(player.getUniqueId()) == null)
                sender.sendMessage(plugin.message("&eChoose a class with /venmark choose before checking your current progression."));
            String tier = levelText(player, ability);
            if (tier != null) sender.sendMessage(plugin.message("&7Your current progression: &e" + tier));
        }
    }

    private List<String> abilitiesForClass(String clazz) {
        return switch (clazz.toLowerCase(Locale.ROOT)) {
            case "vanguard" -> List.of("berserker", "bloodthirst", "blood-blood-blood", "grim-reaper");
            case "phantom" -> List.of("predators-instinct", "marked-prey", "into-the-void", "afterimage");
            case "warden" -> List.of("unshaken", "sculk-pulse", "ancient-guardian", "unyielding");
            default -> List.of();
        };
    }

    private String levelText(Player player, String ability) {
        VanguardProgression.Upgrade tier = plugin.abilityTier(player.getUniqueId(), ability);
        return tier == null ? "no separate upgrade level" : tier.name();
    }

    private String displayName(String ability) {
        return switch (ability) {
            case "blood-blood-blood" -> "BLOOD BLOOD BLOOD";
            case "predators-instinct" -> "Predator's Instinct";
            case "marked-prey" -> "Marked Prey";
            case "into-the-void" -> "Into the Void";
            case "sculk-pulse" -> "Sculk Pulse";
            case "ancient-guardian" -> "Ancient Guardian";
            case "grim-reaper" -> "Grim Reaper";
            default -> ability.substring(0, 1).toUpperCase(Locale.ROOT) + ability.substring(1);
        };
    }

    private AbilityDetails abilityDetails(String ability) {
        long combatSeconds = plugin.getConfig().getLong("pvp.timeout-seconds", 15);
        return switch (ability) {
            case "berserker" -> new AbilityDetails(
                    "Berserker — Passive",
                    "Vanguard deals increased qualifying weapon damage at low health.",
                    "Passive; no activation input.", "None.", "While below 10 hearts.",
                    plugin.getConfig().getDouble("vanguard.berserker.dormant-multiplier", 1.10) + "x Dormant, "
                            + plugin.getConfig().getDouble("vanguard.berserker.imperfect-multiplier", 1.25) + "x Imperfect, "
                            + plugin.getConfig().getDouble("vanguard.berserker.perfect-multiplier", 1.50) + "x Perfect; Perfect is "
                            + plugin.getConfig().getDouble("vanguard.berserker.perfect-critical-multiplier", 1.75) + "x below 5 hearts.",
                    "Qualifying sword and axe attacks only. No Ascendant upgrade.");
            case "bloodthirst" -> new AbilityDetails(
                    "Bloodthirst — Counter",
                    "A timed Vanguard counter that heals Vanguard to full after a successful trigger.",
                    "Right-click a sword, then crouch when a qualifying attack lands.",
                    plugin.getConfig().getLong("vanguard.bloodthirst.cooldown-seconds", 45) + " seconds from arming; a successful counter resets it.",
                    plugin.getConfig().getLong("vanguard.bloodthirst.dormant-window-ms", 750) / 1000.0 + " seconds Dormant; "
                            + plugin.getConfig().getLong("vanguard.bloodthirst.imperfect-window-ms", 1000) / 1000.0 + " seconds Imperfect; "
                            + plugin.getConfig().getLong("vanguard.bloodthirst.perfect-window-ms", 1250) / 1000.0 + " seconds Perfect; "
                            + plugin.getConfig().getLong("vanguard.bloodthirst.ascendant-window-ms", 1500) / 1000.0 + " seconds Ascendant."
                    "The triggering hit still deals damage; a successful counter restores full health.",
                    "Requires a sword, crouching at the correct moment, and a qualifying direct attack.");
            case "ultimate" -> new AbilityDetails(
                    "BLOOD BLOOD BLOOD — Ultimate",
                    "Vanguard's combat ultimate, activated by a Totem of Undying pop.",
                    "A Totem pop activates it when ready. A second Totem pop while active ends it.",
                    "Dormant " + plugin.getConfig().getLong("vanguard.ultimate.dormant-cooldown-seconds", 240) / 60 + " minutes; upgraded "
                            + plugin.getConfig().getLong("vanguard.ultimate.awakened-cooldown-seconds", 480) / 60 + " minutes; Ascendant "
                            + plugin.getConfig().getLong("vanguard.ultimate.ascendant-cooldown-seconds", 2400) / 60 + " minutes.",
                    "Until combat ends after " + combatSeconds + " seconds without qualifying PvP damage.",
                    "Dormant: Strength I and Speed I. Imperfect/Perfect: Strength II, Speed II, and Fire Resistance. Ascendant: kill-scaled Strength up to IV, Speed II, and Fire Resistance.",
                    "The second Totem pop ends the ultimate without bypassing cooldown. Food is blocked only during an active Imperfect ultimate; Perfect and Ascendant users can eat normally.");
            case "grim-reaper" -> new AbilityDetails(
                    "Grim Reaper — Passive", "Vanguard's separate kill-based Strength progression.",
                    "Passive; each qualifying player kill adds one Strength level.", "None.",
                    "Until Vanguard dies, including after BLOOD BLOOD BLOOD ends.",
                    "Strength up to IV.", "Mob, self, environmental, and ineligible kills do not count.");
            case "predators-instinct" -> new AbilityDetails(
                    "Predator's Instinct — Counter", "Phantom's timed counter and repositioning ability.",
                    "Right-click a sword to arm for 1 second, then crouch as an attack lands.",
                    "20 seconds after triggering.", "Blindness and movement stun last 5 seconds.",
                    "The triggering hit deals zero damage. With a mace anywhere in inventory, Phantom teleports above the attacker; otherwise behind them.",
                    "One trigger per activation. The stunned attacker may still attack. Ascendant Into the Void adds Afterimage.");
            case "marked-prey" -> new AbilityDetails(
                    "Marked Prey — Passive", "Attackers who hit Phantom receive Glowing.",
                    "Passive; no activation input.", "None.", "6 seconds Dormant, 9 Imperfect, 15 Perfect.",
                    "Tracks each attacker separately.", "No directional tracking or Ascendant upgrade.");
            case "into-the-void" -> new AbilityDetails(
                    "Into the Void — Ultimate", "Phantom stealth ultimate.",
                    "Left-click an Ender Pearl; the normal pearl throw is cancelled.", "5 minutes.",
                    "5 seconds Dormant, 15 seconds Imperfect, and 25 seconds Perfect/Ascendant.",
                    "Invisibility and Speed I throughout. The first hit dealt to a living target ends the ultimate and deals 1.1x damage at Imperfect, 1.25x at Perfect, or 1.5x at Ascendant; Dormant has no attack bonus. Taking a hit also ends it immediately. If Ascendant expires without either side interacting through a hit, it grants Speed IV for 15 seconds. Ascendant also adds Afterimage: Speed III for 12 seconds after a successful Predator's Instinct counter.",
                    "Clicking the Ender Pearl again while active manually ends the ultimate; any damage hit ends it immediately.");
            case "afterimage" -> new AbilityDetails(
                    "Afterimage — Ascendant Bonus", "Ascendant bonus attached to Phantom's Into the Void ultimate.",
                    "Automatically after a successful Predator's Instinct counter.", "Uses Predator's Instinct's activation and cooldown.",
                    "12 seconds.", "Speed III.", "Only available while Into the Void is Ascendant; not separately upgradeable.");
            case "unshaken" -> new AbilityDetails(
                    "Unshaken — Passive", "Warden knockback resistance.",
                    "Passive while Warden is selected.", "None.", "While Warden is selected.",
                    "20% Dormant, 30% Imperfect, and 40% Perfect knockback resistance.",
                    "Real knockback resistance, not damage reduction. No Ascendant upgrade.");
            case "sculk-pulse" -> new AbilityDetails(
                    "Sculk Pulse — Active", "Reveals nearby enemy players.",
                    "Right-click a sword.", "20 seconds.", "Glowing lasts 3 seconds.",
                    "Enemy players within 20 blocks receive Glowing.", "Allies and the Warden are excluded.");
            case "ancient-guardian" -> new AbilityDetails(
                    "Ancient Guardian — Ultimate", "Warden area-control ultimate with capture and team effects.",
                    "Left-click a shield.", "15 minutes.",
                    "Until combat ends; effect durations cycle 3 seconds, 2 seconds, 1 second, then repeat.",
                    "Capture radius: 30 blocks Dormant/Imperfect, 40 Perfect, 50 Ascendant. Warden/allies receive Resistance II; enemies Weakness I. Captured enemies share a fixed 100-block escape radius. Cards add 0.01x damage up to 1.5x.",
                    "Captured enemies can move, mine, build, and interact. Reactivation refreshes and resets cards.");
            case "unyielding" -> new AbilityDetails(
                    "Unyielding — Ascendant Bonus", "Ancient Guardian's Ascendant emergency defense.",
                    "Triggers at half health or less while Ascendant Ancient Guardian is active.",
                    "Uses Ancient Guardian's cooldown.", "The rest of the ultimate, ending when it ends or Warden dies.",
                    "60% damage reduction; Warden becomes immobile and cannot perform actions. If Warden dies, affected allies die too.",
                    "Healing does not restore mobility. Allies move and attack normally and do not receive Unyielding.");
            default -> throw new IllegalArgumentException("Unknown ability: " + ability);
        };
    }

    private void legacyAbilityDescription(CommandSender sender, String[] args, String ability) {
        if (args.length != 1) {
            String usage = "/venmark ability " + (ability.equals("ultimate") ? "blood-blood-blood" : ability);
            sender.sendMessage(plugin.message("&eUsage: " + usage));
            return;
        }
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
                        + "s; Imperfect: " + plugin.getConfig().getLong("vanguard.bloodthirst.imperfect-window-ms", 1000) / 1000.0
                        + "s; Perfect: " + plugin.getConfig().getLong("vanguard.bloodthirst.perfect-window-ms", 1250) / 1000.0
                        + "s; Ascendant: " + plugin.getConfig().getLong("vanguard.bloodthirst.ascendant-window-ms", 1500) / 1000.0
                        + "s. The hit still deals damage; a successful counter heals to full. Cooldown: "
                        + plugin.getConfig().getLong("vanguard.bloodthirst.cooldown-seconds", 45)
                        + "s from arming, reset on a successful counter.";
            }
            case "into-the-void" -> {
                title = "Into the Void â€” Ultimate";
                description = "Left-click with an Ender Pearl for Invisibility and Speed I. Attacking breaks invisibility; it returns after 3 seconds while combat continues. Perfect grants Speed II. The first eligible hit within 30 seconds consumes a one-use bonus: 1.1x Dormant, 1.15x Imperfect, 1.3x Perfect, or 1.5x Ascendant when Phantom is below 10 hearts. Ascendant also grants Speed IV after 30 seconds without landing a hit. Cooldown: 5 minutes.";
            }
            case "predators-instinct" -> {
                title = "Predator's Instinct — Counter";
                description = "Right-click with a sword to arm for 1 second, then crouch as an attack lands. The counter nullifies that hit, blinds and stuns the attacker for 5 seconds, and has a 20-second cooldown. Ascendant Into the Void grants Afterimage: Speed III for 12 seconds after a successful counter.";
            }
            case "marked-prey" -> {
                title = "Marked Prey — Passive";
                description = "Attackers who hit Phantom receive Glowing for 6 seconds Dormant, 9 seconds Imperfect, and 15 seconds Perfect. It provides no directional tracking.";
            }
            case "grim-reaper" -> {
                title = "Grim Reaper — Passive";
                description = "Qualifying player kills build Strength up to Strength IV. Strength progression persists after BLOOD BLOOD BLOOD ends and resets on death.";
            }
            case "afterimage" -> {
                title = "Afterimage — Ascendant Bonus";
                description = "A successful Predator's Instinct counter grants Speed III for 12 seconds while Into the Void is Ascendant.";
            }
            case "unshaken" -> {
                title = "Unshaken — Passive";
                description = "Warden knockback resistance is 20% Dormant, 30% Imperfect, and 40% Perfect.";
            }
            case "sculk-pulse" -> {
                title = "Sculk Pulse — Active";
                description = "Right-click a sword to reveal enemy players within 20 blocks for 3 seconds. Cooldown: 20 seconds.";
            }
            case "ancient-guardian" -> {
                title = "Ancient Guardian — Ultimate";
                description = "Left-click with a shield to capture enemies in a 30-, 40-, or 50-block radius. It grants Warden/allies Resistance II and enemies Weakness I. Cooldown: 15 minutes.";
            }
            case "unyielding" -> {
                title = "Unyielding — Ascendant Bonus";
                description = "At half health during Ascendant Ancient Guardian, Warden takes 60% less damage and becomes immobile for the rest of the ultimate.";
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
        if (sender instanceof Player player && "vanguard".equals(plugin.selectedClass(player.getUniqueId()))
                && List.of("berserker", "bloodthirst", "ultimate").contains(ability)) {
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
            String ability = tier == 3
                    ? (clazz.equals("vanguard") ? "ultimate" : "class_relic")
                    : (clazz.equals("vanguard") ? "select" : "class_relic");
            giveRelic(sender, target, clazz, ability, tier);
            return;
        }
        // Keep the previous command form working for existing staff workflows.
        if (root.equals("relic") && args.length == 6 && args[1].equalsIgnoreCase("give")) {
            if (!allowed(sender, "venmark.relic.give")) return;
            OfflinePlayer target = target(args[2]); String clazz = args[3].toLowerCase(Locale.ROOT);
            String ability = args[4].toLowerCase(Locale.ROOT); Integer tier = relicTierOrInteger(args[5]);
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
        if (online == null) online = plugin.getServer().getPlayer(name);
        if (online != null) return online;
        OfflinePlayer offline = plugin.getServer().getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline : null;
    }
    private Long positive(String value) { try { long n = Long.parseLong(value); return n > 0 ? n : null; } catch (NumberFormatException ex) { return null; } }
    private Integer integer(String value) { try { return Integer.valueOf(value); } catch (NumberFormatException ex) { return null; } }
    private Long denomination(String value) { return switch (value.toLowerCase(Locale.ROOT)) { case "dye", "purple-dye" -> CurrencyService.DYE_VALUE; case "star", "nether-star" -> CurrencyService.STAR_VALUE; default -> null; }; }
    private Integer relicTier(String value) { return switch (value.toLowerCase(Locale.ROOT)) { case "imperfect" -> 1; case "perfect" -> 2; case "ascendant" -> 3; default -> null; }; }
    private Integer relicTierOrInteger(String value) {
        Integer named = relicTier(value);
        return named != null ? named : integer(value);
    }
    private void giveRelic(CommandSender sender, OfflinePlayer target, String clazz, String ability, int tier) {
        Player recipient = target.getPlayer();
        ItemStack relic;
        try { relic = relics.createRelic(clazz, ability, tier); }
        catch (IllegalArgumentException ex) {
            sender.sendMessage(plugin.message("&cInvalid relic class, ability, or tier."));
            return;
        }
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
        sender.sendMessage(plugin.message("&7Abilities: /venmark ability <ability-name> (" + String.join(", ", ABILITY_NAMES) + ")"));
        sender.sendMessage(plugin.message("&7Economy: /deposit <amount|hand|offhand> | /withdraw <amount> | /bounty <player>"));
        sender.sendMessage(plugin.message("&7Admin: /venmark marks give|reset, currency give, relic give, class set, marks abuse"));
    }

    @Override public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 1) return List.of("choose", "deposit", "withdraw", "ability", "marks", "currency", "relic", "class");
        if (args.length >= 2 && args[0].equalsIgnoreCase("ability")) {
            if (!(source.getSender() instanceof Player player)) return List.of();
            String clazz = plugin.selectedClass(player.getUniqueId());
            if (clazz == null) return List.of();
            String prefix = String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                    .trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
            return abilitiesForClass(clazz).stream().filter(name -> name.startsWith(prefix)).toList();
        }
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
