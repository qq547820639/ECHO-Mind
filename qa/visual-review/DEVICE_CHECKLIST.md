# Real Device Wallpaper Verification Checklist（真机验证清单）

> ERA 31 §15/§16。本清单是**部署侧执行手册**——JVM/模拟器无法替代的检查全部列在这里。
> 执行主体：持有真机的人（dogfood / 发布前设备矩阵）。每台设备一轮，结果记入
> `qa/reports/dogfood/<device>.md`，问题只按 Dogfood 六类归档（PRODUCT_DEFECT / REASONING_DEFECT /
> VISUAL_DEFECT / TRUST_DEFECT / PERFORMANCE_DEFECT / ANDROID_RUNTIME_DEFECT）。

## 1. 设备矩阵（最低要求）

| 类别 | 示例 | 必测原因 |
|---|---|---|
| Pixel / AOSP-like | Pixel 8（或 API 34+ 模拟器兜底） | launcher 行为基准 |
| Samsung-like | 三星/国产 ROM 一台（如有） | 后台杀进程策略差异 |
| 低端条件 | ≤4GB RAM / 中低端 SoC（或模拟器 2GB RAM + 低性能模式） | 内存压力与掉帧 |

## 2. 场景清单（每台设备全过）

- [ ] launcher swipe（壁纸随滚动平移/视差是否正常，不闪黑边）
- [ ] home → app → home（壁纸服务与 App 内渲染切换无白屏/重影）
- [ ] screen off / on（`onVisibilityChanged` 后 15 分钟内 Presence 刷新追上）
- [ ] lock / unlock（LOCK_SAFE 表面：更静、亮度正确）
- [ ] wallpaper invisible（切到全屏 App 后再返回）
- [ ] wallpaper visible（home 长时间停留）
- [ ] orientation（旋转不崩溃、比例正确）
- [ ] memory pressure（开大 App 后返回，壁纸进程被杀的恢复路径）
- [ ] process death（`adb shell am kill` wallpaper 服务进程后服务重建）
- [ ] service recreation（壁纸服务重启后 ECHO 帧与死前一致——同 seed 同帧）

## 3. 采集指标（§16 Battery Reality，禁止 JVM 数字冒充）

每轮固定采集（`adb shell dumpsys` + Battery Historian / `dumpsys batterystats`）：

| 指标 | 采集方式 | 目标 |
|---|---|---|
| CPU（wallpaper 进程） | `top -p <pid>` 每 10s 采样 | 不可见时持续 renderer = 0；可见静态期 CPU ≈ 0 |
| GPU / 帧时间 | `dumpsys gfxinfo` / systrace | 无掉帧尖刺；低变化阶段自动降帧生效 |
| memory | `dumpsys meminfo <pid>` | 无泄漏（30 分钟窗口内存不回涨） |
| wakeups | Battery Historian | 无高频唤醒（15min Presence 刷新之外无周期唤醒） |
| battery | 1 小时实测掉电对比（开/关壁纸各一次） | 壁纸增量可接受（记录绝对数字，不预设阈值撒谎） |

**不变量（已有实现，真机确认）**：不可见 → `WallpaperRenderController` renderActive=false（零渲染）；
静态/低变化阶段自动降低 frame rate。

## 4. 结果模板

```markdown
# <device> 真机 Wallpaper 轮（<date>）
- 设备/ROM/RAM：…
- 场景清单：12/12（失败项列出 + 六类缺陷标签）
- CPU 采样：…（可见/不可见/静态期三段数字）
- 帧时间：…  memory：…  wakeups：…  battery：…
- 结论：可保留 / 不可保留（附理由，禁止模糊通过）
```
