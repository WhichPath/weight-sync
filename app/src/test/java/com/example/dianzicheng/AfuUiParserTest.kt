package com.example.dianzicheng

import com.example.dianzicheng.domain.UserProfile
import com.example.dianzicheng.service.AfuUiParser
import com.example.dianzicheng.service.AfuUiParser.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阿福【身体指标记录】弹窗解析回归测试。
 *
 * 用例全部来自真机实拍/实采：2.0.7 曾在真机上把 80.05kg 的新测量解析成 80.2kg / 22.0%
 * （0.15kg 以外的旧记录 + 把“22:32”当成 22.0% 体脂），因此这里是必须守住的回归线。
 */
class AfuUiParserTest {

    /** 真机 22:57 排查日志对应的弹窗内容（标签与数值是独立节点） */
    private val realSheetSplitNodes = listOf(
        "身体指标记录",
        "共3条记录，更新于22:32",
        "体重",
        "80.05",
        "kg",
        "22:32",
        "内脏脂肪等级",
        "11.0",
        "偏高",
        "体脂率",
        "24.5%",
        "偏高",
        "脂肪量",
        "19.6kg",
        "偏高",
        "皮下脂肪率",
        "17.5%",
        "偏高",
        "皮下脂肪量",
        "14.0kg",
        "偏高",
        "骨量占比",
        "4.0%",
        "偏低",
        "骨量",
        "3.2kg",
        "偏低",
        "肌肉率",
        "71.5%",
        "优",
        "肌肉量",
        "57.2kg",
        "优",
        "体水分率",
        "51.5%",
        "标准",
        "体水分量",
        "41.2kg",
        "标准",
        "蛋白量占比",
        "19.3%",
        "优",
        "蛋白量含量",
        "15.4kg",
        "优",
        "骨骼肌率",
        "37.2%",
        "标准",
        "骨骼肌量",
        "29.8kg",
        "标准",
        "基础代谢",
        "1675.0kcal",
        "体重",
        "80.20kg",
        "21:39",
        "体重",
        "78.60kg",
        "08:03",
        "确认"
    )

    @Test
    fun realDeviceSheet_mustUseNewestRecordOnly() {
        val snapshot = AfuUiParser.parseSheet(realSheetSplitNodes)
        assertNotNull("真实弹窗快照必须解析成功", snapshot)
        val s = snapshot!!

        // 体重必须来自弹窗内最新一条记录的“体重”表头，不能是主页卡片 / 历史记录
        assertTrue("必须锚定到目标记录", s.anchored)
        assertEquals("体重必须为最新一次的 80.05kg", 80.05, s.weightKg!!, 0.001)
        assertEquals("时间必须为最新一次的 22:32", "22:32", s.timeStr)

        // 2.0.7 的致命 bug：把 22:32 当成 22.0% 体脂
        assertEquals("体脂率必须为 24.5，绝不能取时间 22:32 的 22.0", 24.5, s.metrics["fat"]!!, 0.001)
        assertEquals("内脏脂肪等级必须为 11", 11.0, s.metrics["visceral"]!!, 0.001)
        assertEquals("脂肪量必须为 19.6", 19.6, s.metrics["fatMass"]!!, 0.001)
        assertEquals("皮下脂肪率必须为 17.5", 17.5, s.metrics["subFatPct"]!!, 0.001)
        assertEquals("皮下脂肪量必须为 14.0", 14.0, s.metrics["subFatKg"]!!, 0.001)
        assertEquals("骨量占比必须为 4.0", 4.0, s.metrics["bonePct"]!!, 0.001)
        assertEquals("骨量必须为 3.2", 3.2, s.metrics["bone"]!!, 0.001)
        assertEquals("肌肉率必须为 71.5", 71.5, s.metrics["musclePct"]!!, 0.001)
        assertEquals("肌肉量必须为 57.2", 57.2, s.metrics["muscle"]!!, 0.001)
        assertEquals("体水分率必须为 51.5", 51.5, s.metrics["water"]!!, 0.001)
        assertEquals("体水分量必须为 41.2", 41.2, s.metrics["waterKg"]!!, 0.001)
        assertEquals("蛋白量占比必须为 19.3", 19.3, s.metrics["protein"]!!, 0.001)
        assertEquals("蛋白量含量必须为 15.4（不能被误当成骨骼肌量）", 15.4, s.metrics["proteinKg"]!!, 0.001)
        assertEquals("骨骼肌率必须为 37.2", 37.2, s.metrics["skelMusclePct"]!!, 0.001)
        assertEquals("骨骼肌量必须为 29.8", 29.8, s.metrics["skelMuscleKg"]!!, 0.001)
        assertEquals("基础代谢必须为 1675.0", 1675.0, s.metrics["bmr"]!!, 0.001)

        // 一次称重 = 体重 + 16 项成分
        assertEquals("必须完整拿到 16 项成分", 16, s.metrics.size)
        assertEquals("含体重共 17 项", 17, s.metrics.size + 1)
    }

    @Test
    fun scrolledSnapshot_withoutRecordHeader_stillCollectsRows() {
        // 用户向下滚动后，最新记录的表头已滚出屏幕；下方是两条折叠的历史记录
        val scrolled = listOf(
            "身体指标记录",
            "共3条记录，更新于22:32",
            "骨量占比",
            "4.0%",
            "偏低",
            "骨量",
            "3.2kg",
            "偏低",
            "肌肉率",
            "71.5%",
            "优",
            "肌肉量",
            "57.2kg",
            "优",
            "体水分率",
            "51.5%",
            "标准",
            "体水分量",
            "41.2kg",
            "标准",
            "蛋白量占比",
            "19.3%",
            "优",
            "蛋白量含量",
            "15.4kg",
            "优",
            "骨骼肌率",
            "37.2%",
            "标准",
            "骨骼肌量",
            "29.8kg",
            "标准",
            "基础代谢",
            "1675.0kcal",
            "体重",
            "80.20kg",
            "21:39",
            "体重",
            "78.60kg",
            "08:03",
            "确认"
        )
        val snapshot = AfuUiParser.parseSheet(scrolled)
        assertNotNull(snapshot)
        val s = snapshot!!

        // 表头已滚出：不能锚定、不能给出体重（体重沿用会话锚点）
        assertFalse("表头滚出屏幕时不应锚定", s.anchored)
        assertNull("表头滚出屏幕时不应凭空给出体重", s.weightKg)

        // 但滚出来的行必须照常收集
        assertEquals(4.0, s.metrics["bonePct"]!!, 0.001)
        assertEquals(3.2, s.metrics["bone"]!!, 0.001)
        assertEquals(71.5, s.metrics["musclePct"]!!, 0.001)
        assertEquals(57.2, s.metrics["muscle"]!!, 0.001)
        assertEquals(51.5, s.metrics["water"]!!, 0.001)
        assertEquals(41.2, s.metrics["waterKg"]!!, 0.001)
        assertEquals(19.3, s.metrics["protein"]!!, 0.001)
        assertEquals(15.4, s.metrics["proteinKg"]!!, 0.001)
        assertEquals(37.2, s.metrics["skelMusclePct"]!!, 0.001)
        assertEquals(29.8, s.metrics["skelMuscleKg"]!!, 0.001)
        assertEquals(1675.0, s.metrics["bmr"]!!, 0.001)
        assertEquals(11, s.metrics.size)
    }

    @Test
    fun topAndScrolledSnapshots_mergeIntoFullSeventeenItems() {
        // 顶部快照（含表头 + 前 5 项）与滚动快照（后 11 项）合并后应还原完整 17 项
        val top = listOf(
            "共3条记录，更新于22:32",
            "体重",
            "80.05",
            "kg",
            "22:32",
            "内脏脂肪等级",
            "11.0",
            "偏高",
            "体脂率",
            "24.5%",
            "偏高",
            "脂肪量",
            "19.6kg",
            "偏高",
            "皮下脂肪率",
            "17.5%",
            "偏高",
            "皮下脂肪量",
            "14.0kg",
            "偏高",
            "确认"
        )
        val scrolled = listOf(
            "共3条记录，更新于22:32",
            "骨量占比",
            "4.0%",
            "偏低",
            "骨量",
            "3.2kg",
            "偏低",
            "肌肉率",
            "71.5%",
            "优",
            "肌肉量",
            "57.2kg",
            "优",
            "体水分率",
            "51.5%",
            "标准",
            "体水分量",
            "41.2kg",
            "标准",
            "蛋白量占比",
            "19.3%",
            "优",
            "蛋白量含量",
            "15.4kg",
            "优",
            "骨骼肌率",
            "37.2%",
            "标准",
            "骨骼肌量",
            "29.8kg",
            "标准",
            "基础代谢",
            "1675.0kcal",
            "确认"
        )

        val topSnapshot = AfuUiParser.parseSheet(top)
        val scrolledSnapshot = AfuUiParser.parseSheet(scrolled)
        assertNotNull(topSnapshot)
        assertNotNull(scrolledSnapshot)
        assertEquals("顶部快照必须锚定并拿到 5 项成分", 5, topSnapshot!!.metrics.size)
        assertEquals(80.05, topSnapshot.weightKg!!, 0.001)
        // 滚到底部时表头滚出、且下方没有其他记录，按降级规则仍然解析
        assertEquals(11, scrolledSnapshot!!.metrics.size)

        val merged = LinkedHashMap(topSnapshot.metrics).apply { putAll(scrolledSnapshot.metrics) }
        assertEquals("两次快照合并后必须是完整 17 项", 16, merged.size)
        assertEquals(24.5, merged["fat"]!!, 0.001)
        assertEquals(29.8, merged["skelMuscleKg"]!!, 0.001)
    }

    @Test
    fun mergedLabelNodes_areAlsoSupported() {
        // 部分机型/版本会把“标签 + 数值”合成一个节点
        val mergedNodes = listOf(
            "身体指标记录",
            "共 2 条记录, 更新于 21:39",
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
            "肌肉量 57.1kg",
            "优",
            "体水分率 50.1%",
            "标准",
            "体水分量 40.2kg",
            "标准",
            "蛋白量占比 20.3%",
            "优",
            "蛋白量含量 16.3kg",
            "优",
            "骨骼肌率 36.1%",
            "标准",
            "骨骼肌量 29.0kg",
            "标准",
            "基础代谢 1671.0kcal",
            "确认"
        )
        val snapshot = AfuUiParser.parseSheet(mergedNodes)
        assertNotNull(snapshot)
        val s = snapshot!!
        assertEquals(80.20, s.weightKg!!, 0.001)
        assertEquals("21:39", s.timeStr)
        assertEquals(12.0, s.metrics["visceral"]!!, 0.001)
        assertEquals(24.9, s.metrics["fat"]!!, 0.001)
        assertEquals(20.0, s.metrics["fatMass"]!!, 0.001)
        assertEquals(17.8, s.metrics["subFatPct"]!!, 0.001)
        assertEquals(14.3, s.metrics["subFatKg"]!!, 0.001)
        assertEquals(3.9, s.metrics["bonePct"]!!, 0.001)
        assertEquals(3.1, s.metrics["bone"]!!, 0.001)
        assertEquals(71.2, s.metrics["musclePct"]!!, 0.001)
        assertEquals(57.1, s.metrics["muscle"]!!, 0.001)
        assertEquals(50.1, s.metrics["water"]!!, 0.001)
        assertEquals(40.2, s.metrics["waterKg"]!!, 0.001)
        assertEquals(20.3, s.metrics["protein"]!!, 0.001)
        assertEquals(16.3, s.metrics["proteinKg"]!!, 0.001)
        assertEquals(36.1, s.metrics["skelMusclePct"]!!, 0.001)
        assertEquals(29.0, s.metrics["skelMuscleKg"]!!, 0.001)
        assertEquals(1671.0, s.metrics["bmr"]!!, 0.001)
    }

    @Test
    fun olderRecordExpanded_mustNotBeCapturedAsNewest() {
        // 用户展开的是 21:39 那条旧记录，弹窗表头依旧写着“更新于22:32”
        val olderRecord = listOf(
            "身体指标记录",
            "共3条记录，更新于22:32",
            "体重",
            "80.20kg",
            "21:39",
            "内脏脂肪等级",
            "12.0",
            "偏高",
            "体脂率",
            "24.9%",
            "偏高",
            "基础代谢",
            "1671.0kcal",
            "体重",
            "78.60kg",
            "08:03",
            "确认"
        )
        val snapshot = AfuUiParser.parseSheet(olderRecord)
        assertNotNull(snapshot)
        val s = snapshot!!
        assertFalse("旧记录不能被当成最新记录锚定", s.anchored)
        assertNull("旧记录不能提供体重", s.weightKg)
        assertTrue("旧记录的指标必须被拒收", s.metrics.isEmpty())
    }

    @Test
    fun homeScreenNoise_isRejected() {
        val home = listOf(
            "身材管理",
            "fmt",
            "👋",
            "original",
            "体重单位切换",
            "kg",
            "斤",
            "2026年9月29日",
            "80.20",
            "上一次",
            "80.05",
            "较上次下降0.15kg",
            "22:32",
            "26.4",
            "BMI·偏高",
            "24.5%",
            "体脂率·偏高",
            "7",
            "项异常",
            "测量详情",
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
            "去记录",
            "AI智能解读",
            "设备数据来源"
        )
        assertNull("主页节点绝不能解析出测量数据", AfuUiParser.parseSheet(home))
    }

    @Test
    fun buildMeasurement_mapsEveryField() {
        val metrics = mapOf(
            Metric.VISCERAL.key to 11.0,
            Metric.BODY_FAT.key to 24.5,
            Metric.FAT_MASS.key to 19.6,
            Metric.SUB_FAT_PCT.key to 17.5,
            Metric.SUB_FAT_KG.key to 14.0,
            Metric.BONE_PCT.key to 4.0,
            Metric.BONE_KG.key to 3.2,
            Metric.MUSCLE_PCT.key to 71.5,
            Metric.MUSCLE_KG.key to 57.2,
            Metric.WATER_PCT.key to 51.5,
            Metric.WATER_KG.key to 41.2,
            Metric.PROTEIN_PCT.key to 19.3,
            Metric.PROTEIN_KG.key to 15.4,
            Metric.SKELETAL_PCT.key to 37.2,
            Metric.SKELETAL_KG.key to 29.8,
            Metric.BMR.key to 1675.0
        )
        val epoch = 1759156320000L
        val m = AfuUiParser.buildMeasurement(80.05, epoch, metrics, UserProfile(heightCm = 174.0))

        assertEquals(epoch, m.measuredAtEpochMs)
        assertEquals(80.05, m.weightKg, 0.001)
        assertEquals(26.4, m.bmi, 0.05)
        assertEquals(24.5, m.bodyFatPct, 0.001)
        assertEquals(57.2, m.muscleKg, 0.001)
        assertEquals(51.5, m.waterPct, 0.001)
        assertEquals(19.3, m.proteinPct, 0.001)
        assertEquals(3.2, m.boneMassKg, 0.001)
        assertEquals(11, m.visceralFatRating)
        assertEquals(1675.0, m.basalMetKcal, 0.001)
        assertEquals(19.6, m.fatMassKg, 0.001)
        assertEquals(17.5, m.subcutaneousFatPct, 0.001)
        assertEquals(14.0, m.subcutaneousFatKg, 0.001)
        assertEquals(4.0, m.boneMassPct, 0.001)
        assertEquals(71.5, m.musclePct, 0.001)
        assertEquals(41.2, m.waterKg, 0.001)
        assertEquals(15.4, m.proteinKg, 0.001)
        assertEquals(37.2, m.skeletalMusclePct, 0.001)
        assertEquals(29.8, m.skeletalMuscleKg, 0.001)
    }
}
