# ERA 31 Round 12 报告（ERA 32 §8 ECHO Scene 全链路走查）

> 日期：2026-08-15。按 §8 从用户角度走查全链路并记录「哪里像 App / Dashboard / settings / 普通 AI / 真的像 ECHO」。

## 走查记录

| 步骤 | 现状 | 判定 |
|---|---|---|
| 首次启动 → Welcome → Privacy → Core sensing → Awakening → ECHO | 三步 + 自动过渡（R6 审计） | ✅ 像 ECHO（授权完成不停留） |
| 进入 ECHO 第一视觉 | `visualSurface()` 第一项 ✓；**但第二项是大字号日期（headlineMedium）** | ⚠️ Dashboard 噪音（发现） |
| 一句话 | R11 起自然句 summary 优先 | ✅ 像 ECHO |
| Why | 事实行无边框 + 展开（R1） | ✅ |
| Ask ECHO | 确定性个人回答 26/26 + AI 路径；「参考了/没有使用」双清单 | ✅ 像 ECHO（只有它知道） |
| Action | 只相关时出现（Intervention L2 门禁） | ✅ |
| Journey | 视觉河流第一眼（R5）+ 时间地标（R6） | ✅ 看见自己的时间 |
| 返回 ECHO | 状态保留（StateFlow） | ✅ |
| 像 settings 的部分 | 无——设置全在 Me | ✅ |
| 内部 diagnostics | 仅 DEBUG（InternalQualityFeedback） | ✅ production 不出现 |

## 修复（§9/§10）

Scene 第二项大字号日期「今天 · MM月dd日」（headlineMedium）与 ECHO 抢第一视觉——
日期是元数据，不是 ECHO。降为 `labelMedium` 安静时间锚：
**ECHO → 安静日期 → 自然句 → Why → Ask**。

- 回归：EchoSceneContentSmokeTest 日期锚点仍通过（文本不变，样式降级）。
- 审计文件更新：`qa/reports/ECHO_SCENE_AUDIT.md` §3.5。

## 实测

- Android 1005 全绿 + detekt + lint PASS；快照/黄金零漂移。
