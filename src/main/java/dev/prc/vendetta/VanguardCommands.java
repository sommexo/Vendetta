package dev.prc.vendetta;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class VanguardCommands implements CommandExecutor {
    private final mark plugin;
    private final VanguardProgression progression;
    VanguardCommands(mark plugin, VanguardProgression progression) { this.plugin = plugin; this.progression = progression; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("This command is for players."); return true; }
        VanguardProgression.PlayerData data = progression.get(player.getUniqueId());
        if (args.length == 1 && args[0].equalsIgnoreCase("choose")) {
            if (data.vanguard) { player.sendMessage(plugin.message("&cYou have already chosen Vanguard.")); return true; }
            data.vanguard = true;
            progression.save(player.getUniqueId());
            player.sendMessage(plugin.message("&cVanguard selected. Your abilities are unlocked."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            if (!data.vanguard) { player.sendMessage(plugin.message("&7You have not chosen a class. Use /vanguard choose.")); return true; }
            player.sendMessage(plugin.message("&6Vanguard &7| Berserker: &e" + data.berserker
                    + " &7| Headweb: &e" + data.headweb + " &7| Ultimate: &e" + data.ultimate
                    + " &7| Ultimate cooldown: &e" + Math.max(0, (data.ultimateReadyAt - System.currentTimeMillis() + 999) / 1000) + "s"));
            return true;
        }
        player.sendMessage(plugin.message("&e/vanguard choose &7or &e/vanguard status&7. Ultimate: right-click with a sword."));
        return true;
    }
}
