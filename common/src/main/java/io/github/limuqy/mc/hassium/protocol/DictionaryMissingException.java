package io.github.limuqy.mc.hassium.protocol;

/**
 * 聚合帧解码失败：字典缺失（帧头 epoch 未安装 / 帧要求字典但客户端没有）。
 * <p>
 * <b>本异常在 rollout 门控设计下不应发生</b>：服务端只在全部活跃连接对某字典版本
 * ACK 确认后才切换激活版（{@code DictionaryManager}），超时/旧协议客户端阻断则保持
 * 旧字典（或无字典）继续聚合。若日志出现本异常，说明门控被绕过（bug）——
 * 处置为丢弃该帧并记 ERROR，聚合链路继续用当前字典，不回原版协议。
 */
public class DictionaryMissingException extends IllegalStateException {

    public DictionaryMissingException(String message) {
        super(message);
    }
}
