package io.github.limuqy.mc.hassium.client.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冒烟场景步骤：原语类型 + 参数键值对。
 * <p>
 * 场景文件格式（每行一步）：
 * <pre>
 *   # 注释
 *   join label=WAIT_JOIN_1 since=start timeoutMs=120000
 *   wait ms=20000
 *   fly seconds=6 tag=MOVE
 *   dump label=ROUND1 round=1
 * </pre>
 * 值支持 {@code ${var}} 替换（由加载方传入变量表求值，变量来自 JVM 属性）。
 * 解析失败抛 {@link IllegalArgumentException}（init 时 fail-fast）。
 */
public final class ScenarioStep {

    /** 步骤原语集。 */
    public enum Type {
        /** 等待进服（player/level/connection 就绪、非内嵌服、y&gt;0）。 */
        JOIN,
        /** 等待：定时（ms）或条件（until=migrated）。 */
        WAIT,
        /** 非阻塞飞行注入：爬升 2s + 平飞 Ns。 */
        FLY,
        /** 发送客户端命令；mode=migrate 走迁移触发语义。 */
        COMMAND,
        /** 切换维度（/execute in）。 */
        DIMENSION,
        /** 主动断开连接（netty channel 关闭路径）。 */
        DISCONNECT,
        /** 等 reconnectDelayMs 后重连（玩家仍在场则跳过重连）。 */
        RECONNECT,
        /** 统计 dump + GATEWAY marker + PROBE 写盘 + validateStats（gate=false 跳过校验）。 */
        DUMP,
        /** 场景内门禁断言：assertProbe key=<path> op=<gt|ge|lt|le|eq> value=<N>|vs=<path>。 */
        ASSERT_PROBE,
        /** 收尾：PASS/FAIL marker + 影子保存等待 + 退出（rounds=1 只看 round1Pass）。 */
        EXIT
    }

    private final Type type;
    private final Map<String, String> params;
    private final int lineNo;
    private final String rawLine;

    private ScenarioStep(Type type, Map<String, String> params, int lineNo, String rawLine) {
        this.type = type;
        this.params = params;
        this.lineNo = lineNo;
        this.rawLine = rawLine;
    }

    public Type type() {
        return type;
    }

    public int lineNo() {
        return lineNo;
    }

    public String param(String key) {
        return params.get(key);
    }

    public long longParam(String key, long def) {
        String v = params.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("step L" + lineNo + ": " + key + " 不是数字: " + v);
        }
    }

    public boolean boolParam(String key, boolean def) {
        String v = params.get(key);
        return v == null || v.isBlank() ? def : Boolean.parseBoolean(v.trim());
    }

    /**
     * 解析场景行列表为步骤序列。
     *
     * @param lines 场景文件原始行
     * @param vars  变量表（{@code ${name}} 替换来源）
     */
    public static List<ScenarioStep> parse(List<String> lines, Map<String, String> vars) {
        java.util.ArrayList<ScenarioStep> steps = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            Type type = parseType(tokens[0], i + 1, raw);
            Map<String, String> params = new LinkedHashMap<>();
            for (int t = 1; t < tokens.length; t++) {
                int eq = tokens[t].indexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("scenario L" + (i + 1) + ": 参数缺少 key=value: " + tokens[t]);
                }
                params.put(tokens[t].substring(0, eq), substitute(tokens[t].substring(eq + 1), vars));
            }
            steps.add(new ScenarioStep(type, params, i + 1, raw));
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("scenario: 没有任何步骤");
        }
        return List.copyOf(steps);
    }

    private static Type parseType(String token, int lineNo, String raw) {
        // 大小写不敏感 + 忽略下划线：assertProbe / ASSERT_PROBE 均命中 ASSERT_PROBE
        String norm = token.toUpperCase().trim().replace("_", "");
        for (Type t : Type.values()) {
            if (t.name().replace("_", "").equals(norm)) {
                return t;
            }
        }
        throw new IllegalArgumentException("scenario L" + lineNo + ": 未知原语 '" + token + "' in: " + raw);
    }

    private static String stripComment(String line) {
        int idx = line.indexOf('#');
        return idx >= 0 ? line.substring(0, idx) : line;
    }

    /** 替换值中的 {@code ${name}}；未定义变量保持原样（便于排查）。 */
    static String substitute(String value, Map<String, String> vars) {
        StringBuilder out = new StringBuilder(value.length());
        int pos = 0;
        while (true) {
            int start = value.indexOf("${", pos);
            if (start < 0) {
                out.append(value, pos, value.length());
                return out.toString();
            }
            int end = value.indexOf('}', start + 2);
            if (end < 0) {
                out.append(value, pos, value.length());
                return out.toString();
            }
            out.append(value, pos, start);
            String name = value.substring(start + 2, end);
            String rep = vars.get(name);
            out.append(rep != null ? rep : "${" + name + "}");
            pos = end + 1;
        }
    }

    @Override
    public String toString() {
        return "L" + lineNo + ": " + rawLine.trim();
    }
}
