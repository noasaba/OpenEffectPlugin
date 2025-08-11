package net.example.openeffect;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class OpenEffectPlugin extends JavaPlugin implements Listener, TabExecutor {

    private final Set<UUID> enabled = new HashSet<>();
    private DisplayManager displays;

    @Override
    public void onEnable() {
        try {
            try { saveDefaultConfig(); } catch (Throwable ignore) {
                if (!getDataFolder().exists()) getDataFolder().mkdirs();
                saveConfig();
            }
            loadEnabled();

            displays = new DisplayManager(this); // このプラグイン本体を渡す

            PluginCommand cmd = getCommand("open");
            if (cmd == null) {
                getLogger().severe("command 'open' not found. Check plugin.yml!");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);

            // 管理者はデフォでON（enabledへ自動追加）
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("openeffect.admin")) enabled.add(p.getUniqueId());
            }

            displays.ensureAllTargets();
            for (Player viewer : Bukkit.getOnlinePlayers()) applyVisibilityFor(viewer);

            int period = Math.max(1, getConfig().getInt("updateTicks", 1));
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                try {
                    displays.ensureAllTargets();
                    displays.updateAll();
                } catch (Throwable t) {
                    getLogger().severe("Update task failed: " + t);
                    t.printStackTrace();
                }
            }, period, period);

            Bukkit.getPluginManager().registerEvents(this, this);
            getLogger().info("OpenEffect enabled.");
        } catch (Throwable t) {
            getLogger().severe("Enable failed: " + t);
            t.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (displays != null) displays.despawnAll();
            saveEnabled();
        } catch (Throwable t) {
            getLogger().severe("Disable failed: " + t);
            t.printStackTrace();
        }
        getLogger().info("OpenEffect disabled.");
    }

    // ===== Events =====
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("openeffect.admin")) {
            enabled.add(p.getUniqueId()); // 管理者は初期ON
            saveEnabled();
        }
        displays.ensureTarget(p);
        for (Player viewer : Bukkit.getOnlinePlayers()) applyVisibilityFor(viewer);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        displays.removeTarget(e.getPlayer().getUniqueId());
        saveEnabled();
    }

    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (!getConfig().getBoolean("updateOnMove", true)) return;
        if (e.getFrom().toVector().distanceSquared(e.getTo().toVector()) < 1.0E-6) return;
        displays.updateOne(e.getPlayer());
    }

    // ===== Visibility helper =====
    private void applyVisibilityFor(Player viewer) {
        displays.applyVisibility(viewer, canUse(viewer));
    }

    /** 権限 + ON（enabledに含まれる）で可視。OFF なら管理者でも非表示にする */
    public boolean canUse(Player p) {
        boolean hasPerm = p.hasPermission("openeffect.admin") || p.hasPermission("openeffect.toggle");
        return hasPerm && enabled.contains(p.getUniqueId());
    }

    // ===== /open effect on|off =====
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        try {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "ゲーム内で実行してください。");
                return true;
            }
            Player p = (Player) sender;

            if (!p.hasPermission("openeffect.admin") && !p.hasPermission("openeffect.toggle")) {
                p.sendMessage(ChatColor.RED + "このコマンドを実行する権限がありません。");
                return true;
            }
            if (args.length != 2 || !args[0].equalsIgnoreCase("effect")) {
                p.sendMessage(ChatColor.RED + "使い方: /" + label + " effect <on|off>");
                return true;
            }

            if (args[1].equalsIgnoreCase("on")) {
                enabled.add(p.getUniqueId());
                saveEnabled();
                displays.applyVisibility(p, true);   // 即表示
                p.sendMessage(ChatColor.AQUA + "エフェクト表示を " + ChatColor.GREEN + "ON" + ChatColor.AQUA + " にしました。");
                return true;
            }
            if (args[1].equalsIgnoreCase("off")) {
                enabled.remove(p.getUniqueId());
                saveEnabled();
                displays.applyVisibility(p, false);  // 即非表示
                p.sendMessage(ChatColor.AQUA + "エフェクト表示を " + ChatColor.YELLOW + "OFF" + ChatColor.AQUA + " にしました。");
                return true;
            }

            p.sendMessage(ChatColor.RED + "使い方: /" + label + " effect <on|off>");
            return true;
        } catch (Throwable t) {
            getLogger().severe("Command error: " + t);
            t.printStackTrace();
            if (sender instanceof Player) {
                ((Player) sender).sendMessage(ChatColor.RED + "内部エラー: " + t.getClass().getSimpleName());
            }
            return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, org.bukkit.command.Command c, String a, String[] args) {
        if (args.length == 1) return Collections.singletonList("effect");
        if (args.length == 2 && "effect".equalsIgnoreCase(args[0])) return Arrays.asList("on","off");
        return Collections.emptyList();
    }

    private void loadEnabled() {
        List<String> list = getConfig().getStringList("enabled");
        enabled.clear();
        enabled.addAll(list.stream().map(UUID::fromString).collect(Collectors.toSet()));
    }

    private void saveEnabled() {
        getConfig().set("enabled", enabled.stream().map(UUID::toString).collect(Collectors.toList()));
        saveConfig();
    }
}
