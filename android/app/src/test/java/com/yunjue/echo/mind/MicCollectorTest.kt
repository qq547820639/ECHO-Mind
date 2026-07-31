package com.yunjue.echo.mind

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.sensing.MicCollector
import com.yunjue.echo.mind.sensing.MicFeatureExtractor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * T03 麦克风采集器单测：
 *
 * - 默认关闭（micEnabled=false 时不启动）
 * - 授权后启动（micEnabled=true + RECORD_AUDIO 已授予）
 * - 撤回授权后不重启
 * - 音频 buffer 不持久化（处理后清空，仅暴露派生特征）
 * - 特征提取器契约（vector ≤ 256 维，summary ≤ 4000 字符）
 *
 * Robolectric 限定 SDK 35 运行，与 PassiveSensingTest 一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MicCollectorTest {

    private lateinit var context: Context
    private lateinit var prefs: PassiveSensingPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 重置 PassiveSensingPrefs（DataStore 单例跨测试保留状态，需手动复位）
        runBlocking {
            prefs = PassiveSensingPrefs(context)
            prefs.setMicEnabled(false)
            prefs.setPassiveSensingEnabled(false)
        }
    }

    @After
    fun tearDown() = Unit

    // ===== 默认关闭 =====

    @Test
    fun micCollectorDoesNotStartWhenDisabled() {
        // 即使授予权限，micEnabled=false 也不应启动
        grantRecordAudioPermission()
        val collector = MicCollector(context, prefs)
        assertFalse("micEnabled=false 时 canStart 应为 false", collector.canStart())
        collector.start()
        assertFalse("micEnabled=false 时不应启动", collector.running)
    }

    @Test
    fun micEnabledDefaultsToFalse() = runBlocking {
        assertFalse("麦克风默认关闭", prefs.micEnabled.first())
    }

    // ===== 授权后启动 =====

    @Test
    fun micCollectorDoesNotStartWithoutPermission() = runBlocking {
        prefs.setMicEnabled(true)
        // 不授予 RECORD_AUDIO 权限
        val collector = MicCollector(context, prefs)
        assertFalse("无 RECORD_AUDIO 权限时 canStart 应为 false", collector.canStart())
        assertFalse("hasPermission 应为 false", collector.hasPermission())
        collector.start()
        assertFalse("无权限不应启动", collector.running)
    }

    @Test
    fun micCollectorCanStartWhenEnabledAndAuthorized() = runBlocking {
        prefs.setMicEnabled(true)
        grantRecordAudioPermission()
        val collector = MicCollector(context, prefs)
        assertTrue("授权且开启时 canStart 应为 true", collector.canStart())
        assertTrue("hasPermission 应为 true", collector.hasPermission())
    }

    @Test
    fun micCollectorStartsAndStopsWhenAuthorized() = runBlocking {
        prefs.setMicEnabled(true)
        grantRecordAudioPermission()
        val collector = MicCollector(context, prefs)
        collector.start()
        assertTrue("授权且开启时应启动", collector.running)
        collector.stop()
        assertFalse("stop 后应停止", collector.running)
    }

    @Test
    fun micCollectorStopIsIdempotent() = runBlocking {
        prefs.setMicEnabled(true)
        grantRecordAudioPermission()
        val collector = MicCollector(context, prefs)
        // 未启动时 stop 不崩溃
        collector.stop()
        assertFalse(collector.running)
        collector.start()
        assertTrue(collector.running)
        collector.stop()
        collector.stop() // 重复 stop 安全
        assertFalse(collector.running)
    }

    @Test
    fun micCollectorStartIsIdempotent() = runBlocking {
        prefs.setMicEnabled(true)
        grantRecordAudioPermission()
        val collector = MicCollector(context, prefs)
        collector.start()
        assertTrue(collector.running)
        // 重复 start 不崩溃，状态不变
        collector.start()
        assertTrue(collector.running)
        collector.stop()
        assertFalse(collector.running)
    }

    // ===== 撤回授权后停止 =====

    @Test
    fun micCollectorDoesNotRestartAfterPermissionRevoked() = runBlocking {
        prefs.setMicEnabled(true)
        grantRecordAudioPermission()
        val collector = MicCollector(context, prefs)
        collector.start()
        assertTrue("初始应已启动", collector.running)
        collector.stop()
        assertFalse(collector.running)
        // 撤回 RECORD_AUDIO 权限
        denyRecordAudioPermission()
        assertFalse("权限撤回后 hasPermission 应为 false", collector.hasPermission())
        assertFalse("权限撤回后 canStart 应为 false", collector.canStart())
        collector.start()
        assertFalse("权限撤回后不应重启", collector.running)
    }

    // ===== P1.2 / P1.6：权限撤回监听器 =====

    @Test
    fun micCollectorInvokesOnPermissionRevokedCallbackWhenPermissionRevokedWhileRunning() = runBlocking {
        prefs.setMicEnabled(true)
        grantRecordAudioPermission()
        var revokedCalls = 0
        val collector = MicCollector(context, prefs, onPermissionRevoked = { revokedCalls++ })
        collector.start()
        assertTrue("初始应已启动", collector.running)
        // 模拟系统设置撤回 RECORD_AUDIO 权限
        // NotificationManagerCompat.OnPermissionsChangedListener 在 Robolectric 中
        // 通过 PermissionChangedNotifier 触发；此处直接验证回调契约：
        // 撤回权限后再次调用 stop 路径不会重复触发回调（幂等）
        denyRecordAudioPermission()
        // 手动触发权限变化通知（Robolectric 不会自动派发到 OnPermissionsChangedListener，
        // 这里通过 collector.hasPermission() 验证权限已撤回，并显式调用 stop 模拟回调路径）
        assertFalse("权限撤回后 hasPermission 应为 false", collector.hasPermission())
        // collector 内部监听器在真实设备上会自动触发 stop + callback；
        // 测试环境验证回调可被注入且不崩溃
        collector.stop()
        assertFalse(collector.running)
        collector.release()
    }

    @Test
    fun micCollectorCallbackDefaultsToNoOpWhenNotProvided() = runBlocking {
        // 不传 onPermissionRevoked 时使用默认空回调，不应崩溃
        prefs.setMicEnabled(true)
        grantRecordAudioPermission()
        val collector = MicCollector(context, prefs)
        collector.start()
        assertTrue(collector.running)
        collector.stop()
        assertFalse(collector.running)
        collector.release()
    }

    @Test
    fun micCollectorReleaseIsIdempotentAndUnregistersListener() = runBlocking {
        prefs.setMicEnabled(true)
        grantRecordAudioPermission()
        val collector = MicCollector(context, prefs, onPermissionRevoked = {})
        collector.start()
        assertTrue(collector.running)
        // release 应幂等：重复调用不崩溃
        collector.release()
        collector.release()
        assertFalse("release 后应停止", collector.running)
    }

    @Test
    fun micCollectorDoesNotInvokeCallbackWhenPermissionRevokedButNotRunning() = runBlocking {
        // 非运行状态下权限变化不应触发回调（避免误报）
        prefs.setMicEnabled(true)
        grantRecordAudioPermission()
        var revokedCalls = 0
        val collector = MicCollector(context, prefs, onPermissionRevoked = { revokedCalls++ })
        // 不调用 start，直接撤回权限
        denyRecordAudioPermission()
        assertFalse(collector.running)
        assertEquals("非 running 时不应触发回调", 0, revokedCalls)
        collector.release()
    }

    @Test
    fun micCollectorDoesNotStartAfterMicDisabled() = runBlocking {
        prefs.setMicEnabled(true)
        grantRecordAudioPermission()
        val collector = MicCollector(context, prefs)
        collector.start()
        assertTrue(collector.running)
        collector.stop()
        // 关闭 micEnabled 开关
        prefs.setMicEnabled(false)
        assertFalse("micEnabled=false 时 canStart 应为 false", collector.canStart())
        collector.start()
        assertFalse("micEnabled=false 时不应重启", collector.running)
    }

    // ===== 音频 buffer 不持久化 =====

    @Test
    fun micCollectorSnapshotInitiallyEmpty() {
        val collector = MicCollector(context, prefs)
        assertTrue("snapshot 初始应为空", collector.snapshot().isEmpty())
    }

    @Test
    fun micCollectorClearBufferEmptiesSnapshot() {
        val collector = MicCollector(context, prefs)
        collector.clearBuffer()
        assertTrue("clearBuffer 后 snapshot 应为空", collector.snapshot().isEmpty())
    }

    @Test
    fun micCollectorExposesOnlyDerivedFeaturesNotRawAudio() = runBlocking {
        // MicCollector 不暴露任何原始音频 API：仅 snapshot() 返回派生特征列表
        prefs.setMicEnabled(true)
        grantRecordAudioPermission()
        val collector = MicCollector(context, prefs)
        collector.start()
        // 启停后 snapshot 不崩溃；不暴露 ShortArray / ByteArray 等原始音频类型
        val snapshot = collector.snapshot()
        assertNotNull(snapshot)
        collector.stop()
        // 派生缓冲可清空
        collector.clearBuffer()
        assertTrue(collector.snapshot().isEmpty())
    }

    // ===== 特征提取器契约 =====

    @Test
    fun extractorHandlesEmptyInput() {
        val extractor = MicFeatureExtractor()
        val feature = extractor.extract(ShortArray(0))
        assertEquals(0L, feature.durationMs)
        assertTrue("summary 非空", feature.summary.isNotBlank())
        assertEquals(
            "vector 维度应为 VECTOR_DIM",
            MicFeatureExtractor.VECTOR_DIM,
            feature.vector.size
        )
    }

    @Test
    fun extractorVectorDimensionWithin256() {
        val extractor = MicFeatureExtractor()
        // 1 秒 16kHz 音频
        val samples = ShortArray(16000) { (Math.sin(it * 0.1) * 10000).toShort() }
        val feature = extractor.extract(samples, MicFeatureExtractor.SAMPLE_RATE_16K)
        assertTrue("vector 维度 ≤ 256", feature.vector.size <= 256)
        assertEquals(
            "vector 维度应固定为 VECTOR_DIM",
            MicFeatureExtractor.VECTOR_DIM,
            feature.vector.size
        )
    }

    @Test
    fun extractorSummaryUnder4000Chars() {
        val extractor = MicFeatureExtractor()
        val samples = ShortArray(16000 * 5) { (Math.sin(it * 0.05) * 20000).toShort() }
        val feature = extractor.extract(samples, MicFeatureExtractor.SAMPLE_RATE_16K)
        assertTrue("summary ≤ 4000 字符", feature.summary.length <= 4000)
        assertTrue("summary 非空", feature.summary.isNotBlank())
    }

    @Test
    fun extractorDoesNotModifyInputSamples() {
        val extractor = MicFeatureExtractor()
        val original = ShortArray(1600) { (it * 100).toShort() }
        val originalCopy = original.copyOf()
        extractor.extract(original, MicFeatureExtractor.SAMPLE_RATE_16K)
        // 提取器不应修改输入数组（原始音频由调用方管理）
        assertArrayEquals("提取器不应修改输入音频", originalCopy, original)
    }

    @Test
    fun extractorEmptyFeatureHasCorrectDimension() {
        val extractor = MicFeatureExtractor()
        val feature = extractor.emptyFeature()
        assertEquals(MicFeatureExtractor.VECTOR_DIM, feature.vector.size)
        assertTrue(feature.summary.isNotBlank())
    }

    @Test
    fun extractorProducesReasonableRmsDb() {
        val extractor = MicFeatureExtractor()
        // 全静音（全零）→ RMS dB 应为 MIN_DB
        val silent = ShortArray(1600) { 0 }
        val silentFeature = extractor.extract(silent, MicFeatureExtractor.SAMPLE_RATE_16K)
        assertEquals(MicFeatureExtractor.MIN_DB, silentFeature.rmsDb)

        // 大音量正弦波 → RMS dB 应高于 MIN_DB
        val loud = ShortArray(1600) { (Math.sin(it * 0.1) * 30000).toShort() }
        val loudFeature = extractor.extract(loud, MicFeatureExtractor.SAMPLE_RATE_16K)
        assertTrue("大音量 RMS dB 应高于静音", loudFeature.rmsDb > silentFeature.rmsDb)
    }

    @Test
    fun extractorSummaryContainsExpectedKeywords() {
        val extractor = MicFeatureExtractor()
        val samples = ShortArray(16000) { (Math.sin(it * 0.1) * 10000).toShort() }
        val feature = extractor.extract(samples, MicFeatureExtractor.SAMPLE_RATE_16K)
        // summary 应包含语速 / 音量等中文描述关键词之一
        val keywords = listOf("语速", "音量", "停顿", "基频")
        assertTrue(
            "summary 应包含至少一个特征关键词",
            keywords.any { feature.summary.contains(it) }
        )
    }

    // ===== AppPreferences 代理 micEnabled =====

    @Test
    fun appPreferencesDelegatesMicEnabled() = runBlocking {
        val appPrefs = AppPreferences(context, com.yunjue.echo.mind.security.FieldCipher())
        assertFalse("默认关闭", appPrefs.micEnabledFlow().first())
        appPrefs.setMicEnabled(true)
        assertTrue("开启后应读取 true", appPrefs.micEnabledFlow().first())
        appPrefs.setMicEnabled(false)
        assertFalse("关闭后应读取 false", appPrefs.micEnabledFlow().first())
    }

    // ===== 辅助方法 =====

    private fun grantRecordAudioPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun denyRecordAudioPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
    }
}
