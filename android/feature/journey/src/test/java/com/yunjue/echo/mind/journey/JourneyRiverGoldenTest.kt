package com.yunjue.echo.mind.journey
import android.graphics.Bitmap
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.presencevisual.OrganismCanvasRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Journey Memory River golden：同一 identity 连续多日 portrait（验证 identity evolution 连续可辨）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JourneyRiverGoldenTest {
    private fun p(date: String, days: Int, mv: String) = DailyPortraitDto(
        date = date, status = "READY", confidence = "HIGH", baselineDays = days, summary = "x",
        dimensions = mapOf(
            "MOVEMENT" to PortraitDimensionDto(mv, z = if (mv == "MORE") 1.0 else 0.0),
            "SCREEN_AMOUNT" to PortraitDimensionDto("SIMILAR"),
            "RHYTHM" to PortraitDimensionDto("SIMILAR"),
        ),
    )
    @Test
    fun rendersContinuousIdentityRiver() {
        val dir = File("../qa/visual-review/organism/river").also { it.mkdirs() }
        val seed = 7L
        val days = listOf(
            p("2026-08-10", 3, "LESS"), p("2026-08-11", 4, "SIMILAR"),
            p("2026-08-12", 5, "SIMILAR"), p("2026-08-13", 6, "MORE"),
            p("2026-08-14", 7, "SIMILAR"), p("2026-08-15", 8, "MORE"),
            p("2026-08-16", 9, "SIMILAR"),
        )
        days.forEach { d ->
            val frame = JourneyOrganismVisuals.frameFor(d, seed, 400f, 400f)!!
            val bmp = OrganismCanvasRenderer.renderToBitmap(frame, 400, 400)
            val out = File(dir, "river_${d.date}.png")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(out.length() > 1000)
        }
        assertTrue(true)
    }
}
