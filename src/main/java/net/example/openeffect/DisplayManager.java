package net.example.openeffect;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * TextDisplay を使ってプレイヤー頭上に複数行の効果一覧を表示する。
 * - 1プレイヤーにつき TextDisplay を1体保持（複数行は \n に結合）
 * - viewerごとの showEntity/hideEntity を都度適用（トグル・再生成に強い）
 * - setText(Component)/setText(String) の両APIに自動対応
 * - オフライン掃除で残留を防止
 * - 「自分の頭上だけ非表示」のポリシーは OpenEffectPlugin#canSeeOwnOverhead を参照
 */
public class DisplayManager {

    private final OpenEffectPlugin core;

    // targetUUID -> TextDisplay（表示される“所有者”）
    private final Map<UUID, TextDisplay> displays = new HashMap<>();
    // 直近の描画内容（変化検知）
    private final Map<UUID, String> lastText = new HashMap<>();

    // config
    private final double offRight, offForward;
    private final double topUp;      // 上端の高さ
    private final boolean showPlayerName;
    private final String language;

    public DisplayManager(OpenEffectPlugin plugin) {
        this.core = plugin;
        var cfg = plugin.getConfig();
        this.offRight       = cfg.getDouble("offsetRight",   0.0);
        this.offForward     = cfg.getDouble("offsetForward", 0.0);
        this.topUp          = cfg.getDouble("box.topUp",     1.90);
        this.showPlayerName = cfg.getBoolean("showPlayerName", false);
        this.language       = cfg.getString("language", "ja");
    }

    // --- 管理 ---
    public void ensureAllTargets() {
        for (Player p : Bukkit.getOnlinePlayers()) ensureTarget(p);
        removeOfflineTargets(); // 念のため掃除
    }

    public void ensureTarget(Player target) {
        if (target == null || !target.isOnline()) return;
        displays.computeIfAbsent(target.getUniqueId(), k -> {
            TextDisplay td = spawnDisplay(target);
            reapplyVisibilityFor(target.getUniqueId(), td);
            return td;
        });
        lastText.putIfAbsent(target.getUniqueId(), "");
    }

    public void removeTarget(UUID targetId) {
        TextDisplay td = displays.remove(targetId);
        if (td != null && !td.isDead()) td.remove();
        lastText.remove(targetId);
    }

    public void despawnAll() {
        for (TextDisplay td : displays.values()) {
            if (td != null && !td.isDead()) td.remove();
        }
        displays.clear();
        lastText.clear();
    }

    private void removeOfflineTargets() {
        for (UUID id : new ArrayList<>(displays.keySet())) {
            if (Bukkit.getPlayer(id) == null) removeTarget(id);
        }
    }

    // --- 更新 ---
    public void updateAll() {
        for (Player target : Bukkit.getOnlinePlayers()) updateOne(target);
        removeOfflineTargets();
    }

    public void updateOne(Player target) {
        if (target == null || !target.isOnline()) return;

        ensureTarget(target);

        List<String> lines = buildEffectLines(target);
        String joined = String.join("\n", lines);
        UUID id = target.getUniqueId();

        TextDisplay td = displays.get(id);
        if (td == null || td.isDead()) {
            td = spawnDisplay(target);
            displays.put(id, td);
            reapplyVisibilityFor(id, td);
            lastText.put(id, "");
        }

        if (!joined.equals(lastText.getOrDefault(id, ""))) {
            setTextCompat(td, joined);
            lastText.put(id, joined);
        }

        td.teleport(textPos(target));
    }

    /**
     * viewer 単位で可視性ポリシーを再適用。
     *  - Overhead 全体が OFF → 全非表示
     *  - Overhead ON かつ「自分の頭上OFF」→ 自分の td だけ非表示、他は表示
     *  - Overhead ON かつ「自分の頭上ON」→ すべて表示
     */
    public void applyVisibility(Player viewer) {
        if (viewer == null || !viewer.isOnline()) return;
        UUID vId = viewer.getUniqueId();
        boolean seeOverhead = core.canSeeOverhead(viewer);
        boolean seeSelf     = core.canSeeOwnOverhead(viewer); // 新ルール

        for (Map.Entry<UUID, TextDisplay> e : displays.entrySet()) {
            TextDisplay td = e.getValue();
            if (td == null || td.isDead()) continue;

            boolean show = seeOverhead;
            if (show && e.getKey().equals(vId) && !seeSelf) {
                show = false; // 自分のだけ隠す
            }

            if (show) viewer.showEntity(core, td);
            else viewer.hideEntity(core, td);
        }
    }

    /** 新規/再生成した1体について、全 viewer に可視性を再適用 */
    private void reapplyVisibilityFor(UUID ownerId, TextDisplay td) {
        for (Player v : Bukkit.getOnlinePlayers()) {
            boolean show = core.canSeeOverhead(v);
            if (show && ownerId.equals(v.getUniqueId()) && !core.canSeeOwnOverhead(v)) {
                show = false;
            }
            if (show) v.showEntity(core, td);
            else v.hideEntity(core, td);
        }
    }

    // --- 位置計算 ---
    private Location textPos(Player target) {
        Location eye = target.getEyeLocation();
        Vector right = rightOf(eye);
        Vector fwd   = forwardFlat(eye);
        return eye.clone()
                .add(right.multiply(offRight))
                .add(fwd.multiply(offForward))
                .add(0, topUp, 0);
    }

    private Vector forwardFlat(Location eye) {
        Vector fwd = eye.getDirection().clone(); fwd.setY(0);
        if (fwd.lengthSquared() < 1e-6) fwd = new Vector(0,0,1);
        return fwd.normalize();
    }
    private Vector rightOf(Location eye) {
        Vector fwd = forwardFlat(eye);
        return new Vector(-fwd.getZ(), 0, fwd.getX()).normalize();
    }

    private TextDisplay spawnDisplay(Player target) {
        World w = target.getWorld();
        Location pos = textPos(target);
        return w.spawn(pos, TextDisplay.class, ent -> {
            ent.setBillboard(Billboard.CENTER);
            try { ent.setSeeThrough(false); } catch (Throwable ignore) {}
            try { ent.setShadowed(true); } catch (Throwable ignore) {}
            try { ent.setLineWidth(200); } catch (Throwable ignore) {}
            try { ent.setDefaultBackground(false); } catch (Throwable ignore) {}
            try { ent.setAlignment(TextDisplay.TextAlignment.CENTER); } catch (Throwable ignore) {}
            setTextCompat(ent, "");
            try { ent.setPersistent(false); } catch (Throwable ignore) {}
            try { ent.setVisibleByDefault(false); } catch (Throwable ignore) {}
        });
    }

    // setText(Component)/setText(String) のどちらでも動くように
    private void setTextCompat(TextDisplay td, String plain) {
        try {
            var m = td.getClass().getMethod("setText", Component.class);
            m.invoke(td, Component.text(plain));
            return;
        } catch (Throwable ignore) {}
        try {
            var m2 = td.getClass().getMethod("setText", String.class);
            m2.invoke(td, plain);
        } catch (Throwable ignore) {}
    }

    // --- 表示テキスト ---
    public List<String> buildEffectLines(Player target) {
        List<String> out = new ArrayList<>();
        if (showPlayerName) out.add(target.getName());

        Collection<PotionEffect> effects = target.getActivePotionEffects();
        if (effects.isEmpty()) {
            out.add(language != null && language.startsWith("ja") ? "（効果なし）" : "(No Effects)");
            return out;
        }
        for (PotionEffect eff : effects) {
            String type = effectName(eff);
            int lv = eff.getAmplifier() + 1;
            int sec = Math.max(0, eff.getDuration() / 20);
            String m = String.format("%d:%02d", sec / 60, sec % 60);
            out.add(type + " " + roman(lv) + " " + m);
        }
        return out;
    }

    public String effectName(PotionEffect eff) {
        String raw = null;
        try { raw = eff.getType().getKey().getKey(); } catch (Throwable ignore) {}
        if (raw == null) { try { raw = eff.getType().getName(); } catch (Throwable ignore) {} }
        if (raw == null) return "UNKNOWN";
        String key = raw.toUpperCase(Locale.ROOT);

        switch (key) {
            case "SPEED": return ja("移動速度", "Speed");
            case "SLOW": case "SLOWNESS": return ja("移動低下", "Slowness");
            case "HASTE": case "FAST_DIGGING": return ja("採掘速度", "Haste");
            case "MINING_FATIGUE": case "SLOW_DIGGING": return ja("採掘低下", "Mining Fatigue");
            case "STRENGTH": case "INCREASE_DAMAGE": return ja("攻撃力上昇", "Strength");
            case "INSTANT_HEALTH": case "HEAL": return ja("即時回復", "Instant Health");
            case "INSTANT_DAMAGE": case "HARM": return ja("即時ダメージ", "Instant Damage");
            case "JUMP_BOOST": case "JUMP": return ja("跳躍力上昇", "Jump Boost");
            case "REGENERATION": return ja("再生", "Regeneration");
            case "RESISTANCE": case "DAMAGE_RESISTANCE": return ja("耐性", "Resistance");
            case "FIRE_RESISTANCE": return ja("耐火", "Fire Resistance");
            case "WATER_BREATHING": return ja("水中呼吸", "Water Breathing");
            case "INVISIBILITY": return ja("透明化", "Invisibility");
            case "NIGHT_VISION": return ja("暗視", "Night Vision");
            case "HUNGER": return ja("空腹", "Hunger");
            case "WEAKNESS": return ja("弱体化", "Weakness");
            case "POISON": return ja("毒", "Poison");
            case "WITHER": return ja("衰弱", "Wither");
            case "HEALTH_BOOST": return ja("体力増強", "Health Boost");
            case "ABSORPTION": return ja("衝撃吸収", "Absorption");
            case "SATURATION": return ja("満腹度回復", "Saturation");
            case "GLOWING": return ja("発光", "Glowing");
            case "LEVITATION": return ja("浮遊", "Levitation");
            case "LUCK": return ja("幸運", "Luck");
            case "UNLUCK": return ja("不運", "Unluck");
            case "CONDUIT_POWER": return ja("コンジットパワー", "Conduit Power");
            case "DOLPHINS_GRACE": return ja("イルカの好意", "Dolphin's Grace");
            case "BAD_OMEN": return ja("不吉な予感", "Bad Omen");
            case "HERO_OF_THE_VILLAGE": return ja("村の英雄", "Hero of the Village");
            default: return raw;
        }
    }

    private String ja(String ja, String en) {
        return language != null && language.startsWith("ja") ? ja : en;
    }

    private String roman(int n) {
        String[] r = {"","I","II","III","IV","V","VI","VII","VIII","IX","X"};
        return (n >= 0 && n < r.length) ? r[n] : String.valueOf(n);
    }
}
