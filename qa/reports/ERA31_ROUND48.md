# ERA 31 Round 48 报告（Fresh Clone 完整性验证：清单重生成纪律修复）

> 日期：2026-08-15。

## 走查发现

做 clean checkout 完整性验证（本地克隆，网络克隆因 GitHub 连接超时改用
`--no-hardlinks --local`，语义等价），发现**SOURCE_MANIFEST 又漂移了**：

- R42 重生成的清单 1064 文件；R43–R47 新增 5 份轮报 + 1 个文件 → 清单落后
  （fresh clone 1070 文件 vs 清单 1064）→ source-integrity CI 在 R43–R47
  的每个 commit 上又会红。

根因：轮次纪律里「新增文件 → 重生成 SOURCE_MANIFEST」没有落地——
清单重生成被当作 closure 专属动作，但 CI 的防漂移门是**每个 commit** 都执行的。

## 修复

1. `update_release_metadata.py` 重生成 SOURCE_MANIFEST（1064 → **1070**）+
   DELIVERY_MANIFEST 刷新 → verify_source_manifest PASS；
2. **纪律写入轮次约定**：今后每轮若新增/删除 git 受控源文件，收口时必须
   重生成 SOURCE_MANIFEST（CI source-integrity 逐 commit 硬校验）——
   与本轮起在 IMPLEMENTATION_STATUS 的轮次约定中记录。

## 实测（fresh clone + 主树）

- 契约锚点 24/24 PASS；verify_source_manifest PASS（1070）；
- 确定性归档双格式验证 PASS + test_source_archive 10/10（fresh clone 全过）；
- backend 1077/0/1 注入 DELIVERY_MANIFEST 实测。