package com.yunjue.echo.mind.security

/**
 * ERA 17 §91 — 数据库打开 + legacy→新 KDF 迁移编排（纯决策，JVM 可测）。
 *
 * 迁移链（§91）：
 *   derive new（HKDF，随机 secret）
 *   ↓ 打开失败且非「错钥」→ 直接抛（fail-closed）
 *   ↓ 已迁移标记 → legacy 已退役，抛（不回退旧派生）
 *   ↓ derive old（legacy 固定 IV 派生）
 *   ↓ open DB
 *   ↓ rotateSecret（生成/存储新受保护秘密）
 *   ↓ rekey
 *   ↓ verify
 *   ↓ markMigrated + retireAncient
 *
 * 失败恢复：迁移任一步失败 → 抛原始错钥异常；旧库仍以旧口令可用，
 * 下次启动重试（SQLCipher rekey 失败不改变既有连接口令）。
 */

/** 打开路径输入（派生与判定）。 */
data class DatabaseOpenInputs(
    /** 新 KDF 口令（HKDF from 受保护 secret；Keystore 不可用即抛 → fail-closed）。 */
    val deriveNew: () -> ByteArray,
    /** legacy 口令（固定 IV 派生；legacy key 不可用 → null）。 */
    val deriveLegacy: () -> ByteArray?,
    /** 是否已完成迁移（true → legacy 派生退役，§91 retire）。 */
    val isMigrated: () -> Boolean,
    /** 异常是否代表「口令错误」（生产 = SQLiteException）。 */
    val isWrongKey: (Throwable) -> Boolean,
)

/** 迁移动作（rotate/mark/retire）。 */
data class DatabaseMigrationActions(
    /** 生成并持久化新的受保护 DB 秘密，返回其 HKDF 口令。 */
    val rotateSecret: () -> ByteArray,
    /** rekey + verify 成功后标记迁移完成。 */
    val markMigrated: () -> Unit,
    /** 退役古代 alias 密钥（幂等）。 */
    val retireAncient: () -> Unit,
)

/** 数据库 IO（打开/rekey/验证）。 */
data class DatabaseIo<T>(
    val build: (ByteArray) -> T,
    val rekey: (T, ByteArray) -> Unit,
    val verify: (T) -> Boolean,
)

/** §91 编排器：新路径优先，旧库一次性迁移，已迁移即退役 legacy。 */
object DatabaseOpenOrchestrator {

    fun <T> open(
        inputs: DatabaseOpenInputs,
        actions: DatabaseMigrationActions,
        io: DatabaseIo<T>,
    ): T {
        val primary = inputs.deriveNew()
        return try {
            io.build(primary)
        } catch (failure: Throwable) {
            if (!inputs.isWrongKey(failure)) throw failure
            // §91 retire：已完成迁移的库绝不回退 legacy 派生（fail-closed）
            if (inputs.isMigrated()) throw failure
            val legacy = inputs.deriveLegacy() ?: throw failure
            try {
                val legacyDb = io.build(legacy)
                val fresh = actions.rotateSecret()
                io.rekey(legacyDb, fresh)
                if (!io.verify(legacyDb)) {
                    // verify 失败：rekey 已完成但数据校验未通过；
                    // 不标记已迁移（防止下次启动用新口令打开时数据不可用）。
                    // 抛出 IllegalStateException 而非原始 failure，让调用方明确知道是迁移验证失败。
                    throw IllegalStateException("db migration verification failed")
                }
                actions.markMigrated()
                actions.retireAncient()
                legacyDb
            } catch (migrationFailure: Throwable) {
                // verify 失败（IllegalStateException）直抛，不掩盖真实原因。
                // rekey / rotateSecret 失败保留旧口令可用；抛原始异常重试。
                if (migrationFailure is IllegalStateException &&
                    migrationFailure.message == "db migration verification failed") throw migrationFailure
                throw failure
            }
        }
    }
}
