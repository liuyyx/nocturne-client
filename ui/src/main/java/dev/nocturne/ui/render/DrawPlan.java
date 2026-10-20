package dev.nocturne.ui.render;

/**
 * 绘制计划：把 {@link Renderer} 的高级原语编译成 {@link DrawSink} 的三个底层原语。
 *
 * <p>设计目标（见 {@code docs/PLAN.md} P4-D）：
 * <ul>
 *   <li>上层组件树只写一份代码，永远不判断版本；</li>
 *   <li>圆角、描边这类「高级效果」在这里**合成**，后端不认识它们，因此各版本观感一致；</li>
 *   <li>提交前做一次<strong>重写</strong>：剔除裁剪外命令、合并相邻同色矩形、按裁剪分组。</li>
 * </ul>
 *
 * <p>使用方式：每帧 {@link #begin()} → 调 {@link Renderer} 方法 → {@link #flush(DrawSink)}。
 * 命令对象在 {@link #begin()} 时复用，正常渲染不产生分配。
 */
public final class DrawPlan implements Renderer {

    /** 圆角每 90° 的分段数；12 段在 4K 下已看不出阶梯。 */
    private static final int CORNER_SEGMENTS = 12;

    /** 命令类型：填充。 */
    private static final int FILL = 0;
    /** 命令类型：纹理。 */
    private static final int BLIT = 1;
    /** 命令类型：裁剪。 */
    private static final int SCISSOR = 2;

    /** 裁剪栈最大深度；与核心后端保持一致。 */
    private static final int MAX_CLIP_DEPTH = 8;

    /** 文本绘制委托；{@code null} 表示不画文字（几何测试与无字体环境）。 */
    private final TextSink text;

    /**
     * 纹理宿主：{@link #uploadTexture}/{@link #deleteTexture} 直接转发给它。
     *
     * <p>纹理生命周期属于后端，不属于绘制计划；计划只负责「画什么」。
     * {@code null} 表示当前环境不支持纹理（几何测试、无 GL 环境）。
     */
    private final Renderer textures;

    /** 命令池；索引 {@code [0,count)} 有效。 */
    private Cmd[] commands = new Cmd[256];

    /** 当前命令数。 */
    private int count;

    /** 当前裁剪矩形（绝对坐标）。初始为无限大。 */
    private float clipX1 = -Float.MAX_VALUE;
    private float clipY1 = -Float.MAX_VALUE;
    private float clipX2 = Float.MAX_VALUE;
    private float clipY2 = Float.MAX_VALUE;

    /** 裁剪栈：保存 push 之前的矩形。 */
    private final float[] clipStack = new float[MAX_CLIP_DEPTH * 4];
    /** 当前裁剪深度。 */
    private int clipDepth;

    /**
     * @param text 文本绘制委托；可为 {@code null}
     */
    public DrawPlan(TextSink text) {
        this(text, null);
    }

    /**
     * @param text     文本绘制委托；可为 {@code null}
     * @param textures 纹理宿主（负责上传/删除）；可为 {@code null}
     */
    public DrawPlan(TextSink text, Renderer textures) {
        this.text = text;
        this.textures = textures;
    }

    /** 开始新一帧：清空命令与裁剪状态，复用已有命令对象。 */
    public void begin() {
        count = 0;
        clipDepth = 0;
        clipX1 = -Float.MAX_VALUE;
        clipY1 = -Float.MAX_VALUE;
        clipX2 = Float.MAX_VALUE;
        clipY2 = Float.MAX_VALUE;
    }

    /** @return 本帧已记录的命令数（测试用） */
    public int size() {
        return count;
    }

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        if (color == null || width <= 0f || height <= 0f || color.a() == 0) {
            return;
        }
        fill(x, y, x + width, y + height, color);
    }

    /**
     * 圆角矩形：中央一条 + 上下各 {@link #CORNER_SEGMENTS} 条带。
     *
     * <p>没有原生圆角的后端也能得到一致观感；段数固定，避免不同后端的半径算法分叉。
     */
    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        if (color == null || width <= 0f || height <= 0f || color.a() == 0) {
            return;
        }
        float r = Math.min(radius, Math.min(width, height) / 2f);
        if (r <= 0.5f) {
            rect(x, y, width, height, color);
            return;
        }
        // 中央竖条：上下圆角之间的整宽区域。
        fill(x, y + r, x + width, y + height - r, color);
        float bandHeight = r / CORNER_SEGMENTS;
        for (int i = 0; i < CORNER_SEGMENTS; i++) {
            float t0 = i / (float) CORNER_SEGMENTS;
            float t1 = (i + 1) / (float) CORNER_SEGMENTS;
            // 取该段中点处的圆弧内缩，保证阶梯不超出理论圆角。
            float dy = r * (t0 + t1) * 0.5f;
            float inset = r - (float) Math.sqrt(Math.max(0.0, r * r - dy * dy));
            float bandX = x + inset;
            float bandWidth = width - inset * 2f;
            if (bandWidth <= 0f) {
                continue;
            }
            // 顶部带：dy 越大越靠上（y + r - dy 处），高度为 bandHeight。
            fill(bandX, y + r - r * t1, bandX + bandWidth, y + r - r * t1 + bandHeight, color);
            // 底部带：与顶部镜像。
            fill(bandX, y + height - r + r * t0, bandX + bandWidth,
                    y + height - r + r * t0 + bandHeight, color);
        }
    }

    @Override
    public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
        if (color == null || width <= 0f || height <= 0f || lineWidth <= 0f || color.a() == 0) {
            return;
        }
        float lw = Math.min(lineWidth, Math.min(width, height) / 2f);
        fill(x, y, x + width, y + lw, color);
        fill(x, y + height - lw, x + width, y + height, color);
        fill(x, y + lw, x + lw, y + height - lw, color);
        fill(x + width - lw, y + lw, x + width, y + height - lw, color);
    }

    @Override
    public void text(String textValue, float x, float y, float size, Color color) {
        if (text != null) {
            text.draw(textValue, x, y, size, color);
        }
    }

    @Override
    public float textWidth(String textValue, float size) {
        return text == null ? 0f : text.width(textValue, size);
    }

    @Override
    public float textHeight(float size) {
        return text == null ? 0f : text.height(size);
    }

    @Override
    public void texturedQuad(int texture, float x, float y, float width, float height,
                             float u0, float v0, float u1, float v1, int argb) {
        Color color = Color.of(argb);
        if (texture <= 0 || width <= 0f || height <= 0f || color.a() == 0) {
            return;
        }
        Cmd c = command(BLIT);
        c.x1 = x;
        c.y1 = y;
        c.x2 = x + width;
        c.y2 = y + height;
        c.texture = texture;
        c.u0 = u0;
        c.v0 = v0;
        c.u1 = u1;
        c.v1 = v1;
        c.argb = argb;
    }

    @Override
    public int uploadTexture(int id, int width, int height, int[] argb) {
        return textures == null ? 0 : textures.uploadTexture(id, width, height, argb);
    }

    @Override
    public void deleteTexture(int id) {
        if (textures != null) {
            textures.deleteTexture(id);
        }
    }

    @Override
    public boolean supportsTextures() {
        return textures != null && textures.supportsTextures();
    }

    @Override
    public void pushClip(float x, float y, float width, float height) {
        if (clipDepth >= MAX_CLIP_DEPTH) {
            // 深度超限：仍记录一次 scissor 保持 push/pop 配对的语义不变。
            clipDepth++;
            return;
        }
        clipStack[clipDepth * 4] = clipX1;
        clipStack[clipDepth * 4 + 1] = clipY1;
        clipStack[clipDepth * 4 + 2] = clipX2;
        clipStack[clipDepth * 4 + 3] = clipY2;
        clipDepth++;

        clipX1 = Math.max(clipX1, x);
        clipY1 = Math.max(clipY1, y);
        clipX2 = Math.min(clipX2, x + width);
        clipY2 = Math.min(clipY2, y + height);
        scissorCommand();
    }

    @Override
    public void popClip() {
        if (clipDepth <= 0) {
            return;
        }
        if (clipDepth > MAX_CLIP_DEPTH) {
            // 超限的那一层没有真正改状态，只记账。
            clipDepth--;
            return;
        }
        clipDepth--;
        clipX1 = clipStack[clipDepth * 4];
        clipY1 = clipStack[clipDepth * 4 + 1];
        clipX2 = clipStack[clipDepth * 4 + 2];
        clipY2 = clipStack[clipDepth * 4 + 3];
        scissorCommand();
    }

    /**
     * 把本帧命令提交给后端。
     *
     * <p>提交时做三件事：跳过透明与裁剪外命令、合并相邻同色同高的矩形、按命令顺序发出。
     * 调用后命令缓冲区被清空。
     *
     * @param sink 目标执行器
     */
    public void flush(DrawSink sink) {
        float cx1 = -Float.MAX_VALUE;
        float cy1 = -Float.MAX_VALUE;
        float cx2 = Float.MAX_VALUE;
        float cy2 = Float.MAX_VALUE;
        Cmd pending = null;
        for (int i = 0; i < count; i++) {
            Cmd c = commands[i];
            if (c.type == SCISSOR) {
                emit(sink, pending);
                pending = null;
                cx1 = c.x1;
                cy1 = c.y1;
                cx2 = c.x2;
                cy2 = c.y2;
                sink.scissor(c.x1, c.y1, c.x2 - c.x1, c.y2 - c.y1);
                continue;
            }
            if (c.culled(cx1, cy1, cx2, cy2)) {
                continue;
            }
            if (c.type == BLIT) {
                emit(sink, pending);
                pending = null;
                sink.blit(c.texture, c.x1, c.y1, c.x2 - c.x1, c.y2 - c.y1,
                        c.u0, c.v0, c.u1, c.v1, Color.of(c.argb));
                continue;
            }
            if (pending != null && pending.mergeable(c)) {
                pending.x1 = Math.min(pending.x1, c.x1);
                pending.x2 = Math.max(pending.x2, c.x2);
            } else {
                emit(sink, pending);
                pending = c;
            }
        }
        emit(sink, pending);
        count = 0;
    }

    /** 记录一条填充命令。 */
    private void fill(float x1, float y1, float x2, float y2, Color color) {
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        Cmd c = command(FILL);
        c.x1 = x1;
        c.y1 = y1;
        c.x2 = x2;
        c.y2 = y2;
        c.argb = color.packed();
    }

    /** 记录当前裁剪区域。 */
    private void scissorCommand() {
        Cmd c = command(SCISSOR);
        c.x1 = clipX1;
        c.y1 = clipY1;
        c.x2 = clipX2;
        c.y2 = clipY2;
    }

    /** 取一条可写命令（必要时扩容）。 */
    private Cmd command(int type) {
        if (count == commands.length) {
            Cmd[] grown = new Cmd[commands.length * 2];
            System.arraycopy(commands, 0, grown, 0, commands.length);
            commands = grown;
        }
        Cmd c = commands[count];
        if (c == null) {
            c = new Cmd();
            commands[count] = c;
        }
        count++;
        c.type = type;
        return c;
    }

    /** 提交一条待合并的填充。 */
    private static void emit(DrawSink sink, Cmd c) {
        if (c != null) {
            sink.fill(c.x1, c.y1, c.x2, c.y2, Color.of(c.argb));
        }
    }

    /** 一条绘制命令；字段按类型复用，避免每帧分配。 */
    private static final class Cmd {
        int type;
        float x1;
        float y1;
        float x2;
        float y2;
        int argb;
        int texture;
        float u0;
        float v0;
        float u1;
        float v1;

        /** @return 是否应被剔除（透明或完全落在裁剪外） */
        boolean culled(float cx1, float cy1, float cx2, float cy2) {
            if ((argb >>> 24) == 0) {
                return true;
            }
            return x2 <= cx1 || x1 >= cx2 || y2 <= cy1 || y1 >= cy2;
        }

        /** @return 两条填充是否可合并（同色、同一水平带、x 方向相接或重叠） */
        boolean mergeable(Cmd other) {
            return type == FILL && other.type == FILL && argb == other.argb
                    && y1 == other.y1 && y2 == other.y2
                    && other.x1 <= x2 && x1 <= other.x2;
        }
    }
}
