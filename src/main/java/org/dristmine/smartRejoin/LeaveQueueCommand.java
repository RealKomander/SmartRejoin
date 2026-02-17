package org.dristmine.smartRejoin;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Command for players to manually leave the rejoin queue.
 */
public class LeaveQueueCommand implements SimpleCommand {

    private final SmartRejoin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public LeaveQueueCommand(SmartRejoin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        // Check if sender is a player
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("This command is player-only."));
            return;
        }

        RejoinQueueManager queueManager = plugin.getRejoinQueueManager();
        if (queueManager == null) {
            // Rejoin queue is not enabled
            player.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getRejoinQueueLeaveMessageNotInQueue()));
            return;
        }

        // Try to remove player from queue
        String serverName = queueManager.removePlayerFromQueueManually(player.getUniqueId());

        if (serverName != null) {
            // Player was in a queue - send confirmation
            String message = plugin.getConfigManager().getRejoinQueueLeaveMessageLeft()
                    .replace("{server}", serverName);
            player.sendMessage(miniMessage.deserialize(message));
        } else {
            // Player was not in any queue
            player.sendMessage(miniMessage.deserialize(plugin.getConfigManager().getRejoinQueueLeaveMessageNotInQueue()));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // No permission - any player can use this command
        return true;
    }
}
