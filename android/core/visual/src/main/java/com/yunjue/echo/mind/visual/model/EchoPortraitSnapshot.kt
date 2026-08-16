package com.yunjue.echo.mind.visual.model

/**
 * EchoPortraitSnapshot — Journey 视觉记忆单元（§11）。
 *
 * **保存参数，不保存 AI 图片**：Journey 的每个 portrait 由 genome 快照确定性重建，
 * 不存服务器生图、不存 PNG。同一天永远渲染出同一帧（Determinism）。
 */
data class EchoPortraitSnapshot(
    /** 日期（YYYY-MM-DD）。 */
    val date: String,
    /** 当日视觉 genome 快照（确定性重建的全部所需参数）。 */
    val genome: EchoVisualGenome,
    /** identity 修订号（identity 演进追踪）。 */
    val identityRevision: Int,
    /** 构图修订号（渲染算法版本；schema 演进兼容锚点）。 */
    val compositionRevision: Int,
    /** 证据摘要引用（Journey → Evidence 溯源；不含原始敏感数据）。 */
    val evidenceSummaryRef: String?,
    /** snapshot schema 版本。 */
    val version: Int = CURRENT_VERSION,
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

/** snapshot 重建的确定性时间点（同一天永远同一帧；与 Journey 既有 CANONICAL 语义一致）。 */
const val PORTRAIT_CANONICAL_TIME_SECONDS = 12f
