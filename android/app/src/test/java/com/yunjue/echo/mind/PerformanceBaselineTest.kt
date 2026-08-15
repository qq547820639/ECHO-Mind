package com.yunjue.echo.mind
import com.yunjue.echo.mind.model.echoMaturity

import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.EchoContextCompiler
import com.yunjue.echo.mind.intelligence.EvidenceItem
import com.yunjue.echo.mind.intelligence.ReasoningTaskId
import com.yunjue.echo.mind.journey.JourneyDay
import com.yunjue.echo.mind.journey.buildYearView
import com.yunjue.echo.mind.journey.journeyDayParams
import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.memory.RetentionClass
import com.yunjue.echo.mind.memory.rankMemories
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.presence.AmbientVector
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.buildDailyComposition
import com.yunjue.echo.mind.presence.buildMomentState
import com.yunjue.echo.mind.presence.computeLifeSeason
import com.yunjue.echo.mind.presence.deriveIdentityGenome
import com.yunjue.echo.mind.presence.smoothPresenceState
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * ERA 18 收尾 — JVM 性能基线（PART PERFORMANCE；真机数字由 CI connected-test 矩阵执行）。
 *
 * 语义：**防退化门禁**，不是基准分数。预算取 JVM 典型值的 20-100 倍安全边际，
 * 只在出现数量级退化时失败（CI 硬件波动不误报）。
 */
class PerformanceBaselineTest {

    private fun measureMs(iterations: Int, block: () -> Unit): Double {
        block() // warmup
        var best = Double.MAX_VALUE
        repeat(iterations) {
            val start = System.nanoTime()
            block()
            best = minOf(best, (System.nanoTime() - start) / 1_000_000.0)
        }
        return best
    }

    private fun portrait(date: String, baselineDays: Int): DailyPortraitDto = DailyPortraitDto(
        date = date,
        status = "READY",
        confidence = "HIGH",
        baselineDays = baselineDays,
        headline = listOf("接近"),
        summary = "今天和平时很接近。",
        dimensions = mapOf(
            "MOVEMENT" to PortraitDimensionDto(value = "SIMILAR", metric = "movement_index", z = 0.4),
            "SCREEN_AMOUNT" to PortraitDimensionDto(value = "SIMILAR", metric = "screen_on_minutes", z = 0.4),
            "RHYTHM" to PortraitDimensionDto(value = "SIMILAR", metric = "active_start_minute", z = 0.4),
        ),
    )

    /** §108：Journey 365 天载入（画像 → JourneyDay → Year View 全装配）——preaggregation 预算。 */
    @Test
    fun journey365DayAssemblyStaysUnderBudget() {
        val start = LocalDate.of(2026, 1, 1)
        val portraits = (0 until 365).map { i ->
            portrait(start.plusDays(i.toLong()).toString(), baselineDays = i % 120)
        }
        val days: List<JourneyDay> = portraits.map { dto ->
            JourneyDay(
                date = dto.date,
                baselineDays = dto.baselineDays,
                headline = dto.headline.joinToString(" · "),
                summary = dto.summary,
                dimensionValues = mapOf("RHYTHM" to "SIMILAR"),
                visualParams = journeyDayParams(dto),
            )
        }
        val ms = measureMs(3) {
            buildYearView(days = days, canonicalDays = emptyList(), contextExceptions = emptyMap())
        }
        assertTrue("365 天 Year View 装配耗时 ${"%.1f".format(ms)}ms 超出预算 2000ms", ms < 2000.0)
    }

    /** ERA 39：Journey 365 天**完整 UI 状态装配**（§108 全量实时计算防退化——纯函数装配器全链）。 */
    @Test
    fun journeyUiStateAssembly365StaysUnderBudget() {
        val start = LocalDate.of(2026, 1, 1)
        val portraits = (0 until 365).map { i ->
            portrait(start.plusDays(i.toLong()).toString(), baselineDays = i % 120)
        }
        val timeline = com.yunjue.echo.mind.model.PortraitTimelineUiState(
            days = 365, loading = false, portraits = portraits,
        )
        val ms = measureMs(3) {
            com.yunjue.echo.mind.journey.assembleJourneyUiState(
                scale = com.yunjue.echo.mind.journey.JourneyScale.YEAR,
                timeline = timeline,
                permissionEnabled = true,
                narrative = null,
                runtimeAvailability = null,
                runtimeDiagnostics = null,
                showEvidence = false,
                intelligenceAvailable = false,
                syncStatus = com.yunjue.echo.mind.journey.JourneySyncStatus(consent = true, permissionEnabled = true),
                journeySeed = 42L,
                memory = com.yunjue.echo.mind.journey.JourneyMemoryAssemblyInputs(
                    contextExceptions = mapOf("2026-03-10" to "travel"),
                ),
            )
        }
        assertTrue("365 天 UI 状态装配耗时 ${"%.1f".format(ms)}ms 超出预算 2000ms", ms < 2000.0)
    }

    /** ERA 32 R07（§59）：Journey 1000 天完整 UI 状态装配预算——canonical/聚合/懒渲染组合上限。 */
    @Test
    fun journeyUiStateAssembly1000StaysUnderBudget() {
        val start = LocalDate.of(2023, 4, 1)
        val portraits = (0 until 1000).map { i ->
            portrait(start.plusDays(i.toLong()).toString(), baselineDays = i % 120)
        }
        val timeline = com.yunjue.echo.mind.model.PortraitTimelineUiState(
            days = 1000, loading = false, portraits = portraits,
        )
        val ms = measureMs(2) {
            com.yunjue.echo.mind.journey.assembleJourneyUiState(
                scale = com.yunjue.echo.mind.journey.JourneyScale.YEAR,
                timeline = timeline,
                permissionEnabled = true,
                narrative = null,
                runtimeAvailability = null,
                runtimeDiagnostics = null,
                showEvidence = false,
                intelligenceAvailable = false,
                syncStatus = com.yunjue.echo.mind.journey.JourneySyncStatus(consent = true, permissionEnabled = true),
                journeySeed = 42L,
                memory = com.yunjue.echo.mind.journey.JourneyMemoryAssemblyInputs(
                    contextExceptions = mapOf("2024-03-10" to "travel"),
                ),
            )
        }
        assertTrue("1000 天 UI 状态装配耗时 ${"%.1f".format(ms)}ms 超出预算 5000ms", ms < 5000.0)
    }

    /** §108：Life Season 计算（365 画像窗口）——全量重算预算。 */
    @Test
    fun lifeSeason365WindowStaysUnderBudget() {
        val start = LocalDate.of(2026, 1, 1)
        val portraits = (0 until 365).map { i ->
            portrait(start.plusDays(i.toLong()).toString(), baselineDays = i % 120)
        }
        val ms = measureMs(3) { computeLifeSeason(portraits) }
        assertTrue("Life Season 365 窗口耗时 ${"%.1f".format(ms)}ms 超出预算 1000ms", ms < 1000.0)
    }

    /** ERA 72 §108：两段式记忆装配第一段（周期/河流/年视图/生活阶段解释）——独立预算锚点。 */
    @Test
    fun journeyMemoryAssembly365StaysUnderBudget() {
        val start = LocalDate.of(2026, 1, 1)
        val portraits = (0 until 365).map { i ->
            portrait(start.plusDays(i.toLong()).toString(), baselineDays = i % 120)
        }
        val timeline = com.yunjue.echo.mind.model.PortraitTimelineUiState(
            days = 365, loading = false, portraits = portraits,
        )
        val ms = measureMs(3) {
            com.yunjue.echo.mind.journey.assembleJourneyMemoryState(
                scale = com.yunjue.echo.mind.journey.JourneyScale.YEAR,
                timeline = timeline,
            )
        }
        assertTrue("365 天记忆装配耗时 ${"%.1f".format(ms)}ms 超出预算 2000ms", ms < 2000.0)
    }

    /** §109：Memory Long History——1000 条记忆排序（JVM 重排预算；SQL 侧已 LIMIT + 复合索引）。 */
    @Test
    fun memoryRanking1000StaysUnderBudget() {
        val now = System.currentTimeMillis()
        val memories = (0 until 1000).map { i ->
            EchoMemory(
                id = "mem_$i",
                userId = "u",
                type = MemoryType.entries[i % MemoryType.entries.size],
                content = "内容 $i",
                source = "observation-core",
                confidence = i % 100 / 100f,
                createdAt = now - i * 3600_000L,
                lastConfirmedAt = now - i % 30 * 86_400_000L,
                importance = i % 100,
                retentionClass = RetentionClass.LONG_TERM,
                provenance = "observation:v1",
                deleted = false,
            )
        }
        val ms = measureMs(3) { rankMemories(memories, now) }
        assertTrue("1000 条记忆排序耗时 ${"%.1f".format(ms)}ms 超出预算 1000ms", ms < 1000.0)
    }

    /** ERA 39：§77 Derived Pattern Memory——1000 条观察记忆模式派生（Worker 维护路径预算）。 */
    @Test
    fun patternDerivation1000StaysUnderBudget() {
        val now = System.currentTimeMillis()
        val observations = (0 until 1000).map { i ->
            EchoMemory(
                id = "obs_$i",
                userId = "u",
                type = MemoryType.OBSERVATION,
                content = "你通常在这个时间段${i % 20}使用屏幕。",
                source = "observation-core",
                confidence = 0.7f,
                createdAt = now - i * 3600_000L,
                lastConfirmedAt = 0L,
                importance = 50,
                retentionClass = RetentionClass.LONG_TERM,
                provenance = "observation:v1",
                deleted = false,
            )
        }
        val ms = measureMs(3) {
            com.yunjue.echo.mind.memory.derivePatterns(observations, minOccurrences = 3)
        }
        assertTrue("1000 条模式派生耗时 ${"%.1f".format(ms)}ms 超出预算 1000ms", ms < 1000.0)
    }

    /** §109：空/单条边界不退化（空输入开销近零）。 */
    @Test
    fun emptyInputsHaveNearZeroCost() {
        val ms = measureMs(10) {
            computeLifeSeason(emptyList())
            rankMemories(emptyList(), System.currentTimeMillis())
            buildYearView(emptyList(), emptyList(), emptyMap())
        }
        assertTrue("空输入组合耗时 ${"%.1f".format(ms)}ms 超出预算 200ms", ms < 200.0)
    }

    /** PART PERFORMANCE：Presence assembly（Identity + LifeSeason + Daily + Moment + 平滑）全链纯函数。 */
    @Test
    fun presenceAssemblyStaysUnderBudget() {
        val start = LocalDate.of(2026, 1, 1)
        val portraits = (0 until 60).map { i ->
            portrait(start.plusDays(i.toLong()).toString(), baselineDays = i % 30 + 3)
        }
        val vector = AmbientVector(
            activation = 0.5f, regularity = 0.6f, density = 0.4f, deviation = 0.3f, confidence = 0.7f,
        )
        var previous: EchoPresenceState? = null
        val ms = measureMs(3) {
            repeat(200) {
                val identity = deriveIdentityGenome(seed = 42L, baselineStability = vector.regularity, motionPreference = PresenceMotionLevel.DEFAULT)
                val season = computeLifeSeason(portraits)
                val daily = buildDailyComposition(identity, vector)
                val moment = buildMomentState(vector, hourOfDay = 14f)
                val assembled = EchoPresenceState(
                    updatedAt = java.time.Instant.EPOCH,
                    maturity = echoMaturity(30),
                    identityGenome = identity,
                    lifeSeason = season,
                    dailyComposition = daily,
                    momentState = moment,
                )
                previous = smoothPresenceState(previous, assembled, alpha = 0.35f)
            }
        }
        assertTrue("Presence 装配 200 次耗时 ${"%.1f".format(ms)}ms 超出预算 2000ms", ms < 2000.0)
    }

    /** PART PERFORMANCE：Context retrieval（§69 排序 + 预算 + token 截断，500 条证据编译）。 */
    @Test
    fun contextCompilationStaysUnderBudget() {
        val evidence = (0 until 500).map { i ->
            val category = when (i % 6) {
                0 -> DataSourceCategory.PORTRAIT_HISTORY
                1 -> DataSourceCategory.BASELINE
                2 -> DataSourceCategory.TODAY_AGGREGATE
                3 -> DataSourceCategory.USER_CORRECTIONS
                4 -> DataSourceCategory.CONTEXT_EXCEPTIONS
                else -> DataSourceCategory.RAW_NOTIFICATIONS // 禁止数据：编译必须剔除（最小权限路径开销）
            }
            EvidenceItem(
                category = category,
                label = "证据 $i",
                text = "这是第 $i 条个人节律证据，内容用于上下文编译预算测试。",
                id = "e$i",
                type = if (i % 5 == 0) "memory" else "observation",
                confidence = i % 100 / 100f,
            )
        }
        val ms = measureMs(3) {
            repeat(20) {
                EchoContextCompiler.compile(
                    task = ReasoningTaskId.FIND_LONGITUDINAL_PATTERN,
                    evidence = evidence,
                    question = "最近一个月我的节律有什么变化？",
                )
            }
        }
        assertTrue("上下文编译 20 次耗时 ${"%.1f".format(ms)}ms 超出预算 2000ms", ms < 2000.0)
    }

    /** PART PERFORMANCE：ECHO Scene 首帧（确定性帧计算；渲染器只画帧）。 */
    @Test
    fun sceneFrameComputationStaysUnderBudget() {
        val params = com.yunjue.echo.mind.presence.EchoVisualMapper.map(
            state = EchoPresenceState(
                updatedAt = java.time.Instant.EPOCH,
                maturity = echoMaturity(30),
                identityGenome = deriveIdentityGenome(42L, 0.6f, PresenceMotionLevel.DEFAULT),
            ),
            hourOfDay = 14f,
            surface = com.yunjue.echo.mind.presence.SurfaceMode.APP,
        )
        val ms = measureMs(3) {
            repeat(1000) {
                com.yunjue.echo.mind.presence.computeEchoSceneFrame(
                    params = params,
                    seed = 42L,
                    timeSeconds = com.yunjue.echo.mind.journey.JOURNEY_CANONICAL_TIME_SECONDS,
                    width = 1080f,
                    height = 2340f,
                )
            }
        }
        assertTrue("场景帧计算 1000 次耗时 ${"%.1f".format(ms)}ms 超出预算 2000ms", ms < 2000.0)
    }
}
