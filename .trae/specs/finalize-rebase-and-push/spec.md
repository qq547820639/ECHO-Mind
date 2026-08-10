# 交付收尾：Rebase 完成与推送 Spec

## Why
待明确项 P1-P5 的代码、测试、文档已全部完成并通过（825 个测试全绿），所有变更已 stage 到当前 rebase 会话。rebase 处于"所有冲突已解决，等待 continue"状态，需要 finalize 后推送到远程，完成本次交付闭环。

## What Changes
- 执行 `git rebase --continue` 完成 rebase（保留原 commit message `feat: 被动感知范式全量落地 + 待明确项收尾`）
- 推送 `main` 分支到 `origin/main`（fast-forward，无需 force）
- 确认本地与远程 HEAD 一致，清理 rebase 残留状态

## Impact
- Affected specs: `pending-items-resolution`（本次推送即其交付物上线）
- Affected code: 无代码变更，仅 git 历史 finalize
- 不纳入提交：`.trae/`、`.codebuddy/`、`.workbuddy/` 为 IDE/工具内部文件，保持未跟踪

## ADDED Requirements
### Requirement: Rebase 完成
系统 SHALL 在所有冲突解决后，通过 `git rebase --continue`（配合 `GIT_EDITOR=true` 接受原 commit message）finalize rebase 会话。

#### Scenario: 成功完成 rebase
- **WHEN** 执行 `git rebase --continue`
- **THEN** `.git/rebase-merge/` 目录被清除，`git status` 显示 `nothing to commit, working tree clean`，新 commit 位于 onto 基点 `829000a` 之上

### Requirement: 推送到远程
系统 SHALL 通过 `git push origin main` 将本地 `main` 推送到 `origin/main`。

#### Scenario: fast-forward 推送成功
- **WHEN** 本地 `main` 是远程 `origin/main`（`829000a`）的 fast-forward 后继
- **THEN** 推送成功，无需 `--force`，本地与远程 HEAD 指向同一 commit

#### Scenario: 远程有新提交导致非 fast-forward
- **WHEN** push 被拒绝（non-fast-forward）
- **THEN** 执行 `git pull --rebase` 后重试，**禁止**使用 `--force` 推送到 main

## MODIFIED Requirements
无

## REMOVED Requirements
无
