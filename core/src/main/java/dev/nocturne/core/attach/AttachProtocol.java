package dev.nocturne.core.attach;

/**
 * HotSpot attach 协议常量（自研重写，仅行为对齐 OpenJDK）。
 *
 * <p>线格式（Unix 域套接字与 Windows 命名管道统一）：5 个 NUL 结尾的 UTF-8 字符串——
 * 协议版本 {@code "1"}、动词、3 个参数（无参者传空串）。动词与参数均拒绝 NUL；
 * 动词最长 16 字符，每个参数最长 1024 字符。
 *
 * <p>回复格式：首行 {@code <返回码>\n}；返回码 0 表示继续读内层 load 结果，
 * 非 0 按动词映射为异常。load 内层：JDK 8 目标返回裸整数，JDK 21 目标返回
 * {@code return-code-<n>} 前缀；非 0 即 agentmain 启动失败。
 */
public final class AttachProtocol {

    /** 工具类，禁止实例化。 */
    private AttachProtocol() {
    }

    /** 线协议版本（首个 NUL 结尾字符串恒为此值）。 */
    public static final String PROTOCOL_VERSION = "1";

    /** 动词最大长度（字符）。 */
    public static final int MAX_COMMAND_LENGTH = 16;

    /** 单个参数最大长度（字符）。 */
    public static final int MAX_ARGUMENT_LENGTH = 1024;

    /** load 动词。 */
    public static final String COMMAND_LOAD = "load";

    /** properties 动词。 */
    public static final String COMMAND_PROPERTIES = "properties";

    /** data dump 动词。 */
    public static final String COMMAND_DATADUMP = "datadump";

    /** load 的第一个参数：固定为 instrument（声明这是 agent 加载请求）。 */
    public static final String LOAD_ARG_INSTRUMENT = "instrument";

    /** load 的第二个参数：true=随着 app loader 一起加载，false=走系统加载器。 */
    public static final String LOAD_ARG_TRUE = "true";
    /** load 的第二个参数：false 形态。 */
    public static final String LOAD_ARG_FALSE = "false";

    /** 外层成功返回码。 */
    public static final int REPLY_OK = 0;

    /** 外层协议失配返回码（JDK 21 客户端读不懂目标回复时按 IOException 处理）。 */
    public static final int REPLY_PROTOCOL_MISMATCH = 101;

    /** 目标 attach 监听器被禁用的返回码。 */
    public static final int TARGET_DISABLED = 100;

    /** 目标资源不足的返回码。 */
    public static final int TARGET_RESOURCE_EXHAUSTED = 101;

    /** 非法参数的返回码。 */
    public static final int TARGET_ILLEGAL_ARGUMENT = 102;

    /** 目标内部错误的返回码。 */
    public static final int TARGET_INTERNAL_ERROR = 103;

    /** JDK 21 目标 load 内层成功前缀（{@code return-code-0}）。 */
    public static final String LOAD_RETURN_PREFIX = "return-code-";

    /**
     * 校验动词与参数是否符合线格式约束。
     *
     * @param command 动词，不可为 {@code null}
     * @param args    恰好 3 个参数（调用方负责用空串填充无参位）
     * @throws IllegalArgumentException 含 NUL、超长或动词为空时抛出
     */
    public static void checkRequest(String command, String[] args) {
        if (command == null || command.isEmpty() || command.length() > MAX_COMMAND_LENGTH
                || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("bad attach command: " + command);
        }
        if (args == null || args.length != 3) {
            throw new IllegalArgumentException("attach request needs exactly 3 arguments");
        }
        for (String arg : args) {
            if (arg == null || arg.length() > MAX_ARGUMENT_LENGTH || arg.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("bad attach argument: " + arg);
            }
        }
    }

    /**
     * 把一次 load 请求编码为线字节（5 个 NUL 结尾 UTF-8 字符串拼接）。
     *
     * @param agentJar  agent jar 绝对路径（形参 1 与形参 2 以 {@code =} 拼接为第 3 参数）
     * @param options   传给 {@code agentmain} 的参数，可为 {@code null}（按空串处理）
     * @param systemLoader 是否随 app loader 加载（对应第二个参数 true/false）
     * @return 线字节，可直接写入传输层
     */
    public static byte[] encodeLoad(String agentJar, String options, boolean systemLoader) {
        String joint = agentJar + "=" + (options == null ? "" : options);
        return encode(COMMAND_LOAD,
                new String[]{LOAD_ARG_INSTRUMENT,
                        systemLoader ? LOAD_ARG_TRUE : LOAD_ARG_FALSE, joint});
    }

    /**
     * 把动词 + 3 参数编码为线字节。
     *
     * @param command 动词
     * @param args    恰好 3 个参数
     * @return 线字节
     */
    public static byte[] encode(String command, String[] args) {
        checkRequest(command, args);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                    PROTOCOL_VERSION.length() + command.length() + 16);
            writeString(out, PROTOCOL_VERSION);
            writeString(out, command);
            for (String arg : args) {
                writeString(out, arg);
            }
            return out.toByteArray();
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 写一个 NUL 结尾的 UTF-8 字符串。 */
    private static void writeString(java.io.ByteArrayOutputStream out, String value)
            throws java.io.IOException {
        out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.write(0);
    }

    /**
     * 解析外层回复首行（{@code <返回码>\n} 之前的内容）。
     *
     * @param line 首行文本（不含换行）
     * @return 返回码；解析失败返回 {@link #TARGET_INTERNAL_ERROR}
     */
    public static int parseReplyCode(String line) {
        if (line == null) {
            return TARGET_INTERNAL_ERROR;
        }
        try {
            return Integer.parseInt(line.trim());
        } catch (NumberFormatException bad) {
            return TARGET_INTERNAL_ERROR;
        }
    }

    /**
     * 解析 load 内层结果（JDK 8 裸整数 / JDK 21 {@code return-code-<n>} 前缀）。
     *
     * @param body 内层文本
     * @return 返回码；解析失败返回 -1
     */
    public static int parseLoadResult(String body) {
        if (body == null) {
            return -1;
        }
        String text = body.trim();
        if (text.startsWith(LOAD_RETURN_PREFIX)) {
            text = text.substring(LOAD_RETURN_PREFIX.length()).trim();
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException bad) {
            return -1;
        }
    }
}
