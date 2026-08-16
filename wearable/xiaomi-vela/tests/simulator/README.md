# Band10 Simulator（QA mirror · Phase 10 渲染级验收）

无 AIoT-IDE 环境下的**渲染级**静态验收工具：

- 视觉参数由**真实生产模块**计算：`src/common/visual/wear_visual.js`（computeVisual）、
  `src/common/presence/presence_cache.js`（降级逻辑）、`src/common/protocol/wear_protocol.js`；
- 摆位/配色逐行镜像 `pages/*/index.ux` 的几何（QA mirror，不是第二套 renderer）；
- 官方尺寸 **212 × 520**；每状态 × 每语言一个 HTML（zh/en 共 26 态）；
- 内置 212×520 布局门：任何元素直径/粒子越界 → 生成即 FAIL；
- 每页内嵌 LAYOUT 探针：headless Chrome `--dump-dom` 输出文本 clamp 状态
  （scrollHeight > clientHeight ⇒ 截断）。

## 用法

```bash
node tests/simulator/generate.js          # 生成 out/{zh,en}/*.html（out/ 不入库）

# 渲染截屏（需要本机 Chrome）
for f in tests/simulator/out/zh/*.html; do
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
    --headless --disable-gpu --screenshot="${f%.html}.png" \
    --window-size=212,520 --hide-scrollbars "file://$PWD/$f"
done

# 文本截断探针
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless --disable-gpu --dump-dom --virtual-time-budget=1500 \
  --window-size=212,520 "file://$PWD/tests/simulator/out/zh/KNOWN.html" | grep -o 'LAYOUT:\[[^]]*\]'
```

## 覆盖状态

SEED / DISCOVERING / KNOWN / MATURE / QUIET / LOW_CONFIDENCE / DISCONNECTED(degraded) /
EMPTY(no cache) / BREATHING / PAUSE / ACTION_MENU / WHY / HAPTICS_ON(表面开关透传诊断)。

## 验收记录（ERA 33 R2 实测）

- 26 态渲染，像素级检查 **0 边缘裁剪**（内容 bbox 全部严格位于 212×520 内）；
- 文本 clamp 探针：zh/en 全部 `clamp=false`（无截断）；
- identity 连续性：同一 identity 的 accent（accentHue=0.6 → rgb(103,154,230)）在
  SEED→DISCOVERING→KNOWN→MATURE→QUIET 全部呈现，质心 x≈105.4（居中）；
  LOW_CONFIDENCE / DISCONNECTED 降光后仍保持同一色相（质心 x≈105.7/105.6）——降级不换身份；
  EMPTY 使用中性色 rgb(190,180,160)（无缓存不编造新身份）；
- 触觉开关透传：HAPTICS_ON 态（surface.hapticsEnabled=true）正常渲染（振动门在代码层，
  见 action/index.ux vibrateShort/Long 硬门 + Node 测试）。
