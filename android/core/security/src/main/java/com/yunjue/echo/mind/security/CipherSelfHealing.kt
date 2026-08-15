package com.yunjue.echo.mind.security

/**
 * ERA 32 R16 — Keystore 自愈构建（真机缺陷修复：冷启动安全链 fail-closed 导致「打开即闪退」）。
 *
 * 设备侧真实故障模式（用户真机实测）：主 alias 的 AndroidKeyStore 键不可用
 * （OEM 卸载残留旧签名条目 / 密钥被系统作废等）→ 密钥操作抛异常 → fail-closed 裸崩。
 *
 * 自愈规则（纯决策，JVM 可测；安全边界不放松）：
 * 1. 已持久化 fallback suffix → 直接用 fallback alias（后续启动稳定复现同一把密钥）；
 * 2. 主 alias 失败 且 本机**尚无受保护秘密**（全新安装，无数据可孤儿化）→
 *    生成并持久化新 suffix，换新 alias 重建密钥；
 * 3. 主 alias 失败 且 已有受保护秘密 → **fail-closed 原样抛出**（换钥会丢库，绝不自动降级）。
 */
fun resolveCipher(
    persistedSuffix: String?,
    build: (suffix: String) -> AndroidKeystoreFieldCipher,
    hasWrappedSecret: () -> Boolean,
    newSuffix: () -> String,
    persistSuffix: (String) -> Unit,
): AndroidKeystoreFieldCipher {
    if (persistedSuffix != null) return build(persistedSuffix)
    return try {
        build("")
    } catch (primaryFailure: Throwable) {
        if (hasWrappedSecret()) throw primaryFailure
        val suffix = newSuffix()
        persistSuffix(suffix)
        build(suffix)
    }
}
