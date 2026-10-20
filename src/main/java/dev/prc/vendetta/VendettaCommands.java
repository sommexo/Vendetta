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
        if (args.length == 1 && args[0].equalsIgnoreCase("angelEscape")) {
            if (!plugin.triggerableAngelEscapes()) {
                sender.sendMessage(plugin.message("&cManual Angel escapes are disabled. Enable them with /vendetta settings triggerableAngelEscapes true"));
                return;
            }
            if (!plugin.triggerAngelEscape()) {
                sender.sendMessage(plugin.message("&cAngel encounter service is not ready."));
                return;
            }
            plugin.audit("manual Angel escape triggered by " + sender.getName());
            sender.sendMessage(plugin.message("&aManual Angel escape wave triggered."));
            return;
        }
        if (args.length != 3 || !args[0].equalsIgnoreCase("settings")
                || !(args[1].equalsIgnoreCase("opCooldownBypass")
                || args[1].equalsIgnoreCase("classChanger")
                || args[1].equalsIgnoreCase("triggerableAngelEscapes"))
                || !(args[2].equalsIgnoreCase("true") || args[2].equalsIgnoreCase("false"))) {
            sender.sendMessage(plugin.message("&eUsage: /vendetta settings <opCooldownBypass|classChanger|triggerableAngelEscapes> <true|false> or /vendetta angelEscape"));
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
        if (args[1].equalsIgnoreCase("triggerableAngelEscapes")) {
            if (!plugin.setTriggerableAngelEscapes(enabled)) {
                sender.sendMessage(plugin.message("&cCould not save VenMark settings; the previous value remains active."));
                return;
            }
            plugin.audit("settings triggerableAngelEscapes=" + enabled + " changed by " + sender.getName());
            sender.sendMessage(plugin.message("&aManual Angel escapes " + (enabled ? "enabled" : "disabled") + " and saved."));
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
        if (args.length == 1) return List.of("settings", "angelEscape");
        if (args.length == 2 && args[0].equalsIgnoreCase("settings")) return List.of("opCooldownBypass", "classChanger", "triggerableAngelEscapes");
        if (args.length == 3 && args[0].equalsIgnoreCase("settings") && List.of("opCooldownBypass", "classChanger", "triggerableAngelEscapes").contains(args[1]))
            return List.of("true", "false");
        return List.of();
    }

    @Override public boolean canUse(CommandSender sender) { return true; }
    @Override public String permission() { return null; }
}
