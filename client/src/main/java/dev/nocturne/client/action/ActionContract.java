package dev.nocturne.client.action;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code contract/actions.yaml} 的只读模型。
 *
 * <p>契约使用 YAML 1.2 的 JSON flow 语法，因此直接复用 Gson；这样注入侧不必再携带一套
 * YAML 解析器。文件是唯一事实源，代码只消费它，不在别处复制候选方法清单。
 */
public final class ActionContract {

    private final Map<String, ActionSpec> actions;

    private ActionContract(Map<String, ActionSpec> actions) {
        this.actions = actions;
    }

    /** 从客户端 jar 内读取契约；缺失或损坏时显式失败，不能退回硬编码清单。 */
    public static ActionContract load() {
        InputStream stream = ActionContract.class.getResourceAsStream("/contract/actions.yaml");
        if (stream == null) {
            throw new IllegalStateException("missing action contract /contract/actions.yaml");
        }
        try {
            Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            try {
                return parse(reader);
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read action contract", e);
        }
    }

    /** 解析并校验契约。该方法公开供契约测试使用，运行期入口是 {@link #load()}。 */
    public static ActionContract parse(Reader reader) {
        RawContract raw = new Gson().fromJson(reader, RawContract.class);
        if (raw == null || raw.version != 1 || raw.actions == null || raw.actions.isEmpty()) {
            throw new IllegalStateException("invalid action contract header");
        }
        Map<String, ActionSpec> actions = new LinkedHashMap<String, ActionSpec>();
        for (RawAction action : raw.actions) {
            if (action == null || blank(action.id) || blank(action.signature)
                    || blank(action.receiver) || blank(action.operation) || blank(action.degrade)) {
                throw new IllegalStateException("incomplete action entry");
            }
            if (actions.containsKey(action.id)) {
                throw new IllegalStateException("duplicate action " + action.id);
            }
            List<CandidateSpec> candidates = new ArrayList<CandidateSpec>();
            if (action.candidates != null) {
                for (RawCandidate candidate : action.candidates) {
                    if (candidate == null || blank(candidate.owner) || blank(candidate.method)) {
                        throw new IllegalStateException("incomplete candidate for " + action.id);
                    }
                    List<String> parameters = candidate.parameters == null
                            ? Collections.<String>emptyList()
                            : Collections.unmodifiableList(new ArrayList<String>(candidate.parameters));
                    candidates.add(new CandidateSpec(candidate.owner, candidate.method, parameters));
                }
            }
            boolean needsCandidates = !"rotation".equals(action.operation)
                    && !"snapshot".equals(action.operation);
            if (needsCandidates && candidates.isEmpty()) {
                throw new IllegalStateException("action has no candidates " + action.id);
            }
            actions.put(action.id, new ActionSpec(action.id, action.signature, action.receiver,
                    action.operation, action.degrade,
                    Collections.unmodifiableList(candidates)));
        }
        return new ActionContract(Collections.unmodifiableMap(actions));
    }

    /** 按声明顺序返回动作。 */
    public List<ActionSpec> actions() {
        return new ArrayList<ActionSpec>(actions.values());
    }

    /** 按语义 id 取动作；不存在时返回 {@code null}。 */
    public ActionSpec get(String id) {
        return actions.get(id);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 一条语义动作及其绑定方式。 */
    public static final class ActionSpec {
        private final String id;
        private final String signature;
        private final String receiver;
        private final String operation;
        private final String degrade;
        private final List<CandidateSpec> candidates;

        private ActionSpec(String id, String signature, String receiver, String operation,
                           String degrade, List<CandidateSpec> candidates) {
            this.id = id;
            this.signature = signature;
            this.receiver = receiver;
            this.operation = operation;
            this.degrade = degrade;
            this.candidates = candidates;
        }

        public String id() {
            return id;
        }

        public String signature() {
            return signature;
        }

        public String receiver() {
            return receiver;
        }

        public String operation() {
            return operation;
        }

        public String degrade() {
            return degrade;
        }

        public List<CandidateSpec> candidates() {
            return candidates;
        }
    }

    /** 一个 owner/method/params 候选；混淆描述符仍由 mappings-*.json 提供。 */
    public static final class CandidateSpec {
        private final String owner;
        private final String method;
        private final List<String> parameters;

        private CandidateSpec(String owner, String method, List<String> parameters) {
            this.owner = owner;
            this.method = method;
            this.parameters = parameters;
        }

        public String owner() {
            return owner;
        }

        public String method() {
            return method;
        }

        public List<String> parameters() {
            return parameters;
        }
    }

    private static final class RawContract {
        private int version;
        private List<RawAction> actions;
    }

    private static final class RawAction {
        private String id;
        private String signature;
        private String receiver;
        private String operation;
        private String degrade;
        private List<RawCandidate> candidates;
    }

    private static final class RawCandidate {
        private String owner;
        private String method;
        private List<String> parameters;
    }
}
