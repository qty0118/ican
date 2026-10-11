package com.example.baoyancompanion

import org.junit.Assert.assertTrue
import org.junit.Test

class ReporterTest {

    @Test
    fun report_containsAllSectionsAndData() {
        val tasks = listOf(Task("背单词200个", "2026-10-20", false))
        val comps = listOf(Competition("全国大学生数学建模竞赛", "2026-11-01"))
        val bonuses = listOf(Bonus("学科竞赛全国二等奖", 4.0, "学科竞赛", "", true))
        val profile = UserProfile(
            school = "中国地质大学（武汉）", major = "信息管理", grade = "大三",
            rank = "5/60", gpa = "3.8", english = "CET6-500", research = "无", competitions = "数模省二"
        )
        val watched = listOf(
            WatchedCompetition("赛氪竞赛网", "https://www.saikr.com", "聚合平台", "", "", "enrolling", 0L, "报名中")
        )
        val papers = listOf(PaperEntry("SCI 一区", "A", "期刊", ""))
        val docs = listOf(ReferenceDoc("实习认定办法", "需满 3 个月", "其他"))
        val catalog = listOf(CatalogItem("全国大学生数学建模竞赛", "A类", 4.0, ""))

        val r = Reporter.buildFrom(
            tasks, comps, bonuses, profile, watched, papers, docs, catalog, "2028-09-01", 0L
        )

        assertTrue(r.contains("# 保研加分与规划清单"))
        assertTrue(r.contains("目标日期：2028-09-01"))
        assertTrue(r.contains("中国地质大学（武汉）"))
        assertTrue(r.contains("合计：4.0 分，共 1 项"))
        assertTrue(r.contains("- 学科竞赛全国二等奖 +4.0"))
        assertTrue(r.contains("## 竞赛目录"))
        assertTrue(r.contains("- 全国大学生数学建模竞赛｜A类｜4.0 分"))
        assertTrue(r.contains("赛氪竞赛网｜报名中"))
        assertTrue(r.contains("SCI 一区"))
        assertTrue(r.contains("- [ ] 背单词200个"))
    }

    @Test
    fun report_hasSummaryWhenDataPresent() {
        val r = Reporter.buildFrom(
            listOf(Task("t")), emptyList(), listOf(Bonus("x", 2.0, "c", "", true)), UserProfile(),
            emptyList(), emptyList(), emptyList(), listOf(CatalogItem("y", "A类")), "", 0L, 3
        )
        assertTrue(r.contains("## 汇总"))
        assertTrue(r.contains("已获得加分：2.0 分（1 项）"))
        assertTrue(r.contains("番茄钟：3 个"))
        assertTrue(r.contains("— 由「保研陪伴」生成 —"))
    }

    @Test
    fun report_handlesEmptyData() {
        val r = Reporter.buildFrom(
            emptyList(), emptyList(), emptyList(), UserProfile(), emptyList(),
            emptyList(), emptyList(), emptyList(), "", 0L
        )
        assertTrue(r.contains("# 保研加分与规划清单"))
        assertTrue(!r.contains("## "))
    }

    @Test
    fun report_keepsItemsButOmitsZeroPointSuffix() {
        val bonuses = listOf(
            Bonus("没加分的项", 0.0, "", "", true),
            Bonus("有两分的项", 2.0, "", "", true)
        )
        val r = Reporter.buildFrom(
            emptyList(), emptyList(), bonuses, UserProfile(), emptyList(),
            emptyList(), emptyList(), emptyList(), "", 0L
        )
        assertTrue(r.contains("- 没加分的项"))
        assertTrue(r.contains("- 有两分的项 +2.0"))
        assertTrue(!r.contains("+0"))
    }
}
