package com.yunjue.echo.mind.security

import com.yunjue.echo.mind.model.SafetyDecision
import com.yunjue.echo.mind.model.Severity

object SafetyEngine {
    const val RULE_PACK_VERSION = "safety-rules-2026.07.2"
    private val exit = listOf(Regex("^退出[。.!！]?$"), Regex("^停止[。.!！]?$"), Regex("不想聊了|结束对话|别再问了"))
    private val red = listOf(
        Regex("我.{0,5}(想死|不想活|结束生命|不想醒来)"),
        Regex("(准备|已经准备|都准备好).{0,12}(工具|遗书|告别|后事)"),
        Regex("(今晚|今天|现在|马上|待会).{0,12}(结束|去死|伤害自己|不活)"),
        Regex("(想|准备|打算).{0,8}(伤害|杀死).{0,6}(别人|他人|某人|家人)"),
        Regex("(无法|不能|不敢)保证.{0,5}(自己)?安全"),
        Regex("(站在高处|正在驾驶|身边有危险物).{0,10}(控制不住|想结束|不安全)"),
        Regex("(有计划|已经计划|具体计划).{0,12}(自杀|自伤|伤害自己|伤害他人)")
    )
    private val yellow = listOf(
        Regex("没有希望|彻底绝望|活着没意义|消失就好了"),
        Regex("我是.{0,6}负担|拖累所有人|想和大家告别|突然告别"),
        Regex("有人命令我|声音让我去做|被监视|他们.{0,2}要害我"),
        Regex("不用睡|连续几天不睡|我无所不能|精力无限"),
        Regex("只有你理解我|你不能离开我|不要告诉任何人"),
        Regex("(停药|加药|减药|改剂量).{0,8}(可以吗|怎么做|帮我|吗)|帮我.{0,5}(停药|加药|减药|改剂量)")
    )
    private val negation = listOf(
        Regex("没有.{0,3}(想死|自伤|伤害自己)"),
        Regex("不想.{0,3}伤害自己"),
        Regex("不会.{0,3}伤害自己")
    )

    fun evaluate(rawText: String): SafetyDecision {
        val text = rawText.replace(Regex("[\\s，,。.!！?？、]"), "")
        val exitHits = exit.mapIndexedNotNull { i, r -> if (r.containsMatchIn(text)) "EXIT-%03d".format(i + 1) else null }
        if (exitHits.isNotEmpty()) return SafetyDecision(Severity.EXIT, exitHits, true, "end_session")
        val redHits = red.mapIndexedNotNull { i, r -> if (r.containsMatchIn(text)) "RED-%03d".format(i + 1) else null }
        val guarded = negation.any { it.containsMatchIn(text) }
        if (redHits.isNotEmpty() && !guarded) return SafetyDecision(Severity.RED, redHits, true, "l2_stabilization")
        val yellowHits = yellow.mapIndexedNotNull { i, r -> if (r.containsMatchIn(text)) "YELLOW-%03d".format(i + 1) else null }.toMutableList()
        if (guarded) yellowHits += "YELLOW-NEGATED-RISK"
        if (yellowHits.isNotEmpty()) return SafetyDecision(Severity.YELLOW, yellowHits, false, "yellow_check")
        return SafetyDecision(Severity.NONE, emptyList(), false)
    }
}
