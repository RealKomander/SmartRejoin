package org.dristmine.smartRejoin;

import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class ReloadCommand implements SimpleCommand {

    private final SmartRejoin plugin;

    public ReloadCommand(SmartRejoin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        plugin.reload();
        invocation.source().sendMessage(Component.text("SmartRejoin configuration has been reloaded.", NamedTextColor.GREEN));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("smartrejoin.command.reload");
    }
}
