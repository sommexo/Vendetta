package dev.prc.vendetta;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;

import java.util.Collection;
import java.util.List;

final class VendettaCommands implements BasicCommand {
    private final mark plugin;

    VendettaCommands(mark plugin) { this.plugin = plugin; }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission("venmark.admin")) {
            sender.sendMessage(plugin.message("&cYou don't have permission: venmark.admin"));
            return;
        }
        if (args.length != 3 || !args[0].equalsIgnoreCase("settings")
                || !(args[1].equalsIgnoreCase("opCooldownBypass") || args[1].equalsIgnoreCase("classChanger"))
                || !(args[2].equalsIgnoreCase("true") || args[2].equalsIgnoreCase("false"))) {
            sender.sendMessage(plugin.message("&eUsage: /vendetta settings <opCooldownBypass|classChanger> <true|false>"));
            return;
        }
        boolean enabled = Boolean.parseBoolean(args[2]);
        if (args[1].equalsIgnoreCase("classChanger")) {
            if (!plugin.setClassChanger(enabled)) {
                sender.sendMessage(plugin.message("&cCould not save VenMark settings; the previous value remains active."));
                return;
            }
            plugin.audit("settings classChanger=" + enabled + " changed by " + sender.getName());
            sender.sendMessage(plugin.message("&aClass changing " + (enabled ? "enabled" : "disabled") + " and saved."));
            return;
        }
        if (!plugin.setOpCooldownBypass(enabled)) {
            sender.sendMessage(plugin.message("&cCould not save VenMark settings; the previous value remains active."));
            return;
        }
        plugin.audit("settings opCooldownBypass=" + enabled + " changed by " + sender.getName());
        sender.sendMessage(plugin.message("&aOperator Vanguard cooldown bypass " + (enabled ? "enabled" : "disabled") + " and saved."));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 1) return List.of("settings");
        if (args.length == 2 && args[0].equalsIgnoreCase("settings")) return List.of("opCooldownBypass", "classChanger");
        if (args.length == 3 && args[0].equalsIgnoreCase("settings") && List.of("opCooldownBypass", "classChanger").contains(args[1]))
            return List.of("true", "false");
        return List.of();
    }

    @Override public boolean canUse(CommandSender sender) { return true; }
    @Override public String permission() { return null; }
}
