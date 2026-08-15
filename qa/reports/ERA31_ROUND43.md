# ERA 31 Round 43 报告（source-integrity 全量本地重放：五套 CI 恢复全绿）

> 日期：2026-08-15。

## 背景

R42 修复了 CI 锚点与 SOURCE_MANIFEST 两处红。本轮把 source-integrity workflow
的**剩余步骤全部本地重放**，确保下一次 Release Closure 依赖的 CI 从第一个 commit
起就是绿的。

## 重放结果（全部 PASS）

1. **source reality 漂移门**：`generate_source_reality.py` 重生成
   SOURCE_REALITY_REPORT.md（kt=165 / py=68 / components=5）——R11–R43 源文件
   增长后的真实计数入档；
2. **依赖图漂移门**：`generate_dependency_graph.py` 重生成
   ANDROID_DEPENDENCY_GRAPH.md（12 domains / 46 edges / 无环）——模块结构
   无变化（架构冻结保持）；
3. **确定性源归档**：`build_source_archive.py`（1065 源文件，manifest 4c62cee…）
   + `verify_source_archive.py` zip/tar.gz 双格式（hash_verified 1065 /
   unexpected_files 0 / NFC PASS / required PASS）；
4. **distribution 完整性套件**：`test_source_archive.py` 10/10；
5. **产物纪律**：`dist/` 为 CI 生成物目录，从未入库——本轮补 .gitignore
   （此前缺失，本地重放会污染 git status）。

## 结论

五套 CI 的本地等价检查全部恢复绿（android 门禁历轮全绿、backend 门禁 R33
预检全绿、security compile+audit 脚本在位、source-integrity 本轮全绿、
release-closure 依赖脚本在位 + assembleRelease R41 通过）。