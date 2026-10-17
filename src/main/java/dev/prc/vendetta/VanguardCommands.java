package dev.prc.vendetta;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

final class VanguardCommands implements BasicCommand {
    private final mark plugin;
    private final VanguardProgression progression;
    VanguardCommands(mark plugin, VanguardProgression progression) { this.plugin = plugin; this.progression = progression; }

    @Override public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) { sender.sendMessage("This command is for players."); return; }
        VanguardProgression.PlayerData data = progression.get(player.getUniqueId());
        if (args.length == 1 && args[0].equalsIgnoreCase("choose")) {
            player.sendMessage(plugin.message("&eClass selection is locked to /venmark choose <vanguard|phantom|warden>."));
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            if (!data.vanguard) { player.sendMessage(plugin.message("&7You have not selected Vanguard.")); return; }
            player.sendMessage(plugin.message("&6Vanguard &7| Berserker: &e" + data.berserker
                    + " &7| Bloodthirst: &e" + data.bloodthirst + " &7| BLOOD BLOOD BLOOD: &e" + data.ultimate
                    + " &7| Ultimate cooldown: &e" + Math.max(0, (data.ultimateReadyAt - System.currentTimeMillis() + 999) / 1000) + "s"));
            return;
        }
        player.sendMessage(plugin.message("&e/vanguard choose &7or &e/vanguard status&7. Right-click with a sword to arm Bloodthirst, then crouch when hit. Totem pops activate the ultimate when ready."));
    }

    @Override public String permission() {
        return "venmark.use";
    }
}
