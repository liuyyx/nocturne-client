# Vape 4.21 模块清单（照抄自 OpenVape4.21/…/manager/ModManager.java）

清单来源：`ModManager` 构造期注册的模块（coreModules 数组 + registerModules(Stream.of(...)) + setModule(...)）。
本文件由脚本从上游源码生成，未手工增删。

- 注册模块类：**78** 个
- `module/**` 源文件：457 个（含子模块、模块 UI、辅助类）

分类目录文件数：
  - `utility`: 183
  - `blatant`: 83
  - `render`: 75
  - `combat`: 49
  - `world`: 19
  - `(顶层)`: 12
  - `control`: 11
  - `none`: 11
  - `debug`: 7
  - `macro`: 7

## coreModules（固定注册）（60）

| # | 模块 | 源码 | 行数 |
|---|---|---|---|
| 1 | `ClientSettings` | `src/main/java/gg/vape/config/ClientSettings.java` | 359 |
| 2 | `LeftClicker` | `src/main/java/gg/vape/module/combat/LeftClicker.java` | 218 |
| 3 | `RightClicker` | `src/main/java/gg/vape/module/combat/RightClicker.java` | 88 |
| 4 | `Velocity` | `src/main/java/gg/vape/module/combat/Velocity.java` | 320 |
| 5 | `JumpReset` | `src/main/java/gg/vape/module/combat/JumpReset.java` | 172 |
| 6 | `KnockbackDelay` | `src/main/java/gg/vape/module/combat/KnockbackDelay.java` | 171 |
| 7 | `Reach` | `src/main/java/gg/vape/module/combat/Reach.java` | 378 |
| 8 | `Throwpot` | `src/main/java/gg/vape/module/utility/Throwpot.java` | 202 |
| 9 | `Refill` | `src/main/java/gg/vape/module/utility/Refill.java` | 264 |
| 10 | `Tracers` | `src/main/java/gg/vape/module/render/Tracers.java` | 297 |
| 11 | `NameTags` | `src/main/java/gg/vape/module/render/NameTags.java` | 803 |
| 12 | `Search` | `src/main/java/gg/vape/module/render/Search.java` | 309 |
| 13 | `ESP` | `src/main/java/gg/vape/module/render/ESP.java` | 120 |
| 14 | `ChestSteal` | `src/main/java/gg/vape/module/world/ChestSteal.java` | 304 |
| 15 | `KeepSprint` | `src/main/java/gg/vape/module/blatant/KeepSprint.java` | 146 |
| 16 | `FastPlace` | `src/main/java/gg/vape/module/world/FastPlace.java` | 69 |
| 17 | `HitBoxes` | `src/main/java/gg/vape/module/blatant/HitBoxes.java` | 93 |
| 18 | `SpawnerFinder` | `src/main/java/gg/vape/module/render/SpawnerFinder.java` | 155 |
| 19 | `StorageESP` | `src/main/java/gg/vape/module/render/StorageESP.java` | 213 |
| 20 | `Scaffold` | `src/main/java/gg/vape/module/blatant/Scaffold.java` | 523 |
| 21 | `Fullbright` | `src/main/java/gg/vape/module/render/Fullbright.java` | 134 |
| 22 | `WTap` | `src/main/java/gg/vape/module/combat/WTap.java` | 142 |
| 23 | `AutoArmor` | `src/main/java/gg/vape/module/utility/AutoArmor.java` | 249 |
| 24 | `InvCleaner` | `src/main/java/gg/vape/module/utility/InvCleaner.java` | 316 |
| 25 | `ThrowDebuff` | `src/main/java/gg/vape/module/utility/ThrowDebuff.java` | 149 |
| 26 | `AutoTool` | `src/main/java/gg/vape/module/utility/AutoTool.java` | 161 |
| 27 | `AimAssist` | `src/main/java/gg/vape/module/combat/AimAssist.java` | 259 |
| 28 | `Trajectories` | `src/main/java/gg/vape/module/render/Trajectories.java` | 424 |
| 29 | `AntiDebuff` | `src/main/java/gg/vape/module/render/AntiDebuff.java` | 83 |
| 30 | `SafeWalk` | `src/main/java/gg/vape/module/blatant/SafeWalk.java` | 48 |
| 31 | `Projectiles` | `src/main/java/gg/vape/module/render/proj/Projectiles.java` | 288 |
| 32 | `Fly` | `src/main/java/gg/vape/module/blatant/Fly.java` | 88 |
| 33 | `KillAura` | `src/main/java/gg/vape/module/blatant/KillAura.java` | 356 |
| 34 | `Arrows` | `src/main/java/gg/vape/module/render/Arrows.java` | 179 |
| 35 | `Blink` | `src/main/java/gg/vape/module/blatant/Blink.java` | 286 |
| 36 | `AutoPearl` | `src/main/java/gg/vape/module/utility/AutoPearl.java` | 582 |
| 37 | `Panic` | `src/main/java/gg/vape/module/utility/Panic.java` | 54 |
| 38 | `AntiAFK` | `src/main/java/gg/vape/module/world/AntiAFK.java` | 243 |
| 39 | `ArmorSwitch` | `src/main/java/gg/vape/module/utility/ArmorSwitch.java` | 212 |
| 40 | `ItemESP` | `src/main/java/gg/vape/module/render/ItemESP.java` | 322 |
| 41 | `MLG` | `src/main/java/gg/vape/module/utility/MLG.java` | 406 |
| 42 | `AutoHotbar` | `src/main/java/gg/vape/module/utility/AutoHotbar.java` | 338 |
| 43 | `AutoHeal` | `src/main/java/gg/vape/module/blatant/AutoHeal.java` | 436 |
| 44 | `PropHunt` | `src/main/java/gg/vape/module/render/PropHunt.java` | 109 |
| 45 | `Parkour` | `src/main/java/gg/vape/module/utility/Parkour.java` | 83 |
| 46 | `MurderFinder` | `src/main/java/gg/vape/module/world/MurderFinder.java` | 101 |
| 47 | `BowAimbot` | `src/main/java/gg/vape/module/combat/BowAimbot.java` | 474 |
| 48 | `Indicators` | `src/main/java/gg/vape/module/render/Indicators.java` | 535 |
| 49 | `Sprint` | `src/main/java/gg/vape/module/combat/Sprint.java` | 55 |
| 50 | `Health` | `src/main/java/gg/vape/module/render/Health.java` | 60 |
| 51 | `HitSelect` | `src/main/java/gg/vape/module/combat/HitSelect.java` | 48 |
| 52 | `BlockHit` | `src/main/java/gg/vape/module/combat/BlockHit.java` | 105 |
| 53 | `SilentAuraClicker` | `src/main/java/gg/vape/module/combat/silentaura/SilentAuraClicker.java` | 44 |
| 54 | `HitFlick` | `src/main/java/gg/vape/module/combat/HitFlick.java` | 670 |
| 55 | `BlockIn` | `src/main/java/gg/vape/module/utility/BlockIn.java` | 739 |
| 56 | `InventoryManager` | `src/main/java/gg/vape/module/utility/InventoryManager.java` | 653 |
| 57 | `NoItemRelease` | `src/main/java/gg/vape/module/combat/NoItemRelease.java` | 42 |
| 58 | `Timer` | `src/main/java/gg/vape/module/blatant/Timer.java` | 34 |
| 59 | `InventoryFill` | `src/main/java/gg/vape/module/utility/InventoryFill.java` | 87 |
| 60 | `BedPlates` | `src/main/java/gg/vape/module/render/BedPlates.java` | 444 |

## registerModules（版本约束注册）（9）

| # | 模块 | 源码 | 行数 |
|---|---|---|---|
| 1 | `InvWalk` | `src/main/java/gg/vape/module/blatant/InvWalk.java` | 124 |
| 2 | `Backtrack` | `src/main/java/gg/vape/module/blatant/Backtrack.java` | 426 |
| 3 | `AutoFish` | `src/main/java/gg/vape/module/utility/AutoFish.java` | 402 |
| 4 | `AntiBot` | `src/main/java/gg/vape/module/blatant/AntiBot.java` | 725 |
| 5 | `Triggerbot` | `src/main/java/gg/vape/module/combat/Triggerbot.java` | 339 |
| 6 | `NoFall` | `src/main/java/gg/vape/module/blatant/NoFall.java` | 86 |
| 7 | `KeystrokesHudModule` | `src/main/java/gg/vape/module/render/hud/KeystrokesHudModule.java` | 87 |
| 8 | `NoHurtDelayHudModule` | `src/main/java/gg/vape/module/render/hud/NoHurtDelayHudModule.java` | 22 |
| 9 | `ScoreboardHudModule` | `src/main/java/gg/vape/module/render/hud/ScoreboardHudModule.java` | 219 |

## setModule（HUD / 其它）（9）

| # | 模块 | 源码 | 行数 |
|---|---|---|---|
| 1 | `Explosions` | `src/main/java/gg/vape/module/render/Explosions.java` | 72 |
| 2 | `Freecam` | `src/main/java/gg/vape/module/render/Freecam.java` | 196 |
| 3 | `TextGuiSettings` | `src/main/java/gg/vape/module/none/TextGuiSettings.java` | 111 |
| 4 | `FreeLookHudModule` | `src/main/java/gg/vape/module/render/hud/FreeLookHudModule.java` | 304 |
| 5 | `NoClickDelayHudModule` | `src/main/java/gg/vape/module/render/hud/NoClickDelayHudModule.java` | 26 |
| 6 | `MouseDelayFix` | `src/main/java/gg/vape/module/none/MouseDelayFix.java` | 12 |
| 7 | `BlockhitAnimationHudModule` | `src/main/java/gg/vape/module/render/hud/BlockhitAnimationHudModule.java` | 138 |
| 8 | `BlockRenderColorOverrideHudModule` | `src/main/java/gg/vape/module/render/hud/BlockRenderColorOverrideHudModule.java` | 27 |
| 9 | `MotionBlur` | `src/main/java/gg/vape/module/render/hud/MotionBlur.java` | 558 |

> 说明：版本约束（ForgeVersion.MC_x.y 等）决定该模块在哪些游戏版本注册；
> 本清单只记录「照抄到了什么」，不代表已接入本项目运行时（见 UPSTREAM.txt 的结论）。
