package dev.forcerespawn;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ForceRespawnPlugin extends JavaPlugin implements TabExecutor {

    private RespawnService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        service = new RespawnService(this);
        PluginCommand cmd = getCommand("forcerespawn");
        if (cmd == null) {
            getLogger().severe("Command 'forcerespawn' missing from plugin.yml; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
        getLogger().info("Enabled. Floodgate API " + (service.floodgateAvailable() ? "detected." : "not detected (using name prefix detection)."));
    }

    /** Colourised message from config with placeholders replaced. */
    String msg(String key, String... pairs) {
        String raw = getConfig().getString("messages." + key, "&cMissing message: " + key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            raw = raw.replace("%" + pairs[i] + "%", pairs[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    void send(CommandSender to, String key, String... pairs) {
        if (to == null) return;
        String prefix = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix", ""));
        to.sendMessage(prefix + msg(key, pairs));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("forcerespawn.use")) {
            send(sender, "no-permission");
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            send(sender, "usage", "label", label);
            return true;
        }

        boolean force = false;
        if (args.length == 2) {
            if (!args[1].equalsIgnoreCase("force")) {
                send(sender, "usage", "label", label);
                return true;
            }
            if (!sender.hasPermission("forcerespawn.force")) {
                send(sender, "no-force-permission");
                return true;
            }
            force = true;
        }

        // Target is ALWAYS resolved from the argument; never falls back to the sender.
        String wanted = args[0];
        List<Player> matches = service.findPlayers(wanted);
        if (matches.isEmpty()) {
            send(sender, "player-not-found", "target", wanted);
            return true;
        }
        if (matches.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (Player p : matches) {
                if (names.length() > 0) names.append(", ");
                names.append(p.getName());
            }
            send(sender, "ambiguous-player", "target", wanted, "matches", names.toString());
            return true;
        }

        service.recover(sender, matches.get(0), force);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("forcerespawn.use")) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        } else if (args.length == 2 && sender.hasPermission("forcerespawn.force")) {
            if ("force".startsWith(args[1].toLowerCase(Locale.ROOT))) out.add("force");
        }
        return out;
    }
}
