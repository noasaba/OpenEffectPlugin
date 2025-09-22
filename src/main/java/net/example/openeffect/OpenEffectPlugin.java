package net.example.openeffect;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理GUI + 管理者のみの表示設定（/openeffect config）。
 *
 * 変更点:
 * - 一般権限ではヒント表示もコマンドも不可（openeffect.admin のみ）
 * - ログアウト時に頭上TextDisplayが残る不具合を修正（Quitでremove、定期掃除も追加）
 * - バージョン 1.1.1
 */
public class OpenEffectPlugin extends JavaPlugin implements Listener, TabExecutor {

    // === タイトル ===
    private static final String GUI_TITLE_MAIN    = "OpenEffect 設定";
    private static final String GUI_TITLE_ADMIN   = "OpenEffect 管理";
    private static final String GUI_TITLE_EFFECT_PREFIX = "効果: ";

    // === 管理GUI設定 ===
    private static final int PAGE_SIZE = 45; // 5行 x 9列

    // === 表示状態保存（管理者のみ操作可） ===
    private final Set<UUID> enabledOverhead = new HashSet<>();
    private final Set<UUID> enabledHud      = new HashSet<>();

    // === 実体 ===
    private DisplayManager displays;

    // === 周期 ===
    private int updateTicks;     // 頭上TextDisplay更新
    private int hudUpdateTicks;  // HUD(ActionBar)更新

    // === 管理GUI状態 ===
    private final Map<UUID, UUID> openEffectView = new HashMap<>(); // viewer -> target
    private final Map<UUID, Inventory> openEffectInv = new HashMap<>();
    private final Map<UUID, Integer> adminPage = new HashMap<>();
    private NamespacedKey KEY_TARGET_UUID;

    @Override
    public void onEnable() {
        try {
            try { saveDefaultConfig(); } catch (Throwable ignore) {
                if (!getDataFolder().exists()) getDataFolder().mkdirs();
                saveConfig();
            }
            // 周期設定
            updateTicks    = Math.max(1,  getConfig().getInt("updateTicks", 1));
            hudUpdateTicks = Math.max(10, getConfig().getInt("hudUpdateTicks", 40));

            loadEnabledSets();

            displays = new DisplayManager(this);
            KEY_TARGET_UUID = new NamespacedKey(this, "target");

            // コマンド登録（openeffect / open）
            registerCmd("openeffect");
            registerCmd("open");

            Bukkit.getPluginManager().registerEvents(this, this);

            // 既存オンラインに対し、admin は Overhead 初期ON（なければ追加）
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("openeffect.admin")) {
                    enabledOverhead.add(p.getUniqueId());
                }
            }
            saveEnabledSets();

            // 初期可視適用
            displays.ensureAllTargets();
            for (Player viewer : Bukkit.getOnlinePlayers()) applyVisibilityFor(viewer);

            // 頭上ディスプレイ更新
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                try {
                    displays.ensureAllTargets();
                    displays.updateAll();
                } catch (Throwable t) {
                    getLogger().severe("Update task failed: " + t);
                    t.printStackTrace();
                }
            }, updateTicks, updateTicks);

            // HUD(ActionBar) 更新（管理者のみ）
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                try {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (canSeeHud(p)) {
                            var lines = displays.buildEffectLines(p);
                            p.sendActionBar(Component.text(lines.isEmpty() ? "" : String.join(" | ", lines)));
                        }
                    }
                } catch (Throwable t) {
                    getLogger().severe("HUD task failed: " + t);
                    t.printStackTrace();
                }
            }, hudUpdateTicks, hudUpdateTicks);

            getLogger().info("OpenEffect 1.1.1 enabled.");
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
            saveEnabledSets();
        } catch (Throwable t) {
            getLogger().severe("Disable failed: " + t);
            t.printStackTrace();
        }
        getLogger().info("OpenEffect disabled.");
    }

    private void registerCmd(String name) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    // ===== Events =====
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // 管理者のみ初期ON & ヒント表示
        if (p.hasPermission("openeffect.admin")) {
            enabledOverhead.add(p.getUniqueId());
            saveEnabledSets();
            p.sendMessage(ChatColor.GRAY + "[OpenEffect] " + ChatColor.AQUA + "/openeffect gui " + ChatColor.GRAY + "でプレイヤーの効果を閲覧できます。");
            p.sendMessage(ChatColor.GRAY + "[OpenEffect] " + ChatColor.AQUA + "/openeffect config " + ChatColor.GRAY + "で表示設定を切替できます。");
        }
        displays.ensureAllTargets();
        applyVisibilityFor(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // ログアウトしたターゲットの頭上テキストを即削除（残留対策）
        displays.removeTarget(e.getPlayer().getUniqueId());
        // ビューア側の管理GUI状態も掃除
        cleanupViewer(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!getConfig().getBoolean("updateOnMove", true)) return;
        if (e.getFrom().toVector().distanceSquared(e.getTo().toVector()) < 1.0E-6) return;
        displays.updateOne(e.getPlayer());
    }

    // ===== Visibility helper =====
    private void applyVisibilityFor(Player viewer) {
        displays.applyVisibility(viewer, canSeeOverhead(viewer));
    }
    /** Overhead（頭上表示）を見られるのは管理者のみ */
    public boolean canSeeOverhead(Player p) {
        return p.hasPermission("openeffect.admin") && enabledOverhead.contains(p.getUniqueId());
    }
    /** HUD（ActionBar）も管理者のみ */
    public boolean canSeeHud(Player p) {
        return p.hasPermission("openeffect.admin") && enabledHud.contains(p.getUniqueId());
    }

    // ===== /openeffect / open =====
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        try {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "ゲーム内で実行してください。");
                return true;
            }
            Player p = (Player) sender;

            // ルートでまず管理者チェック：一般権限は全サブコマンド不可
            if (!p.hasPermission("openeffect.admin")) {
                p.sendMessage(ChatColor.RED + "このコマンドを実行する権限がありません。");
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("config")) {
                openSettingsGui(p);
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("gui")) {
                openAdminGui(p, 0);
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                updateTicks    = Math.max(1,  getConfig().getInt("updateTicks", 1));
                hudUpdateTicks = Math.max(10, getConfig().getInt("hudUpdateTicks", 40));
                p.sendMessage(ChatColor.GREEN + "[OpenEffect] config reloaded.");
                return true;
            }

            // ヘルプ（管理者のみ見える）
            p.sendMessage(ChatColor.AQUA + "使い方:");
            p.sendMessage(ChatColor.GRAY + "/" + label + " config" + ChatColor.DARK_GRAY + " … 自分の表示設定（頭上/HUD）を切替");
            p.sendMessage(ChatColor.GRAY + "/" + label + " gui" + ChatColor.DARK_GRAY + " … 管理GUI（他人の効果閲覧）");
            p.sendMessage(ChatColor.GRAY + "/" + label + " reload" + ChatColor.DARK_GRAY + " … コンフィグ再読込");
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
        if (s instanceof Player && ((Player) s).hasPermission("openeffect.admin")) {
            if (args.length == 1) return Arrays.asList("config","gui","reload");
        }
        return Collections.emptyList();
    }

    // ===== GUI: 自分の設定（頭上/HUD） =====
    private void openSettingsGui(Player p) {
        Inventory inv = Bukkit.createInventory(p, 9 * 3, GUI_TITLE_MAIN);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler());

        boolean oh = enabledOverhead.contains(p.getUniqueId());
        boolean hd = enabledHud.contains(p.getUniqueId());
        inv.setItem(10, toggleItem(Material.LIME_CONCRETE, Material.RED_CONCRETE, oh,
                "頭上表示 (TextDisplay)", Arrays.asList(
                        ChatColor.GRAY + "プレイヤーの頭上に効果一覧を表示します。",
                        ChatColor.GRAY + "現在: " + (oh ? ChatColor.GREEN + "ON" : ChatColor.YELLOW + "OFF")
                )));
        inv.setItem(12, toggleItem(Material.LIGHT_BLUE_CONCRETE, Material.GRAY_CONCRETE, hd,
                "HUD (ActionBar)", Arrays.asList(
                        ChatColor.GRAY + "自分の画面下部(ActionBar)に効果一覧を表示します。",
                        ChatColor.GRAY + "現在: " + (hd ? ChatColor.GREEN + "ON" : ChatColor.YELLOW + "OFF")
                )));
        inv.setItem(14, simpleItem(Material.CHEST, ChatColor.AQUA + "管理GUIを開く",
                Arrays.asList(ChatColor.GRAY + "オンラインプレイヤーの効果を確認します。", ChatColor.DARK_GRAY + "/openeffect gui")));
        inv.setItem(16, simpleItem(Material.OAK_DOOR, ChatColor.RED + "閉じる",
                Collections.singletonList(ChatColor.DARK_GRAY + "クリックで閉じる")));

        p.openInventory(inv);
    }

    // ===== GUI: オンライン一覧（管理） =====
    private void openAdminGui(Player viewer, int page) {
        if (!viewer.hasPermission("openeffect.admin")) {
            viewer.sendMessage(ChatColor.RED + "権限がありません。");
            return;
        }
        adminPage.put(viewer.getUniqueId(), page);

        boolean includeSelf = getConfig().getBoolean("includeSelfInAdminList", true);

        List<Player> list = Bukkit.getOnlinePlayers().stream()
                .filter(p -> includeSelf || !p.getUniqueId().equals(viewer.getUniqueId()))
                .sorted(Comparator.comparing(OfflinePlayer::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .collect(Collectors.toList());

        int total = list.size();
        int maxPage = Math.max(0, (total - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, maxPage));

        int size = 54; // 6行固定（最下段に制御ボタン）
        Inventory inv = Bukkit.createInventory(viewer, size, GUI_TITLE_ADMIN);

        for (int i = 0; i < size; i++) inv.setItem(i, filler());

        int start = page * PAGE_SIZE;
        int end = Math.min(total, start + PAGE_SIZE);
        int slot = 0;
        for (int i = start; i < end; i++) {
            Player target = list.get(i);
            inv.setItem(slot++, headItem(target));
        }

        inv.setItem(45, simpleItem(Material.OAK_DOOR, ChatColor.GREEN + "閉じる", List.of(ChatColor.DARK_GRAY + "クリックで閉じる")));
        inv.setItem(49, simpleItem(Material.BOOK, ChatColor.AQUA + "ページ " + (page + 1) + "/" + (maxPage + 1), List.of(ChatColor.GRAY + "表示のみ")));
        if (page > 0) inv.setItem(48, simpleItem(Material.ARROW, ChatColor.YELLOW + "前のページ", Collections.emptyList()));
        if (page < maxPage) inv.setItem(50, simpleItem(Material.ARROW, ChatColor.YELLOW + "次のページ", Collections.emptyList()));

        viewer.openInventory(inv);
    }

    private ItemStack headItem(Player target) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) it.getItemMeta();
        sm.setOwningPlayer(target);
        sm.setDisplayName(ChatColor.AQUA + target.getName());
        List<String> lore = new ArrayList<>();
        int effCount = target.getActivePotionEffects().size();
        lore.add(ChatColor.GRAY + "効果数: " + ChatColor.WHITE + effCount);
        lore.add(ChatColor.DARK_GRAY + "クリックで効果を表示");
        sm.setLore(lore);
        sm.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        // ターゲットUUIDをPDCに埋める
        PersistentDataContainer pdc = sm.getPersistentDataContainer();
        pdc.set(KEY_TARGET_UUID, PersistentDataType.STRING, target.getUniqueId().toString());

        it.setItemMeta(sm);
        return it;
    }

    // ===== GUI: 効果一覧（管理） =====
    private void openEffectsGui(Player viewer, Player target) {
        String title = GUI_TITLE_EFFECT_PREFIX + target.getName();
        Inventory inv = Bukkit.createInventory(viewer, 54, title);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler());
        fillEffectsInventory(inv, target);

        inv.setItem(45, simpleItem(Material.ARROW, ChatColor.GREEN + "戻る", Collections.singletonList(ChatColor.DARK_GRAY + "一覧に戻る")));
        inv.setItem(53, simpleItem(Material.OAK_DOOR, ChatColor.RED + "閉じる", Collections.singletonList(ChatColor.DARK_GRAY + "クリックで閉じる")));

        openEffectView.put(viewer.getUniqueId(), target.getUniqueId());
        openEffectInv.put(viewer.getUniqueId(), inv);

        viewer.openInventory(inv);
    }

    private void fillEffectsInventory(Inventory inv, Player target) {
        inv.setItem(4, headItem(target)); // 目印
        var effects = target.getActivePotionEffects();
        int slot = 9; // 2段目から
        if (effects.isEmpty()) {
            inv.setItem(22, simpleItem(Material.PAPER, ChatColor.YELLOW + "効果なし",
                    Collections.singletonList(ChatColor.GRAY + "現在、付与されている効果はありません。")));
            return;
        }
        for (var eff : effects) {
            if (slot >= 9 + 45) break;
            String name = ChatColor.AQUA + displays.effectName(eff);
            int lv = eff.getAmplifier() + 1;
            int sec = Math.max(0, eff.getDuration() / 20);
            String m = String.format("%d:%02d", sec / 60, sec % 60);
            ItemStack it = simpleItem(Material.TIPPED_ARROW, name, List.of(
                    ChatColor.GRAY + "Lv " + lv,
                    ChatColor.GRAY + "残り " + m
            ));
            inv.setItem(slot++, it);
        }
    }

    // ===== クリック/クローズ処理 =====
    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player viewer = (Player) e.getWhoClicked();
        String title = e.getView().getTitle();

        if (GUI_TITLE_MAIN.equals(title)) {
            e.setCancelled(true);
            int slot = e.getRawSlot();
            if (!viewer.hasPermission("openeffect.admin")) return; // 念のため二重防御
            if (slot == 10) { toggleOverhead(viewer); openSettingsGui(viewer); return; }
            if (slot == 12) { toggleHud(viewer);      openSettingsGui(viewer); return; }
            if (slot == 14) { viewer.closeInventory(); viewer.performCommand("openeffect gui"); return; }
            if (slot == 16) { viewer.closeInventory(); return; }
            return;
        }

        if (GUI_TITLE_ADMIN.equals(title)) {
            e.setCancelled(true);
            int slot = e.getRawSlot();
            if (slot == 45) { viewer.closeInventory(); return; }
            if (slot == 48) { int page = adminPage.getOrDefault(viewer.getUniqueId(), 0); openAdminGui(viewer, Math.max(0, page - 1)); return; }
            if (slot == 50) { int page = adminPage.getOrDefault(viewer.getUniqueId(), 0); openAdminGui(viewer, page + 1); return; }

            ItemStack current = e.getCurrentItem();
            if (current != null && current.getType() == Material.PLAYER_HEAD) {
                ItemMeta im = current.getItemMeta();
                if (im instanceof SkullMeta) {
                    PersistentDataContainer pdc = im.getPersistentDataContainer();
                    String uuidStr = pdc.get(KEY_TARGET_UUID, PersistentDataType.STRING);
                    if (uuidStr != null) {
                        try {
                            UUID targetId = UUID.fromString(uuidStr);
                            Player target = Bukkit.getPlayer(targetId);
                            if (target != null && target.isOnline()) openEffectsGui(viewer, target);
                            else viewer.sendMessage(ChatColor.YELLOW + "そのプレイヤーはオフラインです。");
                        } catch (IllegalArgumentException ex) {
                            viewer.sendMessage(ChatColor.RED + "内部データが不正です。");
                        }
                    }
                }
            }
            return;
        }

        if (title.startsWith(GUI_TITLE_EFFECT_PREFIX)) {
            e.setCancelled(true);
            int slot = e.getRawSlot();
            if (slot == 45) { // 戻る
                cleanupViewer(viewer.getUniqueId());
                openAdminGui(viewer, adminPage.getOrDefault(viewer.getUniqueId(), 0));
                return;
            }
            if (slot == 53) { viewer.closeInventory(); return; }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = (Player) e.getPlayer();
        String title = e.getView().getTitle();
        if (title.startsWith(GUI_TITLE_EFFECT_PREFIX)) cleanupViewer(p.getUniqueId());
    }

    private void cleanupViewer(UUID viewerId) {
        openEffectView.remove(viewerId);
        openEffectInv.remove(viewerId);
    }

    // ===== 保存/読込 =====
    private void loadEnabledSets() {
        var cfg = getConfig();
        enabledOverhead.clear();
        enabledHud.clear();
        for (String s : cfg.getStringList("enabledOverhead")) {
            try { enabledOverhead.add(UUID.fromString(s)); } catch (Exception ignore) {}
        }
        for (String s : cfg.getStringList("enabledHud")) {
            try { enabledHud.add(UUID.fromString(s)); } catch (Exception ignore) {}
        }
    }
    private void saveEnabledSets() {
        getConfig().set("enabledOverhead", enabledOverhead.stream().map(UUID::toString).collect(Collectors.toList()));
        getConfig().set("enabledHud",      enabledHud.stream().map(UUID::toString).collect(Collectors.toList()));
        saveConfig();
    }

    // ===== トグル（管理者のみ） =====
    private void toggleOverhead(Player p) {
        UUID id = p.getUniqueId();
        if (enabledOverhead.contains(id)) enabledOverhead.remove(id);
        else enabledOverhead.add(id);
        saveEnabledSets();
        applyVisibilityFor(p); // 即反映
        p.sendMessage(ChatColor.AQUA + "頭上表示: " + (enabledOverhead.contains(id) ? ChatColor.GREEN + "ON" : ChatColor.YELLOW + "OFF"));
    }
    private void toggleHud(Player p) {
        UUID id = p.getUniqueId();
        if (enabledHud.contains(id)) enabledHud.remove(id);
        else enabledHud.add(id);
        saveEnabledSets();
        p.sendMessage(ChatColor.AQUA + "HUD: " + (enabledHud.contains(id) ? ChatColor.GREEN + "ON" : ChatColor.YELLOW + "OFF"));
    }

    // ===== 補助（アイテム生成） =====
    private ItemStack filler() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName(" ");
        it.setItemMeta(im);
        return it;
    }
    private ItemStack toggleItem(Material onMat, Material offMat, boolean on, String title, List<String> lore) {
        ItemStack it = new ItemStack(on ? onMat : offMat);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName((on ? ChatColor.GREEN : ChatColor.YELLOW) + title);
        im.setLore(lore);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(im);
        return it;
    }
    private ItemStack simpleItem(Material mat, String title, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName(title);
        im.setLore(lore);
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(im);
        return it;
    }
}
