package com.example.dianzicheng

import com.example.dianzicheng.domain.UserProfile
import com.example.dianzicheng.service.AfuUiParser
import org.junit.Assert.*
import org.junit.Test

class AfuUiParserTest {

    @Test
    fun testParseDetailDialog() {
        val mockScreenTexts = listOf(
            "身体指标记录",
            "共1条记录，更新于08:14",
            "体重 79.05 kg 08:14",
            "内脏脂肪等级 11.0",
            "体脂率 24.3%",
            "脂肪量 19.2kg",
            "皮下脂肪率 17.4%",
            "皮下脂肪量 13.8kg",
            "骨量占比 3.9%",
            "骨量 3.1kg",
            "肌肉率 71.8%",
            "肌肉量 56.8kg",
            "体水分率 51.5%",
            "体水分量 40.7kg",
            "蛋白量占比 19.5%",
            "蛋白量含量 15.4kg",
            "骨骼肌率 37.3%",
            "骨骼肌量 29.5kg",
            "基础代谢 1663.0kcal"
        )

        val result = AfuUiParser.parseScreenTexts(mockScreenTexts, UserProfile())
        assertNotNull("解析结果不应为空", result)
        val m = result!!.measurement

        assertEquals("体重必须准确解析为 79.05", 79.05, m.weightKg, 0.01)
        assertEquals("体脂率必须准确解析为 24.3", 24.3, m.bodyFatPct, 0.01)
        assertEquals("骨骼肌量必须为 29.5（而非错当成体重）", 29.5, m.skeletalMuscleKg, 0.01)
        assertEquals("肌肉量必须为 56.8", 56.8, m.muscleKg, 0.01)
        assertEquals("基础代谢必须为 1663.0", 1663.0, m.basalMetKcal, 0.01)
        assertEquals("内脏脂肪必须为 11", 11, m.visceralFatRating)
        assertEquals("时间字符串必须为 08:14", "08:14", result.timeStr)
        assertTrue("匹配到的指标数量必须达到 17 项左右", result.matchedFieldsCount >= 16)
    }

    @Test
    fun testIgnoreHomeScreenNoise() {
        // 主页噪声文本：含有 "7 项异常"、"较上次下降2.00kg"
        val homeTexts = listOf(
            "身材管理",
            "79.05",
            "较上次下降2.00kg | 08:14",
            "26.1 BMI·偏高",
            "24.3% 体脂率·偏高",
            "7 项异常 测量详情 ▶"
        )

        // 因为没有进入详情面板（没有“身体指标记录”或“内脏脂肪”），必须直接拒绝解析，避免抓到主页残留噪声
        val result = AfuUiParser.parseScreenTexts(homeTexts, UserProfile())
        assertNull("主页非详情面板时必须拒绝解析", result)
    }
}
