# qa/visual-review — Real Render Review 工件

> ERA 31 §5/§6。目的只有一个：**让人直接看到最终视觉**。这不是测试框架，是人眼评审画廊。
> 渲染的全部是 production 管线：`QaTimeline`（长程 fixture）→ Presence → EchoVisualMapper →
> `computeEchoSceneFrame` → production `renderEchoFrameToCanvas`（android.graphics）。

## 如何生成

```bash
cd android
JAVA_HOME=<JDK17> ./gradlew :feature:qa:testDebugUnitTest --tests "com.yunjue.echo.mind.qa.VisualReviewRenderTest"
```

- 输出目录默认 `<repo>/qa/visual-review/rendered/`（覆盖系统属性 `-Decho.visualReview.out=...` 可改）。
- 渲染全部确定性：同一 profile/day/surface 恒同帧；PNG 是人看工件，**字节级回归不承诺**（帧哈希回归由
  `VisualRegressionGoldenTest` 承担）。
- 生成后把 `rendered/` 变更提交，作为下一轮评审基线。

## 工件布局

```
qa/visual-review/
  README.md                  ← 本文件
  DEVICE_CHECKLIST.md        ← 真机 Wallpaper 验证清单
  rendered/
    <PROFILE_ID>/
      day000/  scene_{APP,HOME_WALLPAPER,LOCK_SAFE,DREAM,CANONICAL_JOURNEY}.png + snapshot.md
      day003/  ...（SNAPSHOT_DAYS = 0/3/7/28/90/180）
      day007/  ...
      day028/  day090/  day180/
    sheets/
      users_APP_day{0,7,90,180}.png        ← 人眼必答 1：不同用户是否明显不同
      users_WALLPAPER_day{7,90}.png        ← 同上（壁纸面）
      continuity_{APP,WALLPAPER}_<PROFILE>.png ← 人眼必答 2：同一个 ECHO 在成长
      maturity_APP_<PROFILE>.png           ← 成熟度演进 SEED→DISCOVERING→EMERGING→KNOWN→MATURE
      _MANIFEST.txt                        ← 拼图清单
```

每个 `snapshot.md` = 参数快照 + 状态解释（maturity / Life Season / Identity / Ambient / Visual params /
三层 headline / 人话「为什么这一天看起来这样」）。

## 人眼评审流程（每轮视觉改动后）

1. 打开 `sheets/users_APP_day180.png`：7 个用户不看名字，能否一眼区分？（不能 → 视觉身份差异不足，Product FAIL）
2. 打开 `sheets/continuity_APP_<PROFILE>.png`：Day 0→180 是否像同一个 ECHO 在长大，而不是五个不同生物？
3. 打开 `sheets/users_WALLPAPER_day90.png` 盯着看：5 秒有生命感？1 小时不烦？值不值得留 7 天？
4. 抽查任意 `scene_*.png` + `snapshot.md`：参数与画面是否相符？解释是否克制、只说事实？
5. 结论与修复记录写入 `qa/reports/PRODUCT_EXPERIENCE_REVIEW.md`（或本轮对应报告），并把新的 PNG 提交为基线。

## 设计约束（防过度工程）

- 不建 golden 对比框架、不建视觉 diff 阈值表——那些属于既有 `VisualRegressionGoldenTest`（帧哈希）。
- 本目录只允许：渲染器（production 调用）、生成测试、画廊文档、真机清单。
- 新增 surface/profile 必须有产品评审问题驱动，不允许为了覆盖而扩矩阵。
