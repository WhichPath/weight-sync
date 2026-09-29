package com.example.dianzicheng

import com.example.dianzicheng.domain.UserProfile
import com.example.dianzicheng.service.AfuUiParser
import org.junit.Assert.*
import org.junit.Test

class AfuUiParserTest {

    @Test
    fun testRealUserScrapedLogWithNewWeight() {
        // 用户真实截图场景：点击【测量详情】后，弹窗内完整呈现最新测量的 80.20kg 与 24.9% 体脂
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
            // ── 弹窗开始（只看此分割线以下的数据！）──
            "身体指标记录",
            "共2条记录，更新于21:39",
            "体重 80.20 kg",
            "21:39",
            "内脏脂肪等级 12.0",
            "偏高",
            "体脂率 24.9%",
            "偏高",
            "脂肪量 20.0kg",
            "偏高",
            "皮下脂肪率 17.8%",
            "偏高",
            "皮下脂肪量 14.3kg",
            "偏高",
            "骨量占比 3.9%",
            "偏低",
            "骨量 3.1kg",
            "偏低",
            "肌肉率 71.2%",
            "优",
            "体水分率 50.1%",
            "标准",
            "体水分量 40.2kg",
            "蛋白量占比 20.3%",
            "蛋白量含量 16.3kg",
            "骨骼肌率 36.1%",
            "骨骼肌量 29.0kg",
            "基础代谢 1671.0kcal",
            "确认"
        )

        val result = AfuUiParser.parseScreenTexts(userActualTexts, UserProfile(heightCm = 174.0))
        assertNotNull("解析结果必须成功，不能为 null", result)
        val m = result!!.measurement

        // 验证必须精确锁定为弹窗内的新数据 80.20kg / 24.9%，绝不能被主页历史 78.60kg / 24.2% 污染！
        assertEquals("体重必须准确解析为弹窗内的新体重 80.20", 80.20, m.weightKg, 0.01)
        assertEquals("体脂率必须准确解析为弹窗内的新体脂率 24.9", 24.9, m.bodyFatPct, 0.01)
        assertEquals("内脏脂肪必须解析为 12", 12, m.visceralFatRating)
        assertEquals("脂肪量必须解析为 20.0", 20.0, m.fatMassKg, 0.01)
        assertEquals("皮下脂肪率必须解析为 17.8", 17.8, m.subcutaneousFatPct, 0.01)
        assertEquals("皮下脂肪量必须解析为 14.3", 14.3, m.subcutaneousFatKg, 0.01)
        assertEquals("骨量占比必须解析为 3.9", 3.9, m.boneMassPct, 0.01)
        assertEquals("骨量必须解析为 3.1", 3.1, m.boneMassKg, 0.01)
        assertEquals("肌肉率必须解析为 71.2", 71.2, m.musclePct, 0.01)
        assertEquals("体水分率必须解析为 50.1", 50.1, m.waterPct, 0.01)
        assertEquals("体水分量必须解析为 40.2", 40.2, m.waterKg, 0.01)
        assertEquals("蛋白量占比必须解析为 20.3", 20.3, m.proteinPct, 0.01)
        assertEquals("蛋白量含量必须解析为 16.3", 16.3, m.proteinKg, 0.01)
        assertEquals("骨骼肌率必须解析为 36.1", 36.1, m.skeletalMusclePct, 0.01)
        assertEquals("骨骼肌量必须解析为 29.0", 29.0, m.skeletalMuscleKg, 0.01)
        assertEquals("基础代谢必须解析为 1671.0", 1671.0, m.basalMetKcal, 0.01)
        assertEquals("时间必须解析为 21:39", "21:39", result.timeStr)
    }

    @Test
    fun testUserScreenRecordingVideo2232() {
        // 用户 2.0.6 录屏场景（22:32 测量）：弹窗内包含当前 80.05kg / 24.5% 及底部历史旧数据
        val videoScrapedTexts = listOf(
            "身体指标记录 X",
            "共 3 条记录, 更新于 22:32",
            "体重 80.05 kg 22:32 一",
            "内脏脂肪等级 11.0",
            "体脂率 24.5%",
            "脂肪量 19.6kg",
            "皮下脂肪率 17.5%",
            "皮下脂肪量 14.0kg",
            "骨量占比 4.0%",
            "骨量 3.2kg",
            "肌肉率 71.5%",
            "肌肉量 57.2kg",
            "体水分率 51.5%",
            "体水分量 41.2kg",
            "蛋白量占比 19.3%",
            "蛋白量含量 15.4kg",
            "骨骼肌率 37.2%",
            "骨骼肌量 29.8kg",
            "基础代谢 1675.0kcal",
            "体重 80.20 kg 21:39",
            "体重 78.60 kg 08:03"
        )

        val result = AfuUiParser.parseScreenTexts(videoScrapedTexts, UserProfile(heightCm = 174.0))
        assertNotNull("录屏数据解析必须成功", result)
        val m = result!!.measurement

        assertEquals("体重必须准确解析为当前测量的 80.05kg，不能误取历史 80.20 或 78.60", 80.05, m.weightKg, 0.01)
        assertEquals("体脂率必须准确解析为 24.5%", 24.5, m.bodyFatPct, 0.01)
        assertEquals("内脏脂肪等级必须为 11", 11, m.visceralFatRating)
        assertEquals("脂肪量必须为 19.6kg", 19.6, m.fatMassKg, 0.01)
        assertEquals("皮下脂肪率必须为 17.5%", 17.5, m.subcutaneousFatPct, 0.01)
        assertEquals("皮下脂肪量必须为 14.0kg", 14.0, m.subcutaneousFatKg, 0.01)
        assertEquals("骨量占比必须为 4.0%", 4.0, m.boneMassPct, 0.01)
        assertEquals("骨量必须为 3.2kg", 3.2, m.boneMassKg, 0.01)
        assertEquals("肌肉率必须为 71.5%", 71.5, m.musclePct, 0.01)
        assertEquals("肌肉量必须为 57.2kg", 57.2, m.muscleKg, 0.01)
        assertEquals("体水分率必须为 51.5%", 51.5, m.waterPct, 0.01)
        assertEquals("体水分量必须为 41.2kg", 41.2, m.waterKg, 0.01)
        assertEquals("蛋白量占比必须为 19.3%", 19.3, m.proteinPct, 0.01)
        assertEquals("蛋白量含量必须为 15.4kg", 15.4, m.proteinKg, 0.01)
        assertEquals("骨骼肌率必须为 37.2%", 37.2, m.skeletalMusclePct, 0.01)
        assertEquals("骨骼肌量必须为 29.8kg", 29.8, m.skeletalMuscleKg, 0.01)
        assertEquals("基础代谢必须为 1675.0", 1675.0, m.basalMetKcal, 0.01)
        assertEquals("时间必须解析为 22:32", "22:32", result.timeStr)
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
