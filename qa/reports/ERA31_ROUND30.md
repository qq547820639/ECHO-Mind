# ERA 31 Round 30 报告（文案一致性收口：引号规范 + 重新生成按钮人话化）

> 日期：2026-08-15。

## 走查发现

全局文案风格走查收口（前几轮逐面走查后的小残留）：

1. **WARMING_UP_TEXT 引号风格不统一**：Learning 期第一句用全角双引号
   （“今天”和“平常的你”），全仓其余文案统一用「」（Android + backend 镜像同此）；
2. **「重新生成」按钮**：画像反馈补救路径的按钮叫「重新生成」——工程动词
   （rebuild），用户语境应是「重新看看今天」。

## 修复

1. Android `LocalPortraitEngine.WARMING_UP_TEXT` 与 backend `narrative.py`
   `WARMING_UP_TEXT` 镜像同改：「今天」「平常的你」；
2. `PORTRAIT_COPY_REGENERATE`：「重新生成」→「重新看看今天」。

## 实测

- Android 1025 全绿 + detekt + `:app:lintDebug` PASS；
- backend pytest 1077 passed + 1 skipped 全绿 + ruff 0 + mypy strict 0。
