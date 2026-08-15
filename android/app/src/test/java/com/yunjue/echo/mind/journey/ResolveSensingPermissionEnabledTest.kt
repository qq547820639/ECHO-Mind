package com.yunjue.echo.mind.journey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 32 R20（真机误报修复）：感知权限可用判定与服务门控同构——
 * 本地模式（未订阅）放行 flag；订阅后 flag 必须为 true；consent 是总前提。
 * 防回归：无网首启（flag 缓存空 = null/false）不得在旅程页误报「被动感知已关闭或权限被撤」。
 */
class ResolveSensingPermissionEnabledTest {

    @Test
    fun consentIsRequiredEvenInLocalMode() {
        assertFalse(resolveSensingPermissionEnabled(consent = false, localMode = true, flagEnabled = null))
    }

    @Test
    fun localModeBypassesFlag() {
        assertTrue(resolveSensingPermissionEnabled(consent = true, localMode = true, flagEnabled = null))
        assertTrue(resolveSensingPermissionEnabled(consent = true, localMode = true, flagEnabled = false))
    }

    @Test
    fun subscribedRequiresFlagTrue() {
        assertFalse(resolveSensingPermissionEnabled(consent = true, localMode = false, flagEnabled = null))
        assertFalse(resolveSensingPermissionEnabled(consent = true, localMode = false, flagEnabled = false))
        assertTrue(resolveSensingPermissionEnabled(consent = true, localMode = false, flagEnabled = true))
    }

    @Test
    fun subscribedWithFlagTrueRequiresConsent() {
        assertFalse(resolveSensingPermissionEnabled(consent = false, localMode = false, flagEnabled = true))
    }
}
