# 第三方组件与来源

本项目（nocturne-client）以 **GPL-3.0-or-later** 分发（见根目录 `LICENSE`）。
下列第三方代码/资源的版权归其各自作者，来源与许可如实记录。

---

## 1. Setsuna（历史：旧 UI 绘制层的来源，代码已删除）

| 项 | 值 |
|---|---|
| 来源 | 用户提供的上游工程 `SetsunaClient/`（快照另存于 `vendor/setsuna/`） |
| 上游 commit | `e4915ae093748d48d92ee47c38cdb8b2746a4730`（Sun Aug 30 20:46:25 2026 +0800，"Restore original README"） |
| 作者 | ShiYi（QQ 1782605215 / @FS_Oracle） |
| 声明许可 | `GPL-3.0-or-later`（上游 `gradle.properties`: `license=GPL-3.0-or-later`） |
| 随附许可文件 | 上游 `LICENSE`（GPL-3.0 全文）、`LICENSE-APACHE`（Apache-2.0 全文），两者均随 `vendor/setsuna/` 保留 |
| 上游备注 | README 称"允许你进行任何操作 甚至魔改后售卖"；目录内虽附 Apache-2.0 全文，但**没有**"GPL-3.0 或 Apache-2.0 二选一"的文字声明，故本项目按 `GPL-3.0-or-later` 处理 |

**现状（重要）**：`vendor/setsuna/` 现在只作为**参考资料快照**保留，**不参与构建**。
`ui/` 中曾经移植自它的全部文件（`ui/skija/**`、`ui/gl/SkijaBackend|SkijaCanvas|SkijaRenderer`、
`ui/component/**`、`ui/clickgui/**`、`ui/theme/**`、`ui/anim/**`、`ui/hud/**`、`ui/gl/OverlayGui`
的 Canvas 通道，以及随其引入的 4 个图标字体与 `mainmenu/background.png`）**已全部删除**，
因此当前发布物里不含 Setsuna 代码或资源。历史移植清单与方案见
`docs/research/setsuna-gui-port.md`（已标注废弃）。

---

## 2. FatalityClickGui（当前 ClickGUI 的来源）

| 项 | 值 |
|---|---|
| 来源 | `https://github.com/BanCN-Re/FatalityClickGui`（工作树快照 `FatalityClickGui/`） |
| 随附许可文件 | **无**——上游仓库没有 `LICENSE` 文件，README 也没有许可声明 |
| 本项目用法 | `ui/src/main/java/dev/nocturne/ui/fatality/**` 是该仓库 `*.java` 的移植（包名改为 `dev.nocturne.ui.fatality`，去掉 Minecraft / 上游客户端依赖，Java 16+ 语法降级为 Java 8，按本项目的 `Module`/`Value` 体系重接） |
| 授权前提 | 移植与再分发以**仓库使用者的授权**为准：若使用者即上游作者或已获其许可，按其约定分发；否则该批文件不得随本项目对外分发（此时应改为按上游 README 的结构与视觉描述自行重写） |
| 随附资源 | 上游 `fonts/{manrope-bold,misans,lucide,fa-solid}.ttf` → `ui/src/main/resources/assets/nocturne/fonts/fatality/`（Manrope = OFL；MiSans = 小米公开发布的可免费商用字体；Lucide = ISC；Font Awesome Free = OFL/CC-BY 4.0。四者均非 FatalityClickGui 原创，各自许可独立于上游仓库） |

移植时未搬入的文件（本项目没有对应的数据结构）：`FaKeyRow` / `FaRangeRow` / `FaSlotRow` /
`FaTextRow` / `FaGearRow` / `FaRowsPopup` / `FaSlotPopup` / `FaConfigPopup` / `FaInfoPopup` /
`FaPlayerModel` / `CanvasFaDraw`。

---

## 3. 其他依赖

| 组件 | 许可 | 用途 |
|---|---|---|
| JUnit 5（`org.junit.jupiter`、`org.junit.platform`） | EPL-2.0 | 测试 |

> GPL-3.0 与 EPL-2.0 兼容；分发时保留各自的许可与版权声明即可。`ui` 模块自本次改动起不再依赖任何第三方 UI 库。

---

## 4. 映射数据来源（只在生成期使用；产物是衍生事实）

`client/src/main/resources/mappings-<版本>.json` 由 `tools/mapping/` 离线生成，
输入来自下列上游（下载物只落在 gitignored 的 `tools/mapping/cache/`，**不进仓库、不进 jar**）。

| 上游 | 用途 | 许可 |
|---|---|---|
| Mojang 官方 `client_mappings`（`piston-meta.mojang.com`） | 1.14.4+ 的 canonical ↔ 混淆名 | Minecraft EULA；版权头明确**不得原样再分发完整映射文件**，故只分发由它派生的 JSON（类名/成员名/描述符） |
| [MinecraftForge/MCPConfig](https://github.com/MinecraftForge/MCPConfig) | 1.12.2+ 的 SRG（Forge 运行期成员名） | 修改版 zlib（© 2018 Forge Development LLC）；允许创建与发布衍生作品，但须以**不同的 group 与名称**发布——本项目只发布自己命名的衍生 JSON，并在此致谢 |
| [FabricMC/intermediary](https://github.com/FabricMC/intermediary) | 1.14–1.21.11 的 intermediary（Fabric 运行期名） | CC0-1.0 |
| [Legacy-Fabric/Legacy-Intermediaries](https://github.com/Legacy-Fabric/Legacy-Intermediaries) | 1.8.2–1.13.2 的遗留 intermediary | CC0-1.0 |
| MCP 官方 `joined.srg`（`de.oceanlabs.mcp:mcp:<版本>-srg`，经 NeoForged maven 镜像取得；Forge 自家 maven 现需认证） | 1.9–1.12.1 的 SRG（Forge 运行期成员名） | MCP 的历史分发物；只在生成期读取，不进仓库、不进 jar |
| 本机 MCP 资产（`OpenVape4.21/.../mappings/vanilla189`、`forge189` 等） | 1.8.9 / 1.12.2 的 SRG 与 MCP 人类名 | 随该工程一并提供，仅本地读取 |

生成期还读取目标版本的官方 client jar（`javap` 取类继承链），同样只落在 `cache/`。
