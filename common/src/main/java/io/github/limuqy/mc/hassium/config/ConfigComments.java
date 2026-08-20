package io.github.limuqy.mc.hassium.config;

/**
 * 配置文件备注：中英文分行（NightConfig {@code setComment} 遇 {@code \\n} 写成两行 {@code #}）。
 */
public final class ConfigComments {
    private ConfigComments() {
    }

    /** 中文一行、英文一行；任一侧为空则只保留另一侧。 */
    public static String bilingual(String zh, String en) {
        String z = zh == null ? "" : zh.trim();
        String e = en == null ? "" : en.trim();
        if (z.isEmpty()) {
            return e;
        }
        if (e.isEmpty()) {
            return z;
        }
        return z + "\n" + e;
    }

    /** Forge/NeoForge ConfigSpec {@code builder.comment(String...)} 用。 */
    public static String[] lines(String comment) {
        if (comment == null || comment.isEmpty()) {
            return new String[]{""};
        }
        return comment.split("\n", -1);
    }
}
