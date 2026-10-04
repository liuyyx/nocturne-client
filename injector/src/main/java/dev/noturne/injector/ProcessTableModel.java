package dev.noturne.injector;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * 三列对齐的表格模型——进程、PID、识别到的版本；版本列在后台查询完成后异步补齐。
 *
 * <p>不变量：所有修改方法都必须在 EDT 上调用；{@link Row#pid} 一经创建不再改变。
 */
public final class ProcessTableModel extends AbstractTableModel {

    /** 一行数据；{@code version} 初始为未知，拿到 WMI 结果后就地改写。 */
    public static final class Row {
        /** 进程 ID，创建后不变，是 {@link ProcessTableModel#setVersion} 的查找键。 */
        public final int pid;
        /** 进程显示名。 */
        public final String name;
        /**
         * 版本标签；初始为未知，WMI 返回后就地改写。
         *
         * <p>声明为 volatile：虽则当前读写都发生在 EDT，但版本由后台 SwingWorker 完成后回写，
         * 保留 happens-before 语义，避免将来有后台线程读取时出现可见性问题。
         */
        public volatile String version;

        /**
         * @param pid     进程 ID
         * @param name    进程显示名
         * @param version 版本标签；未知时传破折号占位
         */
        public Row(int pid, String name, String version) {
            this.pid = pid;
            this.name = name;
            this.version = version;
        }
    }

    /** 列标题，顺序即列索引顺序。 */
    private static final String[] COLUMNS = {"进程", "PID", "版本"};

    /** 当前行数据；所有读方法都在 EDT 上访问，无需同步。 */
    private final List<Row> rows = new ArrayList<Row>();

    /**
     * 整体替换行数据并通知表格刷新。
     *
     * @param newRows 新的行集合；内容会被拷贝，调用方可继续持有原集合
     */
    public void setRows(List<Row> newRows) {
        rows.clear();
        // clear + addAll 而不是替换引用：避免中途异常留下半新半旧的列表。
        rows.addAll(newRows);
        fireTableDataChanged();
    }

    /** 清空所有行并通知表格刷新。 */
    public void clear() {
        rows.clear();
        fireTableDataChanged();
    }

    /**
     * 按模型行索引取行。
     *
     * <p>注意这是<em>模型</em>索引；若表格启用了排序/过滤，调用方必须先用
     * {@code JTable.convertRowIndexToModel} 把视图索引转换过来，否则会取到错行。
     *
     * @param index 模型行索引
     * @return 对应行；索引越界时返回 {@code null}，由调用方决定如何提示
     */
    public Row rowAt(int index) {
        return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    /**
     * 按进程 ID 查行索引。
     *
     * @param pid 目标进程 ID
     * @return 命中的行索引；未命中返回 -1
     */
    public int indexOfPid(int pid) {
        // 线性扫描：行数是进程数量级（通常个位数到几十），无需建索引。
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).pid == pid) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 就地改写某个进程的版本标签；行已消失时什么都不做。
     *
     * @param pid     目标进程 ID
     * @param version 新的版本标签
     */
    public void setVersion(int pid, String version) {
        // 先查存在性：版本查询是异步的，期间用户可能已重新扫描导致行被替换。
        int index = indexOfPid(pid);
        if (index < 0) {
            return;
        }
        rows.get(index).version = version;
        fireTableCellUpdated(index, 2);
    }

    /** @return 当前行数 */
    @Override
    public int getRowCount() {
        return rows.size();
    }
    /** @return 固定为 3 */
    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    /**
     * @param column 列索引
     * @return 该列的中文标题
     */
    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }
    /**
     * 取单元格显示值。
     *
     * <p>PID 以字符串返回，因此渲染器只需处理 {@link Object} 而无需为数字另设渲染路径。
     *
     * @param rowIndex    行索引
     * @param columnIndex 列索引：0=进程名，1=PID，2=版本
     * @return 该单元格的显示文本
     */
    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        // 表格与模型短暂不同步时（如并发刷新）不抛异常，返回空值让渲染器画空白。
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return null;
        }
        Row row = rows.get(rowIndex);
        switch (columnIndex) {
            case 1:
                return Integer.toString(row.pid);
            case 2:
                return row.version;
            case 0:
                return row.name;
            default:
                // 越界列返回 null，而不是悄悄把进程名当成该列的值。
                return null;
        }
    }
}
