package com.yunjue.echo.mind.ui.me

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Organism Quality §33 — Me mini ECHO 渲染统一门（源码结构锚点）。
 *
 * Me 身份头像必须经 EchoRendererFacade（thumbnailRequest：LEGACY/MINIMAL 低成本预设），
 * 不得绕过 facade 直连 EchoOrganism / OrganismFrameComputer（不启动多余高成本 full renderer）。
 */
class MeMiniRendererTruthTest {

    private val meSource: String by lazy {
        File("src/main/java/com/yunjue/echo/mind/ui/me/MeScreen.kt").readText()
    }

    @Test
    fun meMiniEchoUsesUnifiedFacadeThumbnailSession() {
        assertTrue(
            "Me mini ECHO 必须经统一 facade（thumbnailRequest）",
            meSource.contains("EchoRenderSession.thumbnailRequest"),
        )
        assertTrue(
            "Me mini ECHO 必须用 facade session 绘制",
            meSource.contains("EchoRendererFacade.createSession"),
        )
        assertFalse(
            "Me mini ECHO 不得绕过 facade 直连 EchoOrganism",
            meSource.contains("EchoOrganism("),
        )
        assertFalse(
            "Me mini ECHO 不得直连帧计算机",
            meSource.contains("OrganismFrameComputer"),
        )
    }
}
