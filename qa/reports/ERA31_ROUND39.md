# ERA 31 Round 39 报告（AI 设置界面标签中文化：混合语言退役）

> 日期：2026-08-15。

## 走查发现

走查 Me → AI Intelligence 设置面（最后一个未走查的 Me 分面），发现界面标签
**中英混排**：状态行用英文标签「Current provider：OpenAI Compatible /
Model：gpt-echo-1 / Status：连接正常 / Advanced（高级）」，而全仓其余
用户界面是中文——中英混排破坏 10 秒可读（gate #7/#9）。

## 修复（IntelligenceSettingsSection）

| 旧 | 新 |
|---|---|
| Current provider：OpenAI Compatible | 当前服务：OpenAI 兼容接口 |
| Model：… | 模型：… |
| Status：… | 状态：… |
| Advanced（高级） | 高级设置 |

Base URL / API Key 属协议专名保留英文（Base URL：…、API Key 输入框标签不动）。

## 回归

- `IntelligenceSettingsContentSmokeTest` 三处锚点同步（当前服务/模型：/高级设置）。

## 实测

- Android 1032 全绿 + detekt + `:app:lintDebug` PASS（本轮无新增测试——既有锚点
  语义更新）。