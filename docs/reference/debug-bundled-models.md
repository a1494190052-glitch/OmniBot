# Debug 包内置模型配置

`developStandardDebug` 可以在构建时把云端 LLM 配置写入 APK，并在首次启动时自动创建默认配置：

- Agent provider：`https://llmapi.paratera.com`
- Agent model：`DeepSeek-V4-Pro`
- VLM provider：`https://llmapi.paratera.com`
- VLM model：`Qwen3-VL-235B-A22B-Instruct`

API key 仅从本机环境变量或私有 Gradle 配置读取，不应提交到仓库：

```bash
export LLMTHU_API_KEY='your-key'
bash scripts/install-dev.sh --device <adb-serial>
```

安装脚本会构建 `developStandardDebug`、安装并启动应用，不会再通过广播二次覆盖 provider。本机已使用 `LLMTHU_API_KEY` 时无需重复设置；debug 构建会优先将它写入 APK。key 会以明文形式进入生成的 `BuildConfig` 和 APK，拿到 APK 的人可以提取它，因此应使用可限额、可吊销、仅用于测试的 key。

可以通过以下 Gradle 属性覆盖默认值：

```properties
OMNIBOT_DEBUG_LLM_BASE_URL=https://llmapi.paratera.com
OMNIBOT_DEBUG_AGENT_MODEL=DeepSeek-V4-Pro
OMNIBOT_DEBUG_VLM_MODEL=Qwen3-VL-235B-A22B-Instruct
OMNIBOT_DEBUG_LLM_PROFILE_NAME=LLM API Debug
```
