package dev.prc.vendetta;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class PhysicalEconomyCommands implements BasicCommand {
    private final mark plugin;
    PhysicalEconomyCommands(mark plugin) { this.plugin = plugin; }

    @Override public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) { source.getSender().sendMessage("This command is for players."); return; }
        String name = source.getExecutor().getName().toLowerCase(Locale.ROOT);
        if (name.equals("deposit")) {
            if (args.length != 1) { player.sendMessage(plugin.message("&eUsage: /deposit <amount|hand|offhand>")); return; }
            if (!args[0].equalsIgnoreCase("hand") && !args[0].equalsIgnoreCase("offhand")) {
                Long amount = positive(args[0]);
                if (amount == null || amount % CurrencyService.DYE_VALUE != 0) { player.sendMessage(plugin.message("&cAmount must be a positive multiple of 10 Marks.")); return; }
                if (!plugin.depositFromInventory(player, amount)) { player.sendMessage(plugin.message("&cDeposit failed. Check authentic currency and bank capacity; nothing was consumed.")); return; }
                player.sendMessage(plugin.message("&aDeposited " + amount + " Marks. Bank balance: " + plugin.getMarks(player.getUniqueId()) + "."));
                return;
            }
            boolean offhand = args[0].equalsIgnoreCase("offhand");
            Long amount = plugin.depositHeldCurrency(player, offhand);
            if (amount == null) { player.sendMessage(plugin.message("&cThat hand does not hold genuine VenMark currency or your bank is full.")); return; }
            player.sendMessage(plugin.message("&aDeposited " + amount + " Marks. Bank balance: " + plugin.getMarks(player.getUniqueId()) + "."));
        } else if (name.equals("withdraw")) {
            if (args.length != 1) { player.sendMessage(plugin.message("&eUsage: /withdraw <amount>")); return; }
            Long amount = positive(args[0]);
            if (amount == null || amount % CurrencyService.DYE_VALUE != 0) { player.sendMessage(plugin.message("&cAmount must be a positive multiple of 10 Marks.")); return; }
            long fee = amount / 20 + (amount % 20 == 0 ? 0 : 1); // 5% rounded up to one whole Mark.
            if (plugin.withdrawPhysical(player, amount, fee))
                player.sendMessage(plugin.message("&aWithdrew " + amount + " Marks. Fee: " + fee + ". Total bank deduction: " + (amount + fee) + "."));
            else player.sendMessage(plugin.message("&cWithdrawal failed. Check your bank balance and inventory space; no changes were made."));
        } else {
            if (args.length != 1) { player.sendMessage(plugin.message("&eUsage: /bounty <player>")); return; }
            Player target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null) { player.sendMessage(plugin.message("&cTarget must be online.")); return; }
            if (target.equals(player)) { player.sendMessage(plugin.message("&cYou cannot place a bounty on yourself.")); return; }
            Long amount = plugin.postPhysicalBounty(player, target);
            if (amount == null) { player.sendMessage(plugin.message("&cBounty payment failed. Carry genuine VenMark currency; no items were consumed.")); return; }
            player.sendMessage(plugin.message("&dBounty posted on " + target.getName() + " for " + amount + " Marks."));
            target.sendMessage(plugin.message("&cYou are Marked. Current bounty: " + plugin.marksBounty(target.getUniqueId()) + " Marks."));
        }
    }

    private Long positive(String raw) { try { long n = Long.parseLong(raw); return n > 0 ? n : null; } catch (NumberFormatException ex) { return null; } }
    @Override public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String name = source.getExecutor().getName().toLowerCase(Locale.ROOT);
        if (args.length != 1) return List.of();
        if (name.equals("deposit")) return List.of("hand", "offhand", "10", "100", "1000");
        if (name.equals("withdraw")) return List.of("10", "100", "1000");
        return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
    }
    @Override public String permission() { return "venmark.use"; }
}
