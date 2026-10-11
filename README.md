# 保研陪伴（BaoyanCompanion）

面向高校推免（保研）场景的 AI 陪伴应用 —— 把校方政策变成可计算的加分规则库，把竞赛情报变成自动提醒，把学生行动沉淀为可追踪的数据。

> 参赛作品：2026 年 iCAN 大学生创新创业大赛 · AI 应用创新挑战赛 · 软件赛道（个人参赛）

## 它能做什么

- **AI 对话即操作**：所有操作（加任务、记竞赛、导政策、报奖项、加监控）都用自然语言完成。AI 以结构化动作块 `<ACTIONS>…</ACTIONS>` 与记忆块 `<MEMORY>…</MEMORY>` 直接读写作本地数据。
- **政策导入与多类型识别**：上传 PDF/Word，AI 自动判断类型并分派记录——加分政策 → 竞赛目录 + 评分规则；竞赛目录 → 清单；论文目录 → 论文库；其他 → 资料库。
- **加分计算（先确认后计入）**：竞赛目录只作参考、不计分；用户说出获奖后，AI 按"目录类别 + 评分规则"算出分值并**询问确认**，确认后才计入总分。
- **竞赛报名监控**：AI 提议候选官网 + 程序逐个探测验证（零幻觉），定时抓取页面并由大模型判定报名状态；开始报名/临近截止时推送通知。
- **学习与规划**：任务管理、番茄钟、一键生成周计划。
- **数据自有**：全部本地保存；支持备份/恢复（排除 API Key）与导出申报清单。

内置《中国地质大学（武汉）大学生学科竞赛目录》(148 项，A/B/C 类) 与 2026 推免素质成绩评分规则。

## 技术栈

Android 原生 · Kotlin · Jetpack Compose · DeepSeek 大模型 · OkHttp · PDFBox-android · Apache POI 3.17 · WorkManager · SharedPreferences(JSON)

- Gradle 9.6 / AGP 9.4 / Kotlin 2.2；compileSdk/targetSdk 37，minSdk 24
- 单元测试 13 项（日期解析 / 状态映射 / HTML 清洗 / 备份 / 报告生成）

## 构建与运行

```
set JAVA_HOME=<Android Studio 自带 JBR 目录>
gradlew.bat assembleDebug        # 产出 app\build\outputs\apk\debug\app-debug.apk
gradlew.bat testDebugUnitTest    # 运行单元测试
```

安装到手机后，进入「我的 → 设置」填入 **DeepSeek API Key**（需自备），即可使用 AI 功能。

## 目录结构

```
app/src/main/java/com/example/baoyancompanion/
  MainActivity.kt          # 界面（AI 助手 / 我的）、对话、动作解析、报告导出
  CompetitionMonitor.kt    # 竞赛报名监控（抓取 / 大模型判定 / 定时任务）
  DefaultData.kt           # 内置竞赛目录（148 项）+ 评分规则
  ui/theme/                # 主题
app/src/test/              # 单元测试
```

## 数据与隐私

所有数据保存在手机本地；仅按需调用 DeepSeek API 与抓取你所设置的竞赛网址；备份导出会自动排除 API Key。
