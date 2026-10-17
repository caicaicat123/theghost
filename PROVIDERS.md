# 服务商配置说明（1.8.0）

本插件接入纯文本、非流式聊天。以下 15 家均有独立预设，另保留 `openai-compatible` 通用入口。
已完成本地模拟 HTTP 验证，**尚未使用真实账号验证各家连通性、模型权限与额度**。
默认地址依据 2026-10-17 查阅的官方资料填写；地址、模型和地区可能变化，均可自行覆盖。

## 配置方法

1. 在 `config.yml` 中将 `ai.provider` 改为表内规范 ID 或别名。
2. 填写 `ai.providers.<规范ID>.api-key`、`model`，按需调整 `base-url`。
3. `/ghost reload`，然后 `/ghost test 你好`。若替换了 jar，先安排服务器重启加载新版本。

DeepSeek 为兼容旧配置，仍读取根下的 `deepseek.*`。其他服务商不读取这个 Key。
`base-url` 只填基础地址，客户端追加 `/chat/completions`；Anthropic 追加 `/messages`。
模型 ID 不设名单限制；除保留原 DeepSeek 默认值外，模板的模型均留空，由管理员填写控制台实际可用值。
未配置或调用失败时，游戏回复沿用本地台词兜底。

## 服务商与凭据

| 规范 ID | 服务商 / 官方资料 | 填写注意 |
| --- | --- | --- |
| `deepseek` | [DeepSeek](https://api-docs.deepseek.com/) | 保留原 `deepseek.*` 配置与 thinking 开关 |
| `kimi` | [月之暗面 Kimi](https://platform.moonshot.cn/docs/intro) | API Key；国内与海外账号和地址须匹配，别名 `moonshot` |
| `zhipu` | [智谱 AI](https://docs.bigmodel.cn/cn/guide/develop/openai/introduction) | 通用 API Key；预设为通用接口，Coding 套餐需核对专用地址，别名 `glm`、`bigmodel` |
| `minimax` | [MiniMax OpenAI 兼容](https://platform.minimax.cn/docs/api-reference/text-openai-api) | 国内默认 `api.minimax.cn`；其他地区按控制台改地址；部分模型强制思考，模板输出上限 2048 |
| `baichuan` | [百川智能](https://platform.baichuan-ai.com/docs/api?activity=true) | API Key 与账号已开通的模型；不保证旧模型仍开放 |
| `stepfun` | [阶跃星辰](https://platform.stepfun.com/) | API Key，别名 `step`；海外入口按控制台改地址 |
| `doubao` | [豆包 / 火山方舟](https://docs.volcengine.com/docs/ark/compatible-with-openai-sdk?lang=zh) | 使用方舟 API Key；`model` 按控制台填模型 ID 或推理接入点 ID（如 `ep-...`），别名 `ark`、`bytedance` |
| `qwen` | [通义千问 / 阿里百炼](https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope) | 地区与 API Key 对应；默认中国内地兼容入口。模板 `extra-body.enable_thinking: false`，按模型需要修改；别名 `aliyun`、`alibaba`、`dashscope`、`tongyi` |
| `ernie` | [文心 / 百度千帆 V2](https://ai.baidu.com/ai-doc/WENXINWORKSHOP/0m2vwrjws) | 使用千帆 V2 API Key；不使用旧版 OAuth AK/SK；别名 `baidu`、`qianfan`、`wenxin` |
| `hunyuan` | [腾讯混元兼容接口](https://cloud.tencent.com/document/product/1729/111007) | 使用该平台 API Key，不填云 API 的 SecretId/SecretKey；平台正迁移到 TokenHub，新账号按控制台修改地址和模型；别名 `tencent` |
| `spark` | [讯飞星火 HTTP](https://www.xfyun.cn/doc/spark/HTTP%E8%B0%83%E7%94%A8%E6%96%87%E6%A1%A3.html) | **`api-key` 填 HTTP 认证信息中的 APIPassword**；不使用 WebSocket 的 APPID/APISecret；别名 `iflytek`、`xunfei` |
| `openai` | [OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) | API Key 与可用于 Chat Completions 的模型；不接只支持 Responses 的模型 |
| `anthropic` | [Anthropic Claude Messages](https://platform.claude.com/docs/en/api/messages/create) | API Key；`api-version` 默认 `2023-06-01`，别名 `claude` |
| `xai` | [xAI Grok](https://docs.x.ai/developers/model-capabilities/legacy/chat-completions) | API Key 与支持 Chat Completions 的模型，别名 `grok` |
| `mistral` | [Mistral AI](https://docs.mistral.ai/resources/migration-guides) | API Key 与文本聊天模型 |

所有别名都读取规范 ID 对应的配置段。例如 `ai.provider: grok` 仍读取 `ai.providers.xai`。
各家模型差异由管理员按控制台与官方文档配置；这里提供协议入口，不代表所有模型的所有功能都受支持。

## 模型专用参数

`send-temperature: false` 默认不发送温度参数，避免不支持该参数的模型直接报错。
可启用后配置 `temperature`。OpenAI 默认发送 `max_completion_tokens`，其他兼容服务默认发送 `max_tokens`；
通过 `token-parameter` 可在这两者之间切换。Claude 始终用 `max_tokens`。

`extra-body` 是可选 YAML 映射，例如千问：

```yaml
extra-body:
  enable_thinking: false
```

如模型要求其他推理参数，可在对应服务商的 `extra-body` 中添加；嵌套映射也会转为 JSON 对象。
不允许覆盖 `model`、`messages`、`system`、`stream`，避免破坏人设、上下文或非流式解析。
`thinking` 开关仍仅自动发送给 DeepSeek；其他平台若需要此参数，应按其文档在 `extra-body` 中自行配置。

正文内 `<think>...</think>` 以及尚未闭合的思考片段会被过滤，不展示给玩家，也不放入对话记忆。
MiniMax 等强制思考模型仍可能因额度太小返回空文本，需要提高 `max-tokens` 和 `timeout-seconds`。
过滤思考文本不会减少已发生的服务商计费。插件不执行服务商返回的工具调用。

## 未接入的原生协议

按本次范围调整，不提供 Gemini、Cohere、Meta 官方 Llama API 预设或原生适配。
第三方托管或自部署的 Llama 等模型若提供 OpenAI 兼容 Chat Completions，可使用 `openai-compatible`，自行填写地址、Key、模型。

用量只展示服务商报告的 token，未报告的字段显示 0；不据此推算跨服务商费用，账单以各平台为准。
启动网络探测不验证密钥，实际调用以 `/ghost test` 为准。
