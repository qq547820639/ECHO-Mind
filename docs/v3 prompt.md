你现在负责 ECHO Mind 的一次完整工程收口迭代。

仓库以当前 checkout 为唯一事实源。

本轮名称：

ECHO Simplification & Runtime Consolidation

这不是一个新视觉版本。

这不是继续扩展 V3。

本轮任务是：

1. 修复当前架构/治理漂移；
2. 合并重复的 Visual Runtime 事实源；
3. 让 SAME ECHO 成为真实运行时事实；
4. 简化 ECHO / Journey / Me / Onboarding；
5. 删除 V3 带来的不必要交互复杂度；
6. 让测试验证用户真正看到的 backend；
7. 保留已经成熟的 Observation / Presence / Intelligence / Memory / Safety / Wrist；
8. 完成软件侧全部测试和 closure；
9. 有真机则完成真实设备验证；
10. 没有真机则明确 BLOCKED_EXTERNAL，不得虚构。

==================================================
A. 工作原则
==================================================

除 frozen contract 真实冲突、外部硬件/凭证缺失以外，不向用户提问。

自行：

定位
修改
迁移
测试
修复
清理
提交

直到 Software Definition of Done。

不要提出新的产品方向。

不要继续发明新的交互范式。

新的全产品设计纪律：

ONLY ECHO MAY BE UNFAMILIAR.

也就是：

ECHO organism 可以独特。

但：

Navigation
Calendar
Chat
Settings
Back
Lists
Permissions
Details

必须优先使用 Android 用户熟悉的模式。

==================================================
B. 首先锁定源码
==================================================

执行：

git status
git rev-parse HEAD
git log -1 --oneline

记录：

IMPLEMENTATION_HEAD

完整阅读：

README.md
PORTRAIT_CONTRACT.md
docs/STATUS.md
docs/product/ECHO_PRODUCT_CONSTITUTION.md
docs/intelligence/PERSONAL_INTELLIGENCE_CONTRACT.md
docs/intelligence/AFFECTIVE_CONTRACT.md
docs/presence/**
docs/wearable/**
docs/architecture/**
qa/visual-review/**

扫描全部：

android
backend
wearable
scripts
.github

==================================================
C. 不允许重写的区域
==================================================

默认保持：

Observation Ground Truth
Presence business state
Memory semantics
Intelligence contract
Safety contract
Crisis phone numbers
Data ownership
Provider security
Wrist protocol/security
Backend API contracts

除非测试证明存在实际 bug。

本轮重构集中：

Android presentation
Visual semantic pipeline
Android renderer integration
runtime timing
UI tests
architecture tooling
CI/tooling

==================================================
D. 第一项：架构事实修复
==================================================

当前 settings.gradle.kts 是实际 module 真相。

不要根据旧 STATUS 强行删除 module。

首先同步治理文档。

当前已知实际包含：

:feature:presencevisual
:core:visual

它们存在，因此 STATUS / contract 必须反映当前事实。

但是只有在证明它们职责重复且删除收益明显时才能合并。

默认保留：

core:visual
= pure deterministic visual math

feature:presencevisual
= Android rendering adapters/backend

这是合理边界。

==================================================
E. Dependency Graph
==================================================

重写 scripts/generate_dependency_graph.py。

当前脚本的问题：

DOMAIN_OF 未包含：

visual
presencevisual
wearable

导致新模块可能被错误归类为 root。

最终生成两张图：

1. Gradle Module Dependency Graph

从：

settings.gradle.kts
build.gradle.kts

自动解析。

2. Package Domain Dependency Graph

显式识别：

actions
memory
observation
presence
presencevisual
intelligence
journey
wearable
security
model
ports
visual
app/ui/data/runtime/di

两图都必须有 cycle detection。

Gradle module graph 必须无环。

Package/domain graph 必须满足 boundary rules。

==================================================
F. 消除 observation → root → observation
==================================================

当前真实 cycle 必须删除。

搜索：

feature:observation / sensing

是否 import：

EchoMindApplication
PassiveSensingPrefs
enqueueSync
AppContainer
app root implementation

原则：

Observation implementation 不得依赖 app composition root。

正确方向：

app
→ observation

而不是：

observation
→ app
→ observation

允许：

将 Android Service/host glue 保留在 app；
Observation 只暴露 collector/scheduler/port；
通过 constructor/callback/port 注入 app-specific action。

不要为了修 cycle 新增无意义 module。

==================================================
G. CI SDK
==================================================

当前 compileSdk=37。

同步：

android-ci.yml
security-ci.yml
release-closure.yml

不要继续硬装：

android-36 / build-tools 36.0.0

安装 android-37。

build tools 优先让 AGP 选择兼容版本；
如 workflow 必须固定，则固定与当前 toolchain 匹配版本。

==================================================
H. Visual Semantic Pipeline
==================================================

这是本轮最重要的重构。

目前禁止继续存在两套：

EchoVisualMapper
和
GenomeDeriver(EchoPresenceState)

都声称自己是视觉语义唯一事实源。

最终链严格为：

EchoPresenceState
    ↓
EchoVisualMapper
    ↓
EchoVisualParameters
    ↓
VisualGenomeCompiler
    ↓
EchoVisualGenome
    ↓
EchoSceneCompiler
    ↓
Renderer

==================================================
I. Presence 保持唯一业务视觉映射
==================================================

feature:presence：

继续负责：

rhythm
behavior
maturity
identity/season/daily/moment
confidence
hour
user motion preference

→ EchoVisualParameters

所有：

activityLevel
behaviorState
confidence
maturity
coverage

向视觉语义的解释只在 Presence mapping 层发生。

==================================================
J. core:visual 不得重新解释 Presence
==================================================

移除 production：

GenomeDeriver.derive(
    EchoPresenceState,
    ...
)

core:visual 不得直接根据：

rhythmState
behaviorState
confidence
EchoMaturity name

重新推：

brightness
turbulence
density
core openness

改成：

VisualGenomeCompiler.compile(
    params: EchoVisualParameters,
    identity: EchoIdentityGenome,
    season/daily identity metadata needed for stable topology,
)

其中：

EchoVisualParameters

已经是 canonical semantic values。

不要重复：

maturityOpenness
dayBrightnessCurve

==================================================
K. 删除重复公式
==================================================

全仓搜索：

maturityOpenness
dayBrightness
dayBrightnessCurve

最后生产代码只允许各业务事实有一个定义。

Journey 不得再复制。

core:visual 不得再复制。

==================================================
L. Surface / Motion / Power 分离
==================================================

当前 SurfaceMode 包含：

REDUCED_MOTION
LOW_POWER

这是概念混淆。

最终三个正交概念：

Surface
MotionPolicy
RenderQuality

Surface：

APP_PRIVATE
APP_EVIDENCE
JOURNEY_PRIVATE
WALLPAPER_VISUAL_ONLY
LOCK_PUBLIC_SAFE
DREAM_AMBIENT
WRIST_PUBLIC_SAFE

MotionPolicy：

NORMAL
REDUCED
QUIET

RenderQuality：

NORMAL
CONSERVE
MINIMAL

不要再把 Reduced Motion 当 Surface。

不要再把 Low Power 当 Surface。

==================================================
M. Surface Policy
==================================================

Surface 只决定：

privacy
allowText
allowEvidence
max public content
surface visual constraints

RenderQuality 决定：

particle count
filament count
glint
halo quality

MotionPolicy 决定：

velocity
amplitude
parallax

三者不能混在一个 motionComplexity 里。

==================================================
N. 统一 Visual Clock
==================================================

新增纯接口/utility：

EchoVisualClock

实时：

SystemClock.elapsedRealtimeNanos()

不使用：

component-mounted elapsed time

作为 ECHO identity animation phase。

Compose / Wallpaper / Dream：

共享 boot-global monotonic time。

因此 visibility / recomposition / navigation 后：

同一个 ECHO 不重新从 phase 0 开始。

Journey：

使用现有 deterministic canonical historical time。

==================================================
O. EchoOrganism
==================================================

删除：

val start = withFrameNanos { ... }

然后：

clock =
now - start

这种组件本地起点。

Compose ticker 只请求帧。

真实 visual time：

EchoVisualClock.nowNanos()

==================================================
P. Renderer Facade
==================================================

在 feature:presencevisual 建立：

EchoRendererFacade

和必要的：

EchoRenderSession

所有 Android visual consumer 必须通过它。

至少：

App
Visual Lab
Wallpaper
Dream
Device Visual Test

Journey selected/detail 也走 facade。

Journey mini thumbnail 可以选择低成本 Canvas quality，
但必须显式命名：

JOURNEY_THUMBNAIL

不能是实现偶然。

==================================================
Q. Backend Resolution
==================================================

Facade 返回：

requestedTier
resolvedTier
backendName
quality
HDR/WCG status

Visual Lab/QA JSON 记录：

requestedBackend
actualBackend

绝对禁止：

requested = ADVANCED
实际 Canvas
报告 backend=ADVANCED

==================================================
R. Visual Lab
==================================================

当前 Visual Lab export 永远 Canvas。

修复。

如果用户选择：

CANVAS
→ 真实 Canvas

AGSL
→ 真实 RuntimeShader rendering

ADVANCED
→ 真实 Advanced rendering

如果设备不支持：

明确：

requested=ADVANCED
resolved=CANVAS
reason=...

不能假装。

==================================================
S. Visual Gates
==================================================

将当前：

VisualReferenceGateTest

重命名/重构为：

CanvasFallbackVisualGateTest

它只保证 Canvas fallback。

增加 Android instrumented：

AdvancedBackendVisualGate

在真实/模拟 Android graphics 环境截图：

实际 AGSL/Advanced。

自动 metrics 可以复用。

不能再用 LEGACY gate 代表 production advanced visual。

==================================================
T. HDR
==================================================

当前：

isWideColorGamut

不能当 HDR 判断。

暂时：

HDR production default OFF

直到实现真实 capability：

Display.HdrCapabilities
supported HDR types
window/headroom support
power/thermal eligibility

并通过真实 HDR 设备视觉测试。

WCG 可以独立启用。

==================================================
U. Render Environment
==================================================

当前 Compose remember(context) 会冻结：

quality
tier
HDR

修复。

建立：

EchoRenderEnvironmentState

可以用：

StateFlow

监听：

Power Save
Thermal status

Display capability 极低频/生命周期刷新。

Compose collectAsStateWithLifecycle。

Wallpaper/Dream 使用同一 environment resolver。

不要每帧 query system service。

==================================================
V. AGSL 性能
==================================================

不要假设 AGSL 比 Canvas 快。

当前实现每帧：

CPU geometry
→ full-size ARGB mask raster
→ GPU shader

必须 benchmark。

保留 AGSL 只有当：

视觉明显更好
且
功耗/帧预算可接受。

如 Canvas 已达到设计目标且更省：

允许 Canvas 成为 default。

技术不是产品目标。

==================================================
W. AGSL 具体修复
==================================================

当前 fixed 3px glow sampling：

改为 uniform。

例如：

iPixelSize
iGlowRadius

按：

viewport
visual radius
density

计算。

不要固定 3px。

若需要更高级 depth material：

可以使用 mask B channel 编码 normalized depth。

但必须有 measurable visual gain。

不要为了技术增加无价值复杂度。

==================================================
X. capability cache
==================================================

AgslEchoBackend.isAvailable()
isAdvancedAvailable()

不要频繁重新编译 RuntimeShader。

使用 process-level lazy cached capability。

==================================================
Y. Canvas 绘制顺序
==================================================

检查：

thin crisp core
vs
wide glow

最终：

glow first
crisp core last

保证 filament 不被自己的 glow 洗掉。

Compose Canvas 和 android.graphics Canvas 都一致。

==================================================
Z. Theme
==================================================

这是本轮 P0 UX 修复。

建立：

EchoMindTheme

MainActivity 必须使用：

EchoMindTheme {
    Surface { ... }
}

不要裸 MaterialTheme。

App 默认 dark-first。

普通 UI：

近黑/深灰背景
接近白色主文字
中性灰 secondary
单一 blue-violet accent

Error 色只用于真正 error/safety。

原则：

ONLY ECHO GLOWS.

禁止：

glowing buttons
glowing chips
glowing nav
glowing cards
multiple neon accents

==================================================
AA. System Bars
==================================================

处理 edge-to-edge。

深色状态栏/导航栏。

系统 icon contrast 正确。

不要继续：

Theme.Material.Light
#F7F5F1 status bar

与深色 ECHO 冲突。

==================================================
AB. ECHO Home
==================================================

这是 UX simplification。

Normal READY 首页只允许：

ECHO organism

1 条 Observation

为什么？

Ask ECHO

bottom navigation

最多一个非常短 transient status，
但不要 AI/provider promo。

首屏禁止：

charts
KPI
Memory
Journey duplicate button
long weekly message
feedback controls
action list
conversation history
provider advertisement

==================================================
AC. Home AI Promo
==================================================

删除：

“连接一个 AI，让 ECHO 更深入地理解你”

作为 Home AmbientPrompt。

Provider setup 进入：

Me → AI

Ask 中可以按需要解释 provider。

No Provider 不能让核心 Home 看起来残缺。

==================================================
AD. WHY
==================================================

删除 custom:

CLOSED
42% sheet
82% sheet
更多依据与反馈

作为主路径。

首页：

tap “为什么？”

直接 inline 展开：

Today observed
Personal usual
Delta
Coverage
Correction entry

一次点击完成主要解释。

高级：

“更多技术详情”

才进入 detail screen/sheet。

目标：

解释 <=1 tap
纠正 <=2 taps

==================================================
AE. Correction
==================================================

不要 hidden behind “更多依据与反馈”。

在 inline evidence 中直接出现：

“这不符合实际？”

保持现有 business callback。

==================================================
AF. Ask ECHO
==================================================

改成标准独立 destination/page。

不要在 underlying Home 上再叠一颗 full live ECHO。

Ask screen：

TopAppBar/back

顶部一个 48–72dp low-cost mini ECHO

conversation

input

evidence link

标准 Android Back。

只允许一个 active high-cost render session。

==================================================
AG. Actions
==================================================

当前第三个：

“想做点什么？”

不再和 Why / Ask 同等级。

改为：

contextual secondary action
或
overflow/secondary button

如果当前没有相关 action：

不显示。

==================================================
AH. EchoSceneScreen 拆分
==================================================

当前文件过大。

拆：

EchoSceneScreen.kt
EchoHomeContent.kt
EchoInlineEvidence.kt
EchoAskScreen.kt
EchoActionEntry.kt
EchoTransientStatus.kt

业务 state/callback 不重复。

目标单 presentation 文件尽量 <300–400 LOC。

不要为追 LOC 数拆成无意义碎片。

==================================================
AI. today date
==================================================

删除：

remember { LocalDate.now() }

这种活动跨午夜不会更新的值。

从 state/clock 获取，
或以 day key 驱动更新。

==================================================
AJ. Correction chips
==================================================

删除：

CORRECTION_REASONS.chunked(4)
→ Row

这种固定四列。

用：

FlowRow
或
vertical selectable list

保证 360dp / fontScale1.5 不溢出。

==================================================
AK. Journey Root
==================================================

删除外层：

Page.verticalScroll

包裹内部 vertical LazyColumn 的模式。

每个 Journey scale 拥有一个主 scrolling container。

不能 nested same-direction scroll。

==================================================
AL. Journey Day
==================================================

改成用户熟悉的 chronological timeline/list。

每项高度：

约 64–88dp

包含：

date
small ECHO portrait
one short factual summary

当前 day highlight。

点击进入 day detail。

不要用 168dp + 148dp portrait 导致只看两天。

==================================================
AM. Journey Week
==================================================

删除 WeekArc 作为导航。

改：

7-day horizontal strip / compact row。

星期/日期清楚。

ECHO mini portrait 是视觉信息。

选择后下面出现 narrative。

==================================================
AN. Journey Month
==================================================

这是 correctness fix。

使用：

YearMonth

计算：

firstDay
lengthOfMonth()

真实展示：

28 / 29 / 30 / 31 days

不要：

(0 until 28)
latest - 27 days

月视图必须真的是一个月。

==================================================
AO. Journey Season/Year
==================================================

简化。

Season：

按月份 group。

Year：

12 months grid/list。

不要让：

constellation
arc
river

成为找到日期的必要认知模型。

可以保留漂亮 portrait。

==================================================
AP. Journey Rendering
==================================================

删除 Journey 自己直接：

drawOrganism

作为 selected main portrait 的独立视觉实现。

走 EchoRendererFacade。

Thumbnail 可显式：

quality=THUMBNAIL
tier=CANVAS

但：

time
palette
identity
topology semantic

必须统一。

==================================================
AQ. Journey visual semantic bridge
==================================================

删除：

JourneyOrganismVisuals

内重复的：

maturityOpenness
hardcoded identityTopology
hardcoded luminance
hardcoded orbit values

Journey 必须基于保存/可重建的 canonical visual semantic state。

如果历史数据缺字段：

从 stable identity + stored daily data 用 canonical compiler 重建。

不能创建 Journey-specific alternative ECHO。

==================================================
AR. Me
==================================================

删除：

MeIntelligenceMap

作为主功能导航。

可以删除或降级为：

non-interactive explanatory illustration

但默认 Me 必须是熟悉的 grouped control center。

==================================================
AS. Me Structure
==================================================

目标：

我的 ECHO
  simple identity summary

了解我的方式
  数据与感知
  记忆
  AI

ECHO 出现在哪里
  动态壁纸
  Dream
  手环

我的数据
  导出
  删除

其他
  通知
  订阅
  专业支持
  关于

使用标准：

ListItem
Divider
chevron
Switch only when state truly boolean

不要概念地图。

==================================================
AT. Crisis
==================================================

保留 frozen safety invariant。

检查当前：

global warning FAB
→ Me
→ CrisisCard

是否造成 duplicate interaction。

优先实现：

global safety action
→ directly SafetyScreen

保持 one tap。

Me 内可以有：

紧急支持

入口。

如果 frozen tests 明确要求 CrisisCard 全展开：

contract 优先。

不要改号码：

12356
110
120

除非 contract 已改变。

==================================================
AU. Presence Settings Copy
==================================================

修改绝对陈述：

“不可见时停止渲染，不额外耗电”

为准确：

“不可见时停止持续绘制，以降低额外耗电。”

修改：

“无障碍支持：视觉保持静止”

因为 Reduced Motion 实际仍保留低频呼吸。

改成真实描述：

“减少动态效果：保留轻微呼吸，降低旋转与粒子运动。”

==================================================
AV. Onboarding capability state
==================================================

删除：

statusText.contains("可用")

所有状态从 typed domain state 得出。

不可通过本地化字符串推导业务状态。

增加 regression：

“此设备不可用”

绝不能被判 READY。

==================================================
AW. Onboarding
==================================================

保持：

WELCOME
PRIVACY
CORE_SENSING
AWAKENING

不加 DONE。

保留法律上必须明确确认的 consent。

但文案去重和压缩。

详细隐私信息可以展开。

==================================================
AX. Awakening
==================================================

保留 same identity / 2200ms。

当前 while(true) 改为有界：

while(elapsed < AWAKENING_DURATION_MS)

结束后退出。

不要无限 loop 等待 composition dispose。

==================================================
AY. Wallpaper Clock
==================================================

使用：

SystemClock.elapsedRealtimeNanos()

不要 visibility 时重置 phase 0。

==================================================
AZ. Wallpaper touch time
==================================================

使用：

SystemClock.elapsedRealtime()

不要 currentTimeMillis。

==================================================
BA. Wallpaper offset
==================================================

当前错误：

(xOffset - .5).coerceIn(-.05,.05)

改：

((xOffset - .5f) * .10f)
    .coerceIn(-.05f,.05f)

最好做轻量 smoothing。

==================================================
BB. Wallpaper viewport
==================================================

Touch normalization 不能使用：

resources.displayMetrics

作为 wallpaper canvas 尺寸真相。

保存：

onSurfaceChanged width/height

使用真实 surface dimensions。

==================================================
BC. Wallpaper quality
==================================================

不要每 frame query：

PowerManager
Thermal
Shader availability

RenderEnvironmentState 低频观察。

==================================================
BD. Dream
==================================================

Dream 也使用 EchoRendererFacade + shared visual clock。

Reduced Motion 必须进入 frame options。

不能只是降低 FPS，
视觉运动本身也必须降低。

==================================================
BE. Dream allocation
==================================================

Paint / Typeface 等静态绘制对象：

缓存。

不要每 frame new。

postDelayed Runnable：

复用，
不要每帧 new lambda。

==================================================
BF. Dream time snapshot
==================================================

每帧只取一次：

LocalDateTime.now()

不要分别调用多个 LocalTime.now/LocalDate.now。

==================================================
BG. Visual Environment
==================================================

Quality changes 必须可观察。

App 不允许：

remember(context) {
    currentQuality(...)
}

永久冻结。

==================================================
BH. MainActivity Theme
==================================================

所有：

Crash screen
Init failed screen
Main app

使用 EchoMindTheme。

==================================================
BI. Init Error Privacy
==================================================

当前 comment 与行为矛盾。

Release 不显示：

e.message
absolute path
raw stack

Debug 可以。

Release 只显示：

sanitized error code
exception class
device/API
retry
export sanitized report

==================================================
BJ. Crash Report
==================================================

BuildConfig.DEBUG：

允许 raw full stack。

Release：

默认只显示 sanitized summary。

“复制全文”仅 Debug，
或显式导出已清洗 diagnostics。

==================================================
BK. Tests must not lock bad UX
==================================================

删除/修改所有测试中以下 implementation assertions：

Ask 必须有第二个 live visual

WHY feedback 必须藏在“更多依据与反馈”

Me 必须有 Intelligence Map

Journey 必须有 WeekArc / MonthConstellation

这些不是产品 contract。

==================================================
BL. New UX Outcome Tests
==================================================

增加：

ECHO:

Why reachable in 1 tap

Correction <=2 taps

Ask reachable in 1 tap

Only one expensive ECHO renderer session per screen

Home has no AI provider promo

Journey:

Day has one vertical scroll owner

Month contains actual month day count

Month does not spill unrelated days unless normal calendar filler clearly outside month

Me:

Data & Sensing <=1 tap from Me

Memory <=1 tap

AI <=1 tap

Wallpaper <=1 tap

Wrist <=1 tap

Onboarding:

UNAVAILABLE != READY

No DONE

No enhanced optional permission wall

==================================================
BM. Visual Backend Tests
==================================================

JVM：

Canvas fallback deterministic tests。

Android instrumented：

AGSL/Advanced screenshot / metrics。

Visual Lab actual selected backend test。

Report:

requested tier
resolved tier
actual renderer

==================================================
BN. Backend
==================================================

不要大规模改 Backend。

现有 backend/safety/contract tests 是资产。

只做：

必要 lint
测试速度改善

当前 full-chain E2E 如果过慢：

拆成：

small HTTP E2E smoke

+
bulk fixture setup directly via service/DB boundary

不要通过成千 HTTP 请求建立 fixture。

但不能降低覆盖语义。

==================================================
BO. Architecture Tests
==================================================

扩大 ArchitectureBoundaryTest 覆盖：

core:visual
feature:presencevisual
feature:wearable

确保：

core:visual 不 import app/data/observation implementations

presencevisual 不 import DB/provider

observation 不 import app root

==================================================
BP. Visual Runtime Contract Test
==================================================

新增静态/编译测试确保：

生产代码只有一个：

Presence → Visual semantic derivation

禁止：

第二个 GenomeDeriver(EchoPresenceState)

==================================================
BQ. 删除旧代码
==================================================

完成迁移后删除：

duplicate semantic mapper

old surface bridging where no longer needed

Journey duplicate visual mapping

Intelligence Map primary navigation

custom WHY sheet hierarchy

second live Ask organism

obsolete V3 regression assertions

不要保留：

Old/
New/
V4/
LegacyUX/

双轨。

==================================================
BR. Vulkan
==================================================

本轮不继续实施/扩展 Vulkan。

如果当前只是 enum/experimental flag：
可以保留 inert capability。

禁止新增 GPU compute complexity。

只有以下全部完成后未来再评估：

Advanced renderer validated

real device power measured

UX simplified

single renderer facade

real need demonstrated

==================================================
BS. 实施顺序
==================================================

严格：

1. Baseline reports

2. Governance / module docs

3. Dependency graphs + cycle removal

4. CI SDK fix

5. Visual semantic consolidation

6. Surface/Motion/Quality separation

7. Visual clock

8. Renderer facade

9. Visual Lab truthfulness

10. Theme

11. ECHO simplification

12. Journey simplification

13. Me simplification

14. Onboarding typed status

15. Wallpaper/Dream fixes

16. MainActivity privacy fixes

17. Test rewrite

18. Android software full gate

19. Backend/Wearable regression

20. Visual device validation if hardware exists

21. Delete review

22. Documentation closure

==================================================
BT. Commit Discipline
==================================================

每个 vertical slice 独立 commit。

建议：

docs: align architecture governance with current modules

fix(architecture): separate module and package dependency graphs

refactor(observation): remove app-root dependency cycle

ci(android): align SDK installation with compileSdk 37

refactor(visual): establish one canonical presence-to-visual mapping

refactor(visual): separate surface motion and render quality policy

refactor(visual): unify live visual clock across surfaces

refactor(presencevisual): route Android surfaces through one renderer facade

fix(visual-lab): validate and export the actual selected backend

feat(theme): introduce restrained dark EchoMind theme

refactor(echo): simplify home and inline evidence

refactor(echo): move Ask ECHO to familiar single-renderer conversation screen

refactor(journey): replace visual navigation metaphors with familiar time navigation

fix(journey): render true calendar months

refactor(me): replace intelligence map navigation with grouped controls

fix(onboarding): use typed sensing capability status

fix(presence): correct wallpaper and dream timing lifecycle

fix(app): sanitize release crash and startup diagnostics

test(ui): replace V3 implementation locks with user-outcome regressions

refactor: delete superseded V3 presentation paths

docs: close simplification and runtime consolidation

==================================================
BU. Android Validation
==================================================

如果当前环境支持：

./gradlew testDebugUnitTest
./gradlew lint
./gradlew detekt
./gradlew assembleDebug

以及仓库现有：

security
architecture
visual
instrumentation
release smoke

全部执行。

如果无法下载 SDK/Gradle：

明确：

BLOCKED_ENVIRONMENT

不能写 PASS。

==================================================
BV. Backend
==================================================

运行全部 pytest。

若某单文件因 fixture 构造极慢：

先保存基线，
再优化 test setup，
最后完整跑。

不能仅因为 timeout 就删除测试。

==================================================
BW. Wearable
==================================================

运行：

node tests/run.js
node tests/preflight.js

所有协议/视觉 regression 必须继续通过。

==================================================
BX. Real Device
==================================================

有 Android device：

真实测试：

fresh install

onboarding

ECHO

inline Why

correction

Ask

Journey Day/Week/Month

Me

Wallpaper

Dream

power saver

lock/unlock

process death

24h battery

如果没有：

BLOCKED_EXTERNAL_ANDROID_DEVICE

==================================================
BY. Final UX Metrics
==================================================

必须达到：

Home:

Why = 1 tap

Ask = 1 tap

Correction <=2 taps

No provider promo on normal Home

No nested sheets for normal explanation

Journey:

find yesterday <=1 tap/scroll

find a date in month is standard calendar interaction

no same-direction nested scroll

Me:

Data <=1 tap

Memory <=1 tap

AI <=1 tap

Wallpaper <=1 tap

Wrist <=1 tap

Onboarding:

no fake ready status

no optional-permission wall

==================================================
BZ. Visual Discipline
==================================================

整个 App：

只有 ECHO 本体可以 glow。

普通：

buttons
tabs
cards
lists

全部克制。

不要让高级 graphics 侵入基本 navigation。

高级技术只有在：

用户看起来更漂亮
或
更省资源

时才保留。

技术复杂度本身不是成功指标。

==================================================
CA. Final Definition of Done
==================================================

全部满足：

[ ] architecture docs reflect real modules

[ ] module graph acyclic

[ ] package dependency cycle removed

[ ] source-integrity dependency graph gate green

[ ] CI SDK matches compileSdk

[ ] one canonical Presence→Visual semantic path

[ ] no duplicate maturity/dayBrightness mapping

[ ] Surface/Motion/Quality orthogonal

[ ] shared live visual clock

[ ] App/Wallpaper/Dream route through renderer facade

[ ] Journey rendering is explicitly unified/thumbnail-quality

[ ] Visual Lab reports actual backend

[ ] Canvas gate no longer pretends to be Advanced

[ ] Advanced backend device gate exists

[ ] HDR capability truthful

[ ] EchoMindTheme exists

[ ] default light-vs-dark visual split removed

[ ] Home simplified

[ ] Why one-tap explanation

[ ] Correction <=2 taps

[ ] Ask standard single-renderer screen

[ ] AI provider promo removed from Home

[ ] Journey nested scroll removed

[ ] Month is actual calendar month

[ ] Me grouped control center

[ ] Intelligence Map no longer required navigation

[ ] Onboarding typed state

[ ] “不可用” cannot be ready

[ ] Wallpaper offset fixed

[ ] Wallpaper uses actual surface dimensions

[ ] shared monotonic animation clock

[ ] Dream reduced motion actually reduces motion

[ ] per-frame avoidable allocations removed

[ ] release init errors sanitized

[ ] raw crash stack debug-gated

[ ] tests no longer lock bad V3 UX

[ ] Android software tests green or exact BLOCKED_ENVIRONMENT

[ ] backend tests green

[ ] wearable tests green

[ ] real device explicitly PASS or BLOCKED_EXTERNAL

[ ] superseded code deleted

[ ] docs and status synchronized

==================================================
CB. 最终产品原则
==================================================

不要继续把“Personal Ambient Intelligence”
理解成：

“每个界面都应该发明一种新的操作方式”。

正确解释：

ECHO 本体是全产品唯一真正陌生的东西。

它可以前所未见。

但承载它的 App：

应该极其容易使用。

导航熟悉。

日历熟悉。

列表熟悉。

聊天熟悉。

设置熟悉。

证据直接。

纠正容易。

数据可控。

当高级技术和易用性冲突时：

易用性优先。

当高级技术和电池冲突且视觉收益不明显时：

简单实现优先。

当漂亮隐喻和用户找不到功能冲突时：

熟悉交互优先。

最终目标：

不是一个展示技术实力的 App。

而是：

一个第一次打开就会用、
一个月后仍然不烦、
同时拥有一颗别人没有见过的 ECHO 的 Android 产品。

现在开始。

从 Source Reality、Dependency Graph 和 Visual Semantic Consolidation 开始。

除 frozen-contract / external hardware 真实阻塞外，
不要停下来询问。

一次完成本轮全部 software closure。