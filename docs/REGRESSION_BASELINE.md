# 回归基线（Regression Baseline）

> **基线版本**：v2.4.4（versionCode 12）
> **基线日期**：2026-08-25
> **用途**：大型重构（v2.5.0）各阶段完成后，逐项对照本文档验证行为无回归。
> 本文档由「阶段0：测试安全网与回归基线」建立，后续阶段只可追加结果，不可删改基线条目。

---

## 1. 功能回归清单

对照 README「验收清单（优化文档 §11）」，每项在基线版本上均为已通过状态：

| # | 回归项 | 基线行为（v2.4.4） | 验证方式 | 自动化覆盖 |
|---|--------|--------------------|----------|------------|
| 1 | 无障碍浮窗自动弹出 | 开启无障碍后打开京东商品页，自动弹出浮窗（价格/商品名/按钮） | 真机 | 无（依赖无障碍服务，需真机） |
| 2 | 浮窗交互 | 浮窗不遮挡操作、可拖动、可关闭、15s 自动消失 | 真机 | 无（同上） |
| 3 | B 站翻车/推荐 Chip | "翻车"红色 Chip / "推荐"绿色 Chip | 真机 | 部分（`ContentRiskTest` 覆盖命中规则） |
| 4 | 价格曲线 | 最低/最高虚线 + 当前脉冲点 + 大促灰线 | 真机目视 | 无（纯绘制层） |
| 5 | "先涨后降"检测 | current ≥ 近 7 点均价 × 1.10 → 疑似；current ≤ 历史最低 × 1.05 → 低价 | 真机 + 单测 | `PriceJudgmentTest` |
| 6 | 券一键复制 | 复制券码 + Snackbar 反馈 | 真机 | 无 |
| 7 | 值得买信息 | 关键词高亮 + 值/不值进度条 | 真机 | 无 |
| 8 | 动画绘制通道 | 全部走 `graphicsLayer` / `drawBehind` | 代码走查 | 无 |
| 9 | 暗色模式 | 跟随系统深浅色切换 | 真机 | 无 |
| 10 | 后台盯价 | 30min 周期 + 电量约束（WorkManager） | 真机/日志 | 无 |
| 11 | 缓存预算 | 分级缓存 ≤ 30MB；APK < 20MB | 构建产物 + 真机 | 部分（`TLRUCacheTest` 覆盖淘汰/容量） |
| 12 | 真机性能指标 | 冷启动 < 800ms、60fps、Storage 占用 | 真机实机验证 | 无 |

> 浮点边界注记（回归项 5，v2.4.4 实测基线）：因 1.10 的 double 表示 ≈ 1.1000000000000001，均价恰为 100 时 current=110.0 实际判为 NORMAL（名义边界点等效严格 >）。重构时不得"修复"此行为，除非产品确认。

## 2. 自动化测试基线（阶段0 建立）

单测目录：`app/src/test/java/com/pricelens/`

| 测试类 | 被测对象 | 用例数 | 覆盖要点 |
|--------|----------|--------|----------|
| `util/PriceFormatterTest` | `PriceFormatter.format/formatRaw` | 5 | ¥ 前缀、千分位、两位小数、进位、零值 |
| `util/PriceJudgmentTest` | `judgePrice()` | 7 | 空历史、LOW/NORMAL/SUSPICIOUS 阈值边界、LOW 优先、近 7 点均价窗口、标签文案 |
| `data/cache/TLRUCacheTest` | `TLRUCache` | 10 | put/get、字节数统计、过期 stale-while-revalidate、clearExpired、过期优先/LRU 淘汰、pin 豁免、onStale 异步回调（虚拟时钟 + Turbine） |
| `util/RateLimiterTest` | `RateLimiter` / `UserAgents` | 7 | 结果透传、403 熔断生效与过期、同域节流间隔、跨域不互等、并发域名信号量上限、UA 池周期 5 轮换 |
| `util/ContentRiskTest` | `ContentRiskRules.assess` | 7 | 商单/夸大词命中、词库顺序取首中、大小写不敏感、联合投稿标记、双标记叠加 |

**合计 36 个用例**。重构各阶段提交前必须 `.\gradlew.bat test` 全绿。

### 说明

- `RateLimiter` 节流间隔直接读真实墙钟（不可注入时钟），测试采用「缩短间隔 + 真实计时」验证，未使用 Robolectric；该类无 `android.util.Log` 等 Android 依赖，可直接跑 JVM 单测。
- 5 个目标类均为纯 JVM 实现，无类因 Android 框架依赖被跳过。
- `util/` 其余类（`ShizukuHelper`、`ScriptStore`、`UrlOpener`、`WbiSigner` 等）依赖 Android 框架或本次范围外，暂不覆盖。

## 3. 基线命令

```powershell
# 项目根目录执行
.\gradlew.bat test                 # 单元测试（必须全绿）
.\gradlew.bat :app:assembleDebug   # Debug 构建（必须成功）
```

## 4. 阶段对照记录

| 阶段 | 完成日期 | `test` 结果 | 真机回归项 | 备注 |
|------|----------|-------------|-----------|------|
| 阶段0（本基线） | 2026-08-25 | 见阶段0报告 | — | 建立基线 |
| | | | | |
