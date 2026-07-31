package com.yunjue.echo.mind

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 被动采集偏好（DataStore Preferences）：
 *
 * - passiveSensingEnabled：被动采集总开关
 * - micEnabled：麦克风采集开关（T03 实现，此处仅存储字段）
 * - samplingConfig：采样配置 JSON 字符串
 *
 * 与现有 AppPreferences 的 SharedPreferences 隔离存储，互不影响。
 * DataStore 实例由顶层 by preferencesDataStore 委托懒加载，全局唯一。
 */
private val Context.passiveSensingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "echo_mind_passive_sensing"
)

class PassiveSensingPrefs(context: Context) {
    private val store = context.applicationContext.passiveSensingDataStore

    val passiveSensingEnabled: Flow<Boolean> = store.data.map { it[KEY_PASSIVE_SENSING] ?: false }
    val micEnabled: Flow<Boolean> = store.data.map { it[KEY_MIC] ?: false }
    val samplingConfig: Flow<String> = store.data.map { it[KEY_SAMPLING_CONFIG] ?: DEFAULT_SAMPLING_CONFIG }

    suspend fun setPassiveSensingEnabled(enabled: Boolean) {
        store.edit { it[KEY_PASSIVE_SENSING] = enabled }
    }

    suspend fun setMicEnabled(enabled: Boolean) {
        store.edit { it[KEY_MIC] = enabled }
    }

    suspend fun setSamplingConfig(config: String) {
        store.edit { it[KEY_SAMPLING_CONFIG] = config }
    }

    companion object {
        private val KEY_PASSIVE_SENSING = booleanPreferencesKey("passive_sensing_enabled")
        private val KEY_MIC = booleanPreferencesKey("mic_enabled")
        private val KEY_SAMPLING_CONFIG = stringPreferencesKey("sampling_config")

        /** 默认采样配置：加速度/陀螺仪/屏幕/通知/App 活跃全开。 */
        const val DEFAULT_SAMPLING_CONFIG =
            """{"accel":true,"gyro":true,"screen":true,"notification":true,"appActivity":true}"""
    }
}
