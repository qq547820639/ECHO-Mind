# ECHO Mind — Master Roadmap

> 维护者：Agnes (Long-term Implementation Agent)  
> 当前 HEAD：`45baef2` | 版本：v0.11.0 | 状态：pilot-candidate  
> 最后更新：2026-08-24

---

## Phase 1：基础稳定 ✅

| Epic | Feature | Task | Commit | Release |
|---|---|---|---|---|
| E1: Observation Core | Passive Sensing → Derived Features → Daily Aggregate → Personal Baseline → Portrait | 全链本地+云端双实现 | （历史） | v0.7.0 |
| E2: 三世界架构 | ECHO / Journey / Me 信息架构收敛 | ui/EchoMindApp 三 tab | （历史） | v0.9.0 |
| E3: ECHO Presence | App 画报 + 动态壁纸 + Dream 屏保 | 同一 EchoPresenceState | （历史） | v0.9.0 |
| E4: Day-0 初见 | SEED ECHO 确定性构建 | dayZeroSeedPresence | （历史） | v0.10.0 |
| E5: BYOM AI | OpenAI 兼容 Provider + Context Compiler + Grounding | AiProviderManager | （历史） | v0.10.0 |
| E6: EchoMemory | 7 类记忆 + 生命周期 + 纠错闭环 | EchoMemory | （历史） | v0.10.0 |
| E7: PersonalAnswerEngine | 16 回答族 26/26 Core Set | PersonalAnswerEngine | （历史） | v0.11.0 |
| E8: Release v0.11.0 Closure | SOURCE_MANIFEST/SBOM/provenance/artifact | 发布元数据闭环 | 88db3b9 | v0.11.0 |

## Phase 2：核心体验完善（进行中）

| Epic | Feature | Task | 状态 | 阻塞 |
|---|---|---|---|---|
| E9: 真机验证 | Wallpaper 电池/帧率/进程死亡 | DEVICE_CHECKLIST 执行 | ⏳ BLOCKED | BLOCKED_EXTERNAL_DEVICE |
| E10: Dogfood 30天 | 真实数据回流 → 六类缺陷 → fixture 化 | DOGFOOD_PROTOCOL 执行 | ⏳ BLOCKED | BLOCKED_EXTERNAL_LONG_RUN |
| E11: P2 FOLLOW_UP 清偿 | T2-P2-2/T2-P2-5/T3-P2-4/T4-P2-6/T6-P2-4/T6-P2-8/T7-P2-5/T8-P2-5 | 8 项软件侧可独立处理 | 🔄 IN_PROGRESS | — |
| E12: 人眼视觉评审 | qa/visual-review/index.html 人眼结论 | 画廊人工复核 | ⏳ PENDING | 多模态 Agent 缺失 |
| E13: Memory Consolidation | 真实 dogfood 数据评估 consolidation 阈值 | 评估后实施 | ⏳ BLOCKED | 需 E10 数据 |

## Phase 3：智能能力增强

| Epic | Feature | Task | 状态 | 阻塞 |
|---|---|---|---|---|
| E14: Affective Intelligence | §1-§7 实现 | affectiveState 写入路径 | ❄️ FROZEN | AFFECTIVE_CONTRACT §8/§9/§10 人工评审 |
| E15: 长周期分析消息 | 订阅用户 7/30 天节律报告 | push notification | 📋 BACKLOG | 需云端基础设施 |
| E16: AI 叙事增强 | daily narrative AI 降级阈值优化 | NarrativeDistiller | 📋 BACKLOG | — |

## Phase 4：商业化准备

| Epic | Feature | Task | 状态 | 阻塞 |
|---|---|---|---|---|
| E17: 生产签名 | 运营签名环境执行 | APK/AAB 生产签 | ⏳ BLOCKED | BLOCKED_EXTERNAL_PRODUCTION_SIGNING |
| E18: 外部渗透 | 第三方安全审计 | penetration test | ⏳ BLOCKED | 外部供应商 |
| E19: 试点治理 | 责任矩阵/临床签署/法务定稿/伦理审查 | pilot-pack 全套 | ⏳ BLOCKED | 机构流程 |
| E20: 设备矩阵回归 | ≥8 台真实设备 | regression | ⏳ BLOCKED | 物理设备 |

## Phase 5：生态扩展

| Epic | Feature | Task | 状态 | 阻塞 |
|---|---|---|---|---|
| E21: Wearable 真机量产 | Band10 真机安装+连接 | Interconnect | ⏳ BLOCKED | BLOCKED_EXTERNAL_XIAOMI_SDK + BAND10_DEVICE |
| E22: ANSWatch 集成 | 研究观察管线 | ANS_FRAME_V1 | ⏳ BLOCKED | BLOCKED_EXTERNAL_ANS_HARDWARE |
| E23: 云端同步增强 | 多设备记忆同步 | cloud sync | 📋 BACKLOG | 产品决策 |

---

## 当前工作焦点

### 立即（本轮会话）

1. **E11: P2 FOLLOW_UP 清偿** — 逐项处理 8 项遗留
   - T2-P2-2: OrganismTopology 盐空间冲突（需黄金集再生，FOLLOW_UP）
   - T2-P2-5: AGSL 丢弃多层（需 KDoc 补能力矩阵）
   - T3-P2-4: SCREEN_TIMING 量-时混义（需产品决策，FOLLOW_UP）
   - T4-P2-6: ACKNOWLEDGED 不可达（需产品确认，FOLLOW_UP）
   - T6-P2-4: 升级 trigger 豁免绕过（需安全评审，FOLLOW_UP）
   - T6-P2-8: scoring.py 死代码退役（需协议裁定，FOLLOW_UP）
   - T7-P2-5: Band10 模拟器漂移（色板+空态尺寸可顺手修）
   - T8-P2-5: PG migration round-trip 占位 skip（需 CI 基建，FOLLOW_UP）

2. **文档更新** — HANDOVER_REPORT.md 已生成，需补充本轮进展

### 近期（下一阶段）

- 真机验证协议准备（如设备可用）
- 人眼视觉评审（如多模态能力可用）
- v0.12.0 决策（需 Production wiring + 真机安装全绿）
