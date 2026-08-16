package com.yunjue.echo.mind.security

import android.annotation.SuppressLint
import android.content.SharedPreferences
import com.yunjue.echo.mind.AppPreferences

/**
 * ERA 17 §91 — [DatabaseSecretStorage] 的 SharedPreferences 实现（:app 数据层 Adapter）。
 *
 * 存储内容为 Keystore AES-GCM 包装后的密文（v1|base64(iv||ct)），不含任何明文
 * 密钥材料；与 AppPreferences 共用同一 prefs 文件（Wallpaper/Dream 只读 presence
 * 快照键，不触碰本键）。
 *
 * ERA 32 R24：本类写入均为**恢复关键写**（DB 口令唯一来源 / 迁移标记），
 * 刻意用 commit 同步落盘（lint ApplySharedPref 豁免）：异步 flush 前进程死亡
 * 会导致既有加密库永久锁死。
 */
@SuppressLint("ApplySharedPref")
class PreferencesDatabaseSecretStorage(
    private val prefs: SharedPreferences,
) : DatabaseSecretStorage {

    override fun readWrappedSecret(): String? = prefs.getString(KEY_DB_SECRET, null)

    override fun writeWrappedSecret(encoded: String) {
        prefs.edit().putString(KEY_DB_SECRET, encoded).commit()
    }

    override fun isSecretMigrated(): Boolean = prefs.getBoolean(KEY_DB_SECRET_MIGRATED, false)

    override fun markSecretMigrated() {
        prefs.edit().putBoolean(KEY_DB_SECRET_MIGRATED, true).commit()
    }

    companion object {
        /** 包装后的 DB 秘密（Keystore 加密；绝无明文密钥）。 */
        const val KEY_DB_SECRET = "echo_mind_db_secret"

        /** §91 retire 标记：true = 已完成 legacy → HKDF 迁移。 */
        const val KEY_DB_SECRET_MIGRATED = "echo_mind_db_secret_migrated"

        /** AppPreferences 共享 prefs 文件（与其它应用状态同文件，保持单一来源）。 */
        const val PREFS_FILE = AppPreferences.PREFS_FILE
    }
}
