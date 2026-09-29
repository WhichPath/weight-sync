package com.example.dianzicheng

import com.example.dianzicheng.domain.UserProfile
import com.example.dianzicheng.service.AfuUiParser
import org.junit.Assert.*
import org.junit.Test

class AfuUiParserTest {

    @Test
    fun testRealUserScrapedLog() {
        val userActualTexts = listOf(
            "身材管理",
            "fmt",
            "👋",
            "original",
            "连接设备，同步身体数据",
            "体重单位切换",
            "kg",
            "斤",
            "2026年9月29日",
            "78.60",
            "较上次下降0.45kg",
            "08:03",
            "26.0",
            "BMI·偏高",
            "24.2%",
            "体脂率·偏高",
            "7",
            "项异常",
            "测量详情",
            "数据来源：蚂蚁阿福&沃莱科技体脂秤",
            "核心指标",
            "BODY TRENDS",
            "近7次",
            "体重",
            "BMI",
            "体脂率",
            "腰围",
            "单位：kg",
            "脂肪管理",
            "脂肪量",
            "内脏脂肪等级",
            "皮下脂肪量",
            "皮下脂肪率",
            "肌肉状态",
            "肌肉量",
            "肌肉率",
            "骨骼肌量",
            "骨骼肌率",
            "- 没有更多内容了，查看我的基础信息 -",
            "身高",
            "174",
            "cm",
            "更新于6月27日",
            "编辑身高",
            "年龄",
            "27",
            "岁",
            "编辑年龄",
            "去记录",
            "AI智能解读",
            "连接中，请轻踩体脂秤唤起设备",
            "设备数据来源",
            "请赤脚上秤，保持身体稳定",
            "体脂秤测量",
            "79.85",
            "体重测量中",
            "体重测量",
            "体脂测量",
            "测量完成",
            "我知道了",
            "测重中，请保持稳定，请勿下秤",
            "80.35",
            "80.25",
            "测脂中，请保持稳定，请勿下秤",
            "80.20",
            "身体成分测量中",
            "体脂秤数据同步中",
            "正在分析测量结果…",
            "体脂秤最新数据已同步",
            "数据加载中",
            "回到身材管理页面，查看详细数据",
            "上一次",
            "较上次上升1.60kg",
            "21:39",
            "26.5",
            "24.9%",
            "身体指标记录",
            "共2条记录，更新于21:39",
            "12.0",
            "偏高",
            "20.0kg",
            "17.8%",
            "14.3kg",
            "骨量占比",
            "3.9%",
            "偏低",
            "骨量",
            "3.1kg",
            "71.2%",
            "优",
            "57.1kg",
            "体水分率",
            "50.1%",
            "标准",
            "体水分量",
            "40.2kg",
            "蛋白量占比",
            "20.3%",
            "蛋白量含量",
            "16.3kg",
            "36.1%",
            "29.0kg",
            "基础代谢",
            "1671.0kcal",
            "确认"
        )

        val result = AfuUiParser.parseScreenTexts(userActualTexts, UserProfile(heightCm = 174.0))
        assertNotNull("解析结果必须成功，不能为 null", result)
        val m = result!!.measurement

        // 验证核心数据（对应日志中的真实数值）
        assertEquals("体重必须准确解析为 78.60", 78.60, m.weightKg, 0.01)
        assertEquals("体脂率必须准确解析为 24.2", 24.2, m.bodyFatPct, 0.01)
        assertEquals("内脏脂肪必须解析为 12", 12, m.visceralFatRating)
        assertEquals("脂肪量必须解析为 20.0", 20.0, m.fatMassKg, 0.01)
        assertEquals("皮下脂肪率必须解析为 17.8", 17.8, m.subcutaneousFatPct, 0.01)
        assertEquals("皮下脂肪量必须解析为 14.3", 14.3, m.subcutaneousFatKg, 0.01)
        assertEquals("骨量占比必须解析为 3.9", 3.9, m.boneMassPct, 0.01)
        assertEquals("骨量必须解析为 3.1", 3.1, m.boneMassKg, 0.01)
        assertEquals("肌肉率必须解析为 71.2", 71.2, m.musclePct, 0.01)
        assertEquals("肌肉量必须解析为 57.1", 57.1, m.muscleKg, 0.01)
        assertEquals("体水分率必须解析为 50.1", 50.1, m.waterPct, 0.01)
        assertEquals("体水分量必须解析为 40.2", 40.2, m.waterKg, 0.01)
        assertEquals("蛋白量占比必须解析为 20.3", 20.3, m.proteinPct, 0.01)
        assertEquals("蛋白量含量必须解析为 16.3", 16.3, m.proteinKg, 0.01)
        assertEquals("骨骼肌率必须解析为 36.1", 36.1, m.skeletalMusclePct, 0.01)
        assertEquals("骨骼肌量必须解析为 29.0", 29.0, m.skeletalMuscleKg, 0.01)
        assertEquals("基础代谢必须解析为 1671.0", 1671.0, m.basalMetKcal, 0.01)
        assertEquals("时间必须解析为 21:39", "21:39", result.timeStr)
        assertTrue("有效匹配项数必须达到 15 项以上", result.matchedFieldsCount >= 15)
    }

    @Test
    fun testIgnoreHomeScreenNoise() {
        val homeTexts = listOf(
            "身材管理",
            "79.05",
            "较上次下降2.00kg | 08:14",
            "26.1 BMI·偏高",
            "24.3% 体脂率·偏高",
            "7 项异常 测量详情 ▶"
        )
        val result = AfuUiParser.parseScreenTexts(homeTexts, UserProfile())
        assertNull("主页非详情面板时必须拒绝解析", result)
    }
}
