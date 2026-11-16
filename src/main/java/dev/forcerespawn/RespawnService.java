package dev.forcerespawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Core recovery logic. All methods must be called on the main server thread.
 */
final class RespawnService {

    private final ForceRespawnPlugin plugin;
    private final Map<UUID, Long> lastUse = new HashMap<>();
    /** Targets currently being processed, so two staff members cannot overlap on one player. */
    private final java.util.Set<UUID> inProgress = new java.util.HashSet<>();

    // Optional Floodgate API (reflection so it is never a hard dependency).
    private Object floodgateApi;
    private Method floodgateIsPlayer;

    RespawnService(ForceRespawnPlugin plugin) {
        this.plugin = plugin;
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            floodgateApi = api.getMethod("getInstance").invoke(null);
            floodgateIsPlayer = api.getMethod("isFloodgatePlayer", UUID.class);
        } catch (Throwable ignored) {
            floodgateApi = null;
            floodgateIsPlayer = null;
        }
    }

    boolean floodgateAvailable() {
        return floodgateApi != null;
    }

    private String prefix() {
        return plugin.getConfig().getString("settings.bedrock-prefix", ".");
    }

    boolean isBedrock(Player p) {
        if (floodgateApi != null) {
            try {
                return (Boolean) floodgateIsPlayer.invoke(floodgateApi, p.getUniqueId());
            } catch (Throwable ignored) {
                // fall through to prefix check
            }
        }
        String pre = prefix();
        return !pre.isEmpty() && p.getName().startsWith(pre);
    }

    /** Exact (case-insensitive) match, also matching Floodgate names with the prefix omitted. */
    List<Player> findPlayers(String name) {
        List<Player> exact = new ArrayList<>();
        List<Player> unprefixed = new ArrayList<>();
        String pre = prefix();
        for (Player p : Bukkit.getOnlinePlayers()) {
            String n = p.getName();
            if (n.equalsIgnoreCase(name)) {
                exact.add(p);
            } else if (!pre.isEmpty() && n.startsWith(pre) && n.substring(pre.length()).equalsIgnoreCase(name)) {
                unprefixed.add(p);
            }
        }
        return exact.isEmpty() ? unprefixed : exact;
    }

    void recover(CommandSender sender, Player target, boolean force) {
        final String name = target.getName();
        final UUID id = target.getUniqueId();
        final String platform = plugin.msg(isBedrock(target) ? "platform-bedrock" : "platform-java");

        if (inProgress.contains(id)) {
            plugin.send(sender, "cooldown", "target", name, "seconds", "1");
            return;
        }

        if (!sender.hasPermission("forcerespawn.bypass-cooldown")) {
            long cooldownMs = plugin.getConfig().getLong("settings.cooldown-seconds", 5) * 1000L;
            Long last = lastUse.get(id);
            long now = System.currentTimeMillis();
            if (last != null && now - last < cooldownMs) {
                long left = (cooldownMs - (now - last) + 999) / 1000;
                plugin.send(sender, "cooldown", "target", name, "seconds", String.valueOf(left));
                return;
            }
        }

        try {
            if (target.isDead()) {
                plugin.send(sender, "respawn-started", "target", name, "platform", platform);
                lastUse.put(id, System.currentTimeMillis());
                inProgress.add(id);
                target.spigot().respawn();
                scheduleVerify(sender, target, 1);
            } else if (force || inVoid(target)) {
                plugin.send(sender, "respawn-started", "target", name, "platform", platform);
                lastUse.put(id, System.currentTimeMillis());
                inProgress.add(id);
                moveToSafe(target);
                scheduleResync(target, () -> finish(sender, target, "success-alive-recovered", true));
            } else {
                plugin.send(sender, "not-stuck", "target", name);
            }
        } catch (Throwable t) {
            inProgress.remove(id);
            plugin.send(sender, "failed-error", "target", name, "error", String.valueOf(t));
            log(sender, target, "FAILED with error: " + t);
        }
    }

    private void scheduleVerify(CommandSender sender, Player target, int attempt) {
        long delay = Math.max(1L, plugin.getConfig().getLong("settings.verify-delay-ticks", 20L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> verify(sender, target, attempt), delay);
    }

    private void verify(CommandSender sender, Player target, int attempt) {
        try {
            if (!target.isOnline()) {
                inProgress.remove(target.getUniqueId());
                plugin.send(sender, "failed-offline", "target", target.getName());
                log(sender, target, "FAILED: target went offline");
                return;
            }

            if (target.isDead()) {
                if (attempt < 2) {
                    target.spigot().respawn();
                    scheduleVerify(sender, target, attempt + 1);
                    return;
                }
                if (plugin.getConfig().getBoolean("settings.dead-fallback", true)) {
                    applyDeadFallback(target);
                    if (!target.isDead()) {
                        moveToSafe(target);
                        scheduleResync(target, () -> finish(sender, target, "success-fallback", true));
                        return;
                    }
                }
                inProgress.remove(target.getUniqueId());
                plugin.send(sender, "failed-still-dead", "target", target.getName());
                log(sender, target, "FAILED: still dead after all attempts");
                return;
            }

            // Respawned normally. Make sure they did not land somewhere unusable.
            if (inVoid(target)) moveToSafe(target);
            scheduleResync(target, () -> finish(sender, target, "success-respawned", false));
        } catch (Throwable t) {
            inProgress.remove(target.getUniqueId());
            plugin.send(sender, "failed-error", "target", target.getName(), "error", String.valueOf(t));
            log(sender, target, "FAILED with error: " + t);
        }
    }

    /**
     * Last resort for a player whose server-side state remains "dead" after respawn() was
     * called twice. Restores health directly. May not clear every state on every server build.
     */
    @SuppressWarnings("deprecation")
    private void applyDeadFallback(Player target) {
        try {
            target.setHealth(target.getMaxHealth());
        } catch (Throwable t) {
            plugin.getLogger().warning("Dead fallback setHealth failed for " + target.getName() + ": " + t);
        }
    }

    /** Runs a couple of ticks later so position/inventory packets go out after the respawn packet. */
    private void scheduleResync(Player target, Runnable then) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                if (target.isOnline()) {
                    target.setFallDistance(0f);
                    target.setVelocity(new Vector(0, 0, 0));
                    // Re-sends position to the client (what unfreezes a desynced Bedrock client).
                    target.teleport(target.getLocation());
                    target.updateInventory();
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Resync step failed for " + target.getName() + ": " + t);
            }
            then.run();
        }, 2L);
    }

    private void finish(CommandSender sender, Player target, String messageKey, boolean fallbackOrAlive) {
        inProgress.remove(target.getUniqueId());
        if (!target.isOnline()) {
            plugin.send(sender, "failed-offline", "target", target.getName());
            log(sender, target, "FAILED: target went offline");
            return;
        }
        if (target.isDead()) {
            plugin.send(sender, "failed-still-dead", "target", target.getName());
            log(sender, target, "FAILED: still dead");
            return;
        }
        plugin.send(sender, messageKey, "target", target.getName());
        plugin.send(target, "notify-target");
        log(sender, target, "SUCCESS (" + messageKey + ")");
    }

    private void log(CommandSender sender, Player target, String result) {
        if (!plugin.getConfig().getBoolean("settings.log-actions", true)) return;
        Location l = target.getLocation();
        plugin.getLogger().info(sender.getName() + " ran forcerespawn on " + target.getName()
                + " [" + (isBedrock(target) ? "Bedrock" : "Java") + "] at "
                + (l.getWorld() == null ? "?" : l.getWorld().getName())
                + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + ": " + result);
    }

    private boolean inVoid(Player p) {
        Location l = p.getLocation();
        World w = l.getWorld();
        return w != null && l.getY() < w.getMinHeight();
    }

    private void moveToSafe(Player target) {
        Location dest = safeLocation(target);
        target.setFallDistance(0f);
        target.setVelocity(new Vector(0, 0, 0));
        target.teleport(dest);
    }

    private Location safeLocation(Player target) {
        Location loc = null;

        // Paper 1.20.4+: getRespawnLocation(); older: getBedSpawnLocation(). Reflection keeps one jar for both.
        try {
            Method m = Player.class.getMethod("getRespawnLocation");
            loc = (Location) m.invoke(target);
        } catch (Throwable ignored) {
            // not available on this build
        }
        if (loc == null) {
            try {
                @SuppressWarnings("deprecation")
                Location bed = target.getBedSpawnLocation();
                loc = bed;
            } catch (Throwable ignored) {
                // ignore
            }
        }

        if (loc == null || loc.getWorld() == null) {
            World w = null;
            String configured = plugin.getConfig().getString("settings.fallback-world", "");
            if (configured != null && !configured.isEmpty()) w = Bukkit.getWorld(configured);
            if (w == null) w = target.getWorld();
            if (w == null && !Bukkit.getWorlds().isEmpty()) w = Bukkit.getWorlds().get(0);
            loc = w.getSpawnLocation();
        }

        World w = loc.getWorld();
        if (loc.getY() <= w.getMinHeight()) {
            int y = w.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ()) + 1;
            loc = new Location(w, loc.getX(), y, loc.getZ(), loc.getYaw(), loc.getPitch());
        }
        return loc;
    }
}
