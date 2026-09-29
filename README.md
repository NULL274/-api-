# 本地小模型接入工具

在 Android 手机上跑本地大模型，并把模型以 **OpenAI 兼容接口** 反向代理出来，供其他软件接入。

![license](https://img.shields.io/badge/license-MIT-blue)

## 功能

- **本地推理**，全部离线运行，不上传任何数据
- **支持两套运行时**
  - `.litertlm` / `.task`：Google AI Edge Gallery 同款 LiteRT-LM，支持 CPU / GPU / NPU 三档加速
  - `.gguf`：llama.cpp，覆盖绝大多数本地小模型
- **自由选择模型文件**：通过系统文件选择器导入任意本地模型
- **应用内多轮对话**：流式输出，思考链可折叠，显示解码速度（tok/s）
- **多模态输入**：支持图像（需模型支持并在设置中开启）
- **OpenAI 兼容反代**：本机回环 + 局域网两种绑定，支持 API Key 鉴权、SSE 流式输出
- **悬浮球**：反代运行时显示，可拖动，点击回到应用
- **日志导出**：一键导出诊断日志

## 支持的模型

模型文件不随本应用分发，请自行获取。

| 模型 | 格式 | 来源 |
| --- | --- | --- |
| Gemma 3n E2B / E4B | `.litertlm` | Google / litert-community |
| Gemma 3 1B | `.litertlm` / `.task` | litert-community |
| Qwen2.5 0.5B / 1.5B / 3B | `.litertlm` / `.gguf` | litert-community / HuggingFace |
| Qwen3 0.6B / 1.7B / 4B | `.litertlm` / `.gguf` | litert-community / HuggingFace |
| DeepSeek-R1-Distill-Qwen-1.5B | `.litertlm` / `.gguf` | litert-community / HuggingFace |
| Llama 3.2 / Phi-3.5 等 | `.gguf` | HuggingFace |

国内网络可从 [hf-mirror.com](https://hf-mirror.com) 下载。

> 模型文件通常 400MB ~ 4GB，导入时会复制到应用私有目录，请预留足够空间。

## 构建

环境要求：

- JDK 17
- Android SDK，compileSdk 34，build-tools 34.0.0
- Gradle 8.7（仓库内已带 wrapper）
- Kotlin 2.2.21 / AGP 8.5.2

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

依赖仓库使用国内镜像优先、官方仓库兜底，可正常在国内外网络下构建。

## 使用

1. 安装后打开应用，菜单 → **选择模型**，选中本地 `.litertlm` / `.task` / `.gguf` 文件；
2. 首次加载会较慢（引擎编译模型，2~3B 模型可能需要几十秒），之后有缓存会快很多；
3. 在对话框直接提问；
4. 需要给其他软件用时，进入 **设置 → 反代服务**，打开开关。

### 设置项

| 项目 | 说明 |
| --- | --- |
| 加速器 | CPU / GPU / NPU。仅 `.litertlm` / `.task` 支持 GPU、NPU；初始化或推理失败会自动回退 CPU |
| 上下文长度 maxNumTokens | 越大能塞进越长的上下文，但占用更多内存。建议 4096 ~ 16384 |
| topK / topP / temperature | 采样参数 |
| 思考通道 | 是否开启模型思考链（Gemma 4、Qwen3 等支持） |
| 多模态输入 | 是否启用图像输入 |
| 绑定方式 | 局域网（其他设备可访问）/ 仅本机回环 |
| API Key | 默认自动生成，可重置；留空表示不校验 |

## 反代接口

服务默认监听 `0.0.0.0:8080`（局域网模式可关闭，改为 `127.0.0.1`）。启动后用浏览器打开 `http://<手机IP>:8080/` 可以看到当前模型名与接口说明。

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/` | 否 | 信息页 |
| GET | `/v1/models` | 是 | 模型列表（供客户端自动拉取模型名） |
| GET | `/v1/models/{id}` | 是 | 同上 |
| POST | `/v1/chat/completions` | 是 | 对话补全，支持 `stream` |

鉴权头：`Authorization: Bearer <API Key>`

第三方客户端填写：

```
API 地址 / Base URL : http://<手机IP>:8080/v1
API Key            : 设置页里那串 sk-...
模型名             : 用 GET /v1/models 返回的名字，或信息页显示的名字
```

请求示例：

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-你的key" \
  -d '{"model":"any","messages":[{"role":"user","content":"你好"}]}'
```

请求体支持 `temperature` / `top_p` / `top_k` 覆盖应用内默认值；`content` 除字符串外也支持多模态分片数组（`image_url` 的 base64 data URL、`input_audio`）。输入超过上下文上限时会自动从最早的消息开始裁剪。

错误码：`401` Key 不对 · `404` 路径不对（多数是 Base URL 少了 `/v1`）· `400` 请求非法或输入超限 · `503` 应用内未加载模型。

## 项目结构

```
app/src/main/java/com/localmodel/gateway/
├── MainActivity.kt            对话界面
├── SettingsActivity.kt        设置
├── LlmEngine.kt               推理门面（LiteRT-LM / llama.cpp 双运行时）
├── PromptFormat.kt            GGUF 对话模板
├── ThinkingChannelFilter.kt   思考通道标记切分
├── ThinkTagFilter.kt          思考标签切分
├── ProxyService.kt            反代前台服务 + 悬浮球
├── ApiServer.kt               OpenAI 兼容 HTTP 服务
├── ModelStore.kt              模型导入
├── DiagLog.kt                 日志
└── ...
```

## 作者

本项目由同一人持有的两个账号共同维护：

| 平台 | 账号 |
| --- | --- |
| Bilibili | `bilibili一曲笑傲江湖醉`（[主页](https://space.bilibili.com/89111571)） |
| GitHub | [NULL274](https://github.com/NULL274) |

`bilibili一曲笑傲江湖醉`（Bilibili）与 `NULL274`（GitHub）为**同一人持有**，Git 提交署名与软件内署名分属这两个账号。

## 许可

本项目以 [MIT License](LICENSE) 开源，版权归 `bilibili一曲笑傲江湖醉`（Bilibili）与 `NULL274`（GitHub）共同持有。随包分发的第三方组件及其许可见 [THIRD-PARTY.md](THIRD-PARTY.md)。

模型权重不随本项目分发，其许可由各模型发布方规定（Gemma 适用 Gemma Terms of Use，Qwen 为 Apache-2.0，Llama 为 Llama Community License）。
