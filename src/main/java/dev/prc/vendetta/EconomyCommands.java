package dev.prc.vendetta;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

final class EconomyCommands implements BasicCommand {
    private final mark plugin;
    private final String commandName;

    EconomyCommands(mark plugin, String commandName) {
        this.plugin = plugin;
        this.commandName = commandName;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        plugin.handleCommand(source.getSender(), commandName, args);
    }

    @Override
    public String permission() {
        return "venmark.use";
    }
}
