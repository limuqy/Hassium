# 聚合 v3 与模板策略迁移至 2.0.0 分支（2026-10-14）

状态：移植与验收完成。本次提交同时记录移植代码和验证结果，目标分支为 2.0.0。

目标基线 `cb23e2ea`（2.0.0 分支，发布版本字段保持 2.0.4），来源为 master 的 `5cbe074d`（出口 v3）与 `158535f6`（模板学习、准入与退避）。没有迁入 26.x 的版本属性、Forge 删除或升级规划文件。优化只接管服务端出口 PLAY，现有区块核心、实体节流及客户端上行策略保持原状。

## 适配边界

- `compat.EgressProtocol` 保留实际编码绑定：1.21.1+ 使用 ProtocolInfo；1.20.1 使用编码开始时的 ConnectionProtocol/PacketFlow。原始 Mod 编码结果按输出 ByteBuf 身份关联，只编码一次。
- Forge 的聚合载体沿用原生 SimpleChannel（hassium:main），通过自有空载体探测包 ID 和消息 discriminator。普通第三方包不拆解、不重造。
- 编码器/解码器绑定用接口桥接；避免把普通 class mixin 当作目标类的 Java 父类。
- 类型化夹具通过 compat 处理 biome holder、原生 payload wrapper、发送回调与实体类命名；加载器模块只注册原生 codec/receiver。
- 原文/历史/模板算法与已提交的模板改进保持一致。仍采用 64 KiB 历史、事务式模板同步、完整帧大小择优及有界退避，无新增用户配置键。
- Forge/NeoForge 的通道允许缺失，已安装旧协议的端仍需双端升级到 v3。

## 运行时发现与修复

旧 Fabric 接收夹具在 Bundle 已位于主线程时再次排队，导致夹具观测顺序错乱。`receiveOnClient` 在已位于主线程时直接处理，否则只调度一次；不改变第三方原生 receiver。

Forge 载体探测原先先消费包 ID，再让加载器检查完整封装，读指针错位。包 ID 探测改用 duplicate，原始探测缓冲保持起始位置。回放新增两字节 discriminator 的测试。

Forge 1.20.1 的首次登录在新 setProtocol TAIL 钩子启用时停滞。未改动基线 classic 通过；临时去掉 Connection mixin 后 v3 与夹具通过；最终仅去掉旧版 setProtocol 钩子，保留回调、严格错误处理及断连清理后两轮专项通过。1.20.1 没有配置重入阶段，聚合在进入 PLAY 后才安装；FIFO 恢复钩子仅挂在 1.21.1+ 的 setupInboundProtocol。没有关闭 v3 或旁路旧 Forge 优化。

## 编译与单测

七个锚点按 versionProperties 的 builds_for 共 18 个加载器组合编译通过，并运行 common:test 与 scanVersionBoundaries。1.20.1 有 423 个测试，其余六锚点各 416 个，均无失败或错误。日志位于本任务工作树 build/egress-2x/final-<version>.log；最后生命周期修改后，1.20.1 的 common:test、Fabric/Forge 编译和边界扫描补跑通过（post-lifecycle-1.20.1.log）。

同一 checkout 的不同 mc_ver 构建/游戏不得并行：根 build.properties 的 Manifold 定义共享。一次 1.21.9 编译被并行的 1.20.1 冒烟覆盖定义；串行重跑通过，未据该执行错误修改无关区块代码。

## 专项与回放

六个有效 egress 会话均两轮通过：1.20.1 Fabric/Forge、1.21.1 Forge/NeoForge、1.21.11 Fabric/NeoForge。每轮 512 个消息，验证具体类型、注册表 holder、变长字符串/列表、同一通道的两种结构、Bundle、回调、重连和一次性事件。有效会话及完整结果见 egress-v3-2x-smoke-20261014.csv；临时禁用 mixin 的诊断会话不计入验收。

工厂 20/100/200 三份采集用迁移版 EgressCaptureReplay 重放，三个模式都逐包核对原始字节、顺序和 CRC，并检查完整帧不超过同协议独立字典基线。发送算法改进后的重放显式 verifySelected=false，不要求重现旧策略选中的帧。四遍运行丢弃首遍预热；CPU 是 codec 的线程 CPU，非服务器总 CPU 或端到端延迟。

报告：factory-egress-v3-2x-replay-{20,100,200}-20261014.csv。活跃工厂仍无模板命中或定义，未发现额外稳定带宽收益；算法与来源提交相同。历史同机旧/新模板策略 CPU 对照仍见 factory-egress-v3-template-comparison-20261013.csv，本次移植回放不是新的配对 CPU 对照。

提供的工厂 ZIP 属于 Minecraft 26.1.2，不能向 1.x 降级加载。三份报告仅证明不透明字节算法的迁移一致性；2026-10-13 的 26.x 冒烟/性能 CSV 保留为历史参考，不作为 2.x 原生收益承诺。
1.21.11 Fabric 的 dimension、dictionary、flyroundtrip 回归均通过。非 classic 场景使用自身探针门禁，dimension 的通用 Round2Pass 字段不作为该场景最终判据。flyroundtrip 首次功能探针通过，外部 Mod Menu 更新检查因 DNS/连接失败触发 ERROR 门禁；保留首次 FAIL 记录，并仅给第二次会话传入精确 AllowErrorPatterns（ModMenu/Update Checker/Fabric Loader/ERROR\].*Failed Fabric Loader update check!$），复跑结果 PASS，未放宽 Hassium/MC 协议错误检查。