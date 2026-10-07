package dev.exoquests.paper;

import static dev.exoquests.paper.text.TextService.n;
import static dev.exoquests.paper.text.TextService.p;

import dev.exoquests.core.quest.QuestDefinition;
import dev.exoquests.core.quest.QuestProgressService;
import dev.exoquests.core.storage.CompletionResult;
import dev.exoquests.paper.text.TextService;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Turns progress events into chat, action bar and sound feedback. Runs on the main thread. */
final class ProgressNotifier implements QuestProgressService.Listener {

    private final ExoQuestsPlugin plugin;

    ProgressNotifier(ExoQuestsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onLoaded(UUID uuid, boolean reset) {
        if (plugin.isShuttingDown()) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        if (reset) {
            plugin.text().send(player, "quest.reset");
            plugin.text().play(player, "reset");
        }
        plugin.menus().refresh(player);
    }

    @Override
    public void onCompleted(UUID uuid, Optional<QuestDefinition> definition, CompletionResult result, boolean online) {
        String questName = definition.map(QuestDefinition::name).orElse(result.questId());
        plugin.getLogger().info("Quest completed: " + uuid + " " + result.questId() + " (+" + result.pointsAwarded()
                + " points, completion " + result.completionId() + ")");
        if (plugin.isShuttingDown()) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        String category = definition.flatMap(d -> plugin.configs().current().quests().category(d.category()))
                .map(c -> c.displayName()).orElse("");
        TextService text = plugin.text();
        text.send(player, "quest.completed", p("quest", questName), n("points", result.pointsAwarded()),
                n("balance", result.balance()), p("category", category));
        text.actionBar(player, "quest.completed-actionbar", p("quest", questName),
                n("points", result.pointsAwarded()), n("balance", result.balance()));
        if (result.pointsAwarded() < result.reward()) {
            text.send(player, "quest.completed-capped", n("points", result.pointsAwarded()));
        }
        text.play(player, "quest-complete");
        plugin.menus().refresh(player);
    }

    @Override
    public void onError(String message, Throwable error) {
        plugin.getLogger().log(Level.SEVERE, message, error);
    }
}
