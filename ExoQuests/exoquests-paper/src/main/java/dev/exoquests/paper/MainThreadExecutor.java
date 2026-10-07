package dev.exoquests.paper;

import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

/**
 * Hands work to the server main thread. During shutdown the scheduler no longer accepts tasks while the
 * main thread is blocked waiting for the final database flush, so callbacks then run inline instead.
 */
public final class MainThreadExecutor implements Executor {

    private final Plugin plugin;
    private volatile boolean inline;

    public MainThreadExecutor(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Switch to inline execution; only used while the plugin is disabling. */
    public void enterShutdownMode() {
        inline = true;
    }

    @Override
    public void execute(Runnable task) {
        if (inline) {
            task.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException e) {
            task.run();
        }
    }
}
