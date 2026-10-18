# P6 3D 预览 — 已验证真实状态（2026-10-18，非伪造完成）

## 已确认的事实（grep + 代码读取）
- `FaPreviewCard.java` 存在（`line 16` 类定义 + `line 169` `"Preview unavailable"` 降级文字写死）
- `FaDraw.java:F565` `player()` 默认 `false`（`line 80`：3D 由 `dev.nocturne.ui.preview.PlayerPreview` 接管）
- `grep preview/PlayerPreview.java` 无命中 → 源文件未存在
- 映射表 `client/src/main/resources/mappings-26.2.json` / `26.3.json` 无以下运行期成员方法/字段：
  - `RenderManager`（实体渲染调度）
  - `EntityRenderDispatcher`（实体渲染派发）
  - `RenderTarget` / `Framebuffer`（离屏渲染目标）
  - `GuiRenderState` / `BlitRenderState` / PiP（26.x 提交路径）
  - 皮肤纹理类（`AbstractClientPlayer` / `PlayerSkin` / `SkinTexture` / `DefaultPlayerSkin`）
- `26.x` PiP 反射：未实现（需反射 `GuiGraphicsExtractor.blit` 提交路径，当前 `ExtractorRenderer` 只处理 `fill/blit/scissor/text`，无 PiP 专用路径）

## 前置条件顺序（不可跳过，按计划）
1. 工具：`tools/mapping/generate.py` 扩展，生成 1.8.9 / 1.13 / 1.21 / 26.x 的实体渲染面表
2. 代码：`preview/PlayerPreview.java`（渲染 + 纹理缓存 + FBO / PiP 切换 + `available()` 降级链）
3. 代码：`FaDraw.player()` / `FaPreviewCard.render()` 接入非降级路径
4. 真机验证：1.8.9（`gl-fixed` + EXT-FBO）+ 26.3（`gui-extractor` + SDL + PiP/Blit）各一张截图

## 结论
P6 当前状态：**预览骨架已存在，模型渲染接入延期**。已在 `VERSION-MATRIX.md` / 交付报告中明确记录为"未实现"。下一步必须先做映射扩展（P6 前置），再做 PiP 反射接入。不能在缺映射数据时写出 `PlayerPreview.render()` 而声称完成。
