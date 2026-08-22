# AI 辅助功能测试（minecraft-mod-mcp）

面向 AI 代理的游戏内功能测试操作手册：经 MCP（Model Context Protocol）stdio 桥驱动一个**正在运行的真实 Minecraft 客户端**，做截图、读 F3 调试数据、点击 UI、执行命令等人工专项验证。

## 定位

- **覆盖分层中的 L3 人工专项**：与自动冒烟（L0–L2，见 [`runtime-smoke-test.md`](runtime-smoke-test.md)）互补，处理自动化脚本不适合覆盖的主观/交互类验证——UI 布局、渲染观感、实体行为、操作手感等。
- **不进自动 PASS 门禁**：本工具链的产出是观察记录与结论，不产出退出码 / result JSON，不会被 `runtime-smoke-test*.ps1` 的任何门禁消费。PASS 判定永远只来自 L0–L2 自动链路。
- **按需使用**：适合排查「冒烟 FAIL 但日志看不出原因」「新特性需要人眼确认」的场景。

## stdio 桥配置

MCP 服务端以 stdio 方式启动，配置在 `.cursor/mcp.json`：

```json
{
  "mcpServers": {
    "minecraft-mod-mcp": {
      "command": "npx",
      "args": ["-y", "minecraft-mod-mcp"]
    }
  }
}
```

`npx -y` 会按需拉取并运行 `minecraft-mod-mcp` 包；桥进程通过其配套 companion mod 与游戏内客户端通信（见下文「companion mod 前提」）。

## 可用能力清单（按 MCP 工具面）

| 工具面 | 能力 | 典型用途 |
|--------|------|----------|
| `get_minecraft_status` | 游戏进程 / mod 连接状态 | 确认桥已接线、客户端在跑 |
| `screenshot` / `screenshot_to_file` | 截取当前游戏画面（带坐标网格叠加） | 渲染观感、UI 布局、黑块/光照异常目视取证 |
| `debug_fields` | F3 调试屏数据（FPS、坐标、生物群系、维度等） | 客户端状态客观核对（切维是否生效、位置是否正确） |
| `get_player_info` | 玩家状态：位置、朝向、血量、饥饿、游戏模式、维度 | 行为断言前置检查 |
| `click` / `click_button_id` / `click_button_index` | 鼠标点击（坐标 / 按钮 ID / 按钮索引） | 驱动 GUI（如 Hassium 配置界面走查） |
| `hotkey` / `press_key` | 键盘按键与组合键 | 打开菜单、切换视角 |
| `type_text` / `paste_text` | 文本输入 | 聊天框 / 输入框填内容 |
| `open_chat` + `execute_command` | 打开聊天框、执行斜杠命令 | `/hassiumc stats`、`/execute in ... run tp ...` 等，配合冒烟场景手工复现 |
| `enter_control_mode` / `exit_control_mode` | 进入/退出控制模式（释放鼠标、启用键鼠注入） | 一切键鼠操作的开关；需先由玩家侧激活入口 |
| `look_delta` / `scroll` / `mouse_drag` / `place_block` / `use_item` / `right_click` | 视角转动、滚轮、拖拽、放方块、使用物品 | 交互路径演练 |
| `enumerate_widgets` / `get_screen_buttons` / `switch_tab` | 枚举控件树 / 当前屏幕按钮 / 分页 GUI 切页 | UI 自动化定位元素 |
| `close_screen` / `pause_game` | 关闭当前屏幕 / 暂停游戏 | 状态复位 |
| `get_world_info` | 世界状态：seed、时间、天气、难度、已加载区块、实体 | 场景环境核对 |
| `detect_java` / `list_accounts` / `create_offline_account` | Java 环境检测、离线账号管理 | 测试环境准备 |
| `launch_minecraft` / `kill_minecraft` / `install_version` / `install_server` / `serve` / `ping` / `wait` | 启动/终止游戏实例、安装版本与服务端、一键服务端+客户端互联 | 独立测试实例编排 |

> 以上为工具面概览；实际入参 schema 以各 MCP 工具的声明为准（会话内可用 `xd://` 设备文档查看完整签名）。

## 与 minecraft-dev 的职责边界（禁止混用）

仓库同时挂有另一组 Minecraft 相关 MCP 工具面（`minecraft_dev_*`），两者职责完全不同：

| | minecraft-mod-mcp（本文档） | minecraft-dev |
|---|---|---|
| 对象 | **运行中的游戏实例**（进程内 companion mod） | **MC 源码 / 字节码**（反编译、映射、Mixin 校验） |
| 用途 | L3 人工功能测试：截图、点击、命令、世界状态 | 开发期查阅：`decompile_minecraft_version`、`search_minecraft_code`、`analyze_mixin`、`find_mapping` 等 |
| 产出 | 观察记录 / 截图证据 | 源码事实 / 映射答案 |

**禁止混用**：不要用 minecraft-dev 去操控游戏（它做不到），也不要用 minecraft-mod-mcp 去查源码/映射（应走 minecraft-dev）。判断口诀：**动游戏找 mod-mcp，看代码找 dev**。

## companion mod 前提与现状

- **前提**：minecraft-mod-mcp 需要其配套 companion mod 安装进目标 MC 实例（作为 mod 加载），桥进程才能与客户端通信；未安装时所有游戏内工具面不可用。
- **当前 dev 环境未接线**：Hassium 各 loader 子项目的 loom dev 运行配置（`runClient`）**尚未挂载该 companion mod**——即经冒烟脚本起的 dev 客户端目前不能被本工具链驱动。要做 L3 测试需先：
  1. 在目标实例（dev runClient 的 mods 目录，或独立启动器实例）装入 companion mod；
  2. 启动游戏并确认 `get_minecraft_status` 能 ping 通；
  3. 再进入控制模式开展测试。
- 接线方案（dev mods 目录注入或独立实例）待定，落地后更新本节。

## 与自动冒烟的协作建议

1. 自动冒烟 FAIL → 先按 [`runtime-smoke-test.md`](runtime-smoke-test.md) 失败诊断清单走日志/probe 分析；
2. 日志无法定位时，用本工具链手动复现同一场景（同版本同加载器起服，`open_chat` + `execute_command` 重放关键命令，`debug_fields` / `screenshot` 取证）；
3. 结论回写 issue/handoff，必要时沉淀为新的 `.scenario` 场景或门禁（升级到 L1/L2 自动化）。
