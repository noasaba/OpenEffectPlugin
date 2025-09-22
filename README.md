# OpenEffect (Paper Plugin) — v1.1.1

プレイヤーに付与されている**ポーション効果**を、
- **管理GUI**（Chest GUI）から他プレイヤーの **現在の効果一覧**を閲覧
- **管理者のみ**が自分の **頭上表示 (TextDisplay)** / **HUD(ActionBar)** を切り替え  
  できる Paper 向けプラグインです。

> **v1.1.1**: 一般権限には設定表示/コマンドが一切出ないように変更。  
> ログアウト時に頭上テキストが残る不具合を修正。  
> ビルドは **Maven → Gradle** へ移行しました。

---

## 主な機能

- **管理GUI** `/openeffect gui`
    - オンラインプレイヤーの **頭（Skull）一覧**をページ表示
    - クリックでそのプレイヤーの **効果一覧**（残り時間を1秒ごと自動更新）
    - Skull には PDC で **UUID を埋め込み**、クリック先を厳密に解決
- **頭上表示 (Overhead / TextDisplay)**（管理者のみ自己切替）
    - 1人あたり 1 つの `TextDisplay` を頭上に表示（複数行は改行で集約）
    - `setText(Component)` / `setText(String)` の **両APIに自動対応**
- **HUD (ActionBar)**（管理者のみ自己切替）
    - 行を ` | ` でまとめて ActionBar に周期表示
- **残留バグ対策**
    - ログアウト（Quit）の瞬間に対象の `TextDisplay` を **確実に remove**
    - 定期タスクでも **オフライン掃除** を実施
- **言語/見た目**
    - `config.yml` で言語 `ja`/`en`（効果名）・名前表示ON/OFF
    - 位置オフセット/高さ・更新間隔を設定可能

---

## 必要環境

- **Paper 1.21.4** 互換（`paper-api:1.21.4-R0.1-SNAPSHOT` を `compileOnly`）
- **Java 21**

---

## インストール

1. `build/libs/OpenEffect-1.1.1.jar` を Paper サーバーの `plugins/` に配置
2. サーバー起動
3. `plugins/OpenEffect/config.yml` を必要に応じて調整 → `/openeffect reload`（管理者）

---

## コマンド & 権限

> 一般プレイヤーは **コマンド不可**・**Join時ヒントも非表示**（v1.1.1）

| コマンド | 用途 | 権限 |
|---|---|---|
| `/openeffect gui` | 管理GUI（オンライン一覧 → 頭クリックで効果一覧） | `openeffect.admin` |
| `/openeffect config` | 自分の **頭上表示/HUD** の切替 GUI | `openeffect.admin` |
| `/openeffect reload` | `config.yml` を再読込 | `openeffect.admin` |
| `/open ...` | 上記のエイリアス | `openeffect.admin` |

**permissions（plugin.yml）**
```yaml
permissions:
  openeffect.admin:
    default: op
    description: 管理用GUIと設定を使用できる
```
```
OpenEffect/
├─ build.gradle
├─ settings.gradle
├─ src/
│  └─ main/
│     ├─ java/net/example/openeffect/
│     │  ├─ OpenEffectPlugin.java
│     │  └─ DisplayManager.java
│     └─ resources/
│        └─ plugin.yml
```
```config.yml
# 管理者一覧に自分自身を含める
includeSelfInAdminList: true

# 表示テキスト設定
language: ja          # ja または en（効果名の表示）
showPlayerName: false # true で先頭行にプレイヤー名

# 更新周期
updateTicks: 1        # 頭上 TextDisplay の追従周期（tick）
hudUpdateTicks: 40    # HUD(ActionBar) の更新周期（tick）

# 座標補正
offsetRight: 0.0
offsetForward: 0.0
box:
  topUp: 1.90         # プレイヤー視点からの上方向オフセット（頭上の高さ）

# 動作フラグ
updateOnMove: true

# 以下はプラグインが自動で保存します（管理者のON/OFF状態）
enabledOverhead: []
enabledHud: []

```