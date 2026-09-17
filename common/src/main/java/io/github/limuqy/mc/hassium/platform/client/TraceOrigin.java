package io.github.limuqy.mc.hassium.platform.client;

/**
 * 区块数据来源归因（诊断链路；绝不编码进网络包或存档）。
 * <p>
 * 从 {@code ClientChunkHandler.TraceOrigin} 迁出，供 shadow / client 共用，
 * 避免 shadow 依赖客户端业务包。
 */
public enum TraceOrigin {
    /**
     * 经 chunk_payload 归一通道到达的 FULL（Compare+Pull 响应正文 / 影子 tracking
     * 本地存货 / 拦截模式先达数据）；历史命名为“服务端推送”，pull 模式下
     * 不代表服务端自主灌输——稳态期间该来源应为零。
     */
    SERVER_PUSH("server_push"),
    /** Compare+Pull 响应 FULL 落地（影子 tracking 采集；非服务端自主推送）。 */
    REMOTE_PULL("remote_pull"),
    SHADOW_MEMORY_CACHE("shadow_memory_cache"),
    SHADOW_DISK_CACHE("shadow_disk_cache"),
    LOCAL_GENERATION("local_generation"),
    /** 分段增量就地合并后回传；不是服务端整柱直推，不得记入全量 miss。 */
    SECTION_DELTA("section_delta");

    private final String logValue;

    TraceOrigin(String logValue) {
        this.logValue = logValue;
    }

    public String logValue() {
        return logValue;
    }
}
