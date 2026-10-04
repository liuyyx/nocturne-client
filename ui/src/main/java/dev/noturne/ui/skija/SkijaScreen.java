/*
 * 移植自 Setsuna（上游 commit e4915ae093748d48d92ee47c38cdb8b2746a4730，作者 ShiYi，
 * 许可 GPL-3.0-or-later，见 THIRD-PARTY-NOTICES.md）。本文件相对上游的改动：仅包名。
 */
package dev.noturne.ui.skija;

import io.github.humbleui.skija.Canvas;

/** 由共享的 Skija 帧缓冲桥接渲染的屏幕契约。 */
public interface SkijaScreen {

    /**
     * 把整屏内容画到 Skija 画布上。
     *
     * <p>画布已经过 GUI 缩放、原点在左上角——屏幕实现无需关心游戏用哪代绘制 API 或分辨率。
     *
     * @param canvas 本帧的画布；为 {@code null} 时表示本帧不可绘制（实现应直接返回）
     */
    void renderSkija(Canvas canvas);
}
