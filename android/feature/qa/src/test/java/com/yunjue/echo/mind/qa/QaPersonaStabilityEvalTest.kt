package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.intelligence.NarrativeDistiller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 22 §30/§31 — Narrative Distillation + Provider-neutral persona stability：
 *
 * 同一意思、不同 Provider/模型风格的原始输出，蒸馏后必须收敛到同一 ECHO voice——
 * 换模型时：语言能力可以改变，但克制程度/表达节奏/解释风格不能大幅改变。
 */
class QaPersonaStabilityEvalTest {

    private data class ProviderStyle(val name: String, val raw: String)

    /** 同一意思（「最近开始得比平常晚」）的 6 种 Provider 风格。 */
    private val styles = listOf(
        ProviderStyle("provider-a-official", "根据数据分析，你最近开始得比平常晚一些。综上所述，希望对你有所帮助。"),
        ProviderStyle("provider-b-casual", "哇！你最近开始得比平常晚一些！！希望能帮到你哦～"),
        ProviderStyle("provider-c-polite", "数据显示：您最近开始得比平常晚一些。请问还有什么可以帮你。"),
        ProviderStyle("provider-d-ai-hallmark", "作为一个AI助手，我认为你最近开始得比平常晚一些。如果你还有其他问题，请随时告诉我。"),
        ProviderStyle("provider-e-concise", "你最近开始得比平常晚一些。"),
        ProviderStyle("provider-f-verbose", "以下是对你的观察：你最近开始得比平常晚一些。基于以上分析，这是当前的主要变化。"),
    )

    @Test
    fun allProviderStylesDistillToTheSameEchoVoice() {
        val distilled = styles.map { it.name to NarrativeDistiller.distill(it.raw) }
        for ((name, text) in distilled) {
            assertEquals("$name 蒸馏后必须收敛到同一 voice", "你最近开始得比平常晚一些。", text)
        }
    }

    @Test
    fun distillationIsDeterministic() {
        for (style in styles) {
            assertEquals(NarrativeDistiller.distill(style.raw), NarrativeDistiller.distill(style.raw))
        }
    }

    @Test
    fun distillationEnforcesRestraint() {
        // 超过 2 句的长输出被截到 2 句；感叹号堆叠被压平
        val verbose = "今天开始得晚了一些！真的很晚！！我觉得你需要调整作息！！希望对你有所帮助！"
        val out = NarrativeDistiller.distill(verbose)
        assertTrue("感叹号堆叠必须被压平（实际：$out）", !out.contains("！！"))
        assertTrue("招牌句必须被剥离", !out.contains("希望"))
    }

    @Test
    fun distillationKeepsQuestionEnding() {
        assertEquals("这周为什么特别零散？", NarrativeDistiller.distill("这周为什么特别零散？"))
    }

    @Test
    fun distillationCapsLengthAndSentences() {
        val long = "第一句今天开始得晚一些。第二句屏幕时间也变多了。第三句这些变化最近经常出现。第四句还需要继续观察。"
        val out = NarrativeDistiller.distill(long)
        val sentenceCount = out.split(Regex("(?<=[。！？])")).count { it.isNotBlank() }
        assertTrue("最多 2 句（实际 $sentenceCount）", sentenceCount <= 2)
        assertTrue("总长 ≤ 80（实际 ${out.length}）", out.length <= 80)
        assertTrue("保留的是前两句（克制）", out.startsWith("第一句今天开始得晚一些。第二句屏幕时间也变多了。"))
    }
}
