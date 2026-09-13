# PocketHarness

手机原生轻量 AI Agent Harness（Android，纯 Kotlin，无第三方重依赖）。

- **自定义 LLM**：任意 OpenAI 兼容端点（/v1/chat/completions），自填 Base URL + Key + 模型名，支持 function calling（工具循环上限 8 轮防失控）
- **手机操作**：无障碍服务读屏（控件树→紧凑文本，含坐标/可点击/可输入标记）+ 手势 API 执行 tap/swipe/输入/返回/主页
- **文件传输**：/sdcard 读写（与 OpenClaw 容器共享存储，天然互通）

## 架构

```
ui/MainActivity      单 Activity 聊天界面 + 设置面板
AgentEngine          消息历史 + 工具循环（tool_calls→执行→回填）
llm/LlmClient        OpenAI 兼容 HTTP 客户端（HttpURLConnection，零依赖）
a11y/HarnessAccessibilityService  读屏 + 手势
tools/DeviceTools    手机操作工具集（get_screen/tap/swipe/input_text/back/home）
tools/FileTools      /sdcard 文件工具（list/read/write，路径防穿越）
```

## 使用

1. 安装 APK → 打开 → 点状态条去系统设置开启无障碍服务
2. 设置面板填 Base URL / API Key / 模型名
3. 下达指令，例如："打开设置里的电池页面，截图看看什么在耗电" / "把 Download 里最新的两张照片的信息列出来"

## 构建

GitHub Actions 自动构建（push main 触发），产物在 Actions artifact。

## 安全设计

- 涉及支付/发消息给他人的动作要求先确认（system prompt 层约束）
- 文件工具无删除能力（硬编码拒绝）
- 路径穿越防护：/sdcard 之外不可读写
