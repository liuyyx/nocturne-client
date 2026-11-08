package dev.nocturne.client.game;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 替身计分板链路：{@code ClientLevel#getScoreboard} → {@code Scoreboard#getDisplayObjective}
 * → {@code listPlayerScores} → 条目 {@code owner()/value()}。
 *
 * <p>对应 1.20.3+/26.x 的形态：{@code getPlayerScores} 与 {@code Score#getOwner/getScore}
 * 在映射表里都是 absent，条目是只读记录。用来回归「26.x 侧边栏整块空白」。
 */
public final class FakeScoreboard {

    private FakeScoreboard() {
    }

    /** 显示槽位枚举：桥接层按「恰好一个枚举形参」找重载并取 {@code SIDEBAR}。 */
    public enum DisplaySlot {
        LIST,
        SIDEBAR
    }

    /** 计分板条目（26.x 的 {@code PlayerScoreEntry} 形态）。 */
    public static final class Entry {
        private final String owner;
        private final int value;

        public Entry(String owner, int value) {
            this.owner = owner;
            this.value = value;
        }

        public String owner() {
            return owner;
        }

        public int value() {
            return value;
        }
    }

    /** 目标（{@code Objective}）。 */
    public static final class Objective {
        private final String name;

        public Objective(String name) {
            this.name = name;
        }

        public Object getDisplayName() {
            return name;
        }
    }

    /** 计分板（{@code Scoreboard}）：只有 26.x 的新形态，没有 getPlayerScores。 */
    public static final class Board {
        private final Objective objective;
        private final List<Entry> entries;

        public Board(Objective objective, List<Entry> entries) {
            this.objective = objective;
            this.entries = entries;
        }

        public Object getDisplayObjective(DisplaySlot slot) {
            return slot == DisplaySlot.SIDEBAR ? objective : null;
        }

        public Collection<Entry> listPlayerScores(Object objective) {
            return entries;
        }
    }

    /** 世界（{@code ClientLevel}）。 */
    public static final class Level {
        private final Board board;

        public Level(Board board) {
            this.board = board;
        }

        public Object getScoreboard() {
            return board;
        }
    }

    /** 一眼可见的一行分数，便于断言。 */
    public static Level levelWith(String objectiveName, String owner, int value) {
        Board board = new Board(new Objective(objectiveName),
                Collections.singletonList(new Entry(owner, value)));
        return new Level(board);
    }
}
