package com.yunjue.echo.mind.sensing

/**
 * ERA 13.5 §42 — 麦克风派生特征源契约（自 MicCollector 抽出）。
 *
 * 契约位于 :feature:observation（SensingWindowScheduler 消费）；
 * 实现 MicCollector 留在 :app（依赖 root PassiveSensingPrefs 的平台组件）。
 */
interface MicDerivedFeatureSource {
    /** 非破坏快照当前全部派生特征。 */
    fun snapshot(): List<MicFeatureExtractor.MicDerivedFeature>

    /** 只清快照内已消费项（引用相等；快照后新到项保留）。 */
    fun clearConsumed(consumed: List<MicFeatureExtractor.MicDerivedFeature>)
}
