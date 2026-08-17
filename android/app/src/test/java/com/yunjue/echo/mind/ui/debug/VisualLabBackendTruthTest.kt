package com.yunjue.echo.mind.ui.debug

import com.yunjue.echo.mind.visual.render.EchoRenderTier
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V3 §Q/§R/§BM — Visual Lab 后端真实性回归：
 * Lab 的预览与导出必须经 EchoRendererFacade（导出后端 == 实际选中后端），
 * 导出 JSON 必须记录 requestedBackend / resolvedBackend / backendName / reason，
 * 不得存在绕过 facade 的独立位图渲染路径（Lab 曾恒 Canvas 而谎报选中后端——禁止回归）。
 */
class VisualLabBackendTruthTest {

    private val labSource: String = File(
        "src/main/java/com/yunjue/echo/mind/ui/debug/VisualLabScreen.kt",
    ).readText()

    @Test
    fun exportRoutesThroughFacadeSessionWithRealBackend() {
        assertTrue("导出必须经 facade.createSession（§R）", labSource.contains("EchoRendererFacade.createSession"))
        assertTrue("导出必须用 session.renderToBitmap（真实后端渲染）", labSource.contains(".renderToBitmap("))
        for (key in listOf("requestedBackend", "resolvedBackend", "backendName", "reason")) {
            assertTrue("导出 JSON 缺少后端真相字段：$key", labSource.contains("\"$key\""))
        }
    }

    @Test
    fun previewRoutesThroughFacadeOrganism() {
        assertTrue("预览必须经 facade.Organism（§P 单会话）", labSource.contains("EchoRendererFacade.Organism"))
    }

    @Test
    fun labHasNoBypassRenderPath() {
        assertTrue(
            "Lab 不得直接实例化后端绕过 facade",
            !labSource.contains("AgslEchoBackend(") && !labSource.contains("OrganismCanvasRenderer("),
        )
    }

    /** tier → 预期后端标签必须与 facade 常量一致（FilterChip 可用性/标签不撒谎）。 */
    @Test
    fun tierBackendLabelsMatchFacadeConstants() {
        val expected = mapOf(
            EchoRenderTier.LEGACY to "BACKEND_CANVAS",
            EchoRenderTier.STANDARD to "BACKEND_AGSL",
            EchoRenderTier.ADVANCED to "BACKEND_AGSL_ADVANCED",
            EchoRenderTier.ULTRA to "BACKEND_AGSL_ADVANCED",
        )
        expected.forEach { (tier, constantName) ->
            assertTrue(
                "Lab 缺少 ${tier.name} → $constantName 的标签映射",
                labSource.contains("EchoRenderTier.${tier.name} -> EchoRendererFacade.$constantName") ||
                    labSource.contains("EchoRenderTier.${tier.name}, EchoRenderTier.ULTRA -> EchoRendererFacade.$constantName") ||
                    labSource.contains("EchoRenderTier.ADVANCED, EchoRenderTier.${tier.name} -> EchoRendererFacade.$constantName"),
            )
        }
    }
}
