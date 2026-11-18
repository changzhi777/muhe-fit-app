# 代码审查报告（全量静态审查 + 修复批次）

> 日期: 2026-11-18 | 范围: android-app 全部 Kotlin 源（main 62 + test 17）+ gradle 全套 + Manifest + strings 双语 + 测试基建
> 方式: 四路并行深审（sync/net/di · data/构建 · screens/导航/主题 · viewmodel/lib/test/strings）→ 逐条人工核实 → 高置信度项直接修复
> ⚠️ 本沙箱无 Android SDK/gradle，修复为静态修改 + 括号/引用/键 diff 自查；**待用户侧 `./gradlew :app:assembleDebug && ./gradlew :app:test` 复验**

---

## 〇、构建实况勘误（先于一切发现）

**CLAUDE.md「构建未验证」口径已过时**：工程实际已跑通——`app/build/outputs/apk/debug/app-debug.apk`（14MB）已产出，`test-results` 显示 **78 个测试 × debug/release 两变体全绿**（Onerm 5 / Backoff 5 / FinishWorkout 3 / Progression 19 / CheckIn 13 / StatsCalc 18 / StateMerge 15）。gradle wrapper jar 已生成（8.10，另有 9.7.0 运行痕迹）。 golden 243 例 + 各纯函数对拍**已实跑闭环**，此前所有「待首建验证」特列大部分已被这次构建背书（CameraX/HorizontalPager/权限流/防抖循环等静态自查项均编译通过）。

---

## 一、已修复（P0/P1，共 11 项）

### P0

| # | 问题 | 修复 |
|---|------|------|
| P0-1 | **MuheNavHost.kt 组合期 Toast 探针遗留**：`BoxWithConstraints { Toast.makeText(...).show() }` 写在 composable 体内，每次重组必弹一个 LENGTH_LONG（路由切换/底栏选中/任何 snapshot 写入都触发），线上必现 | 整段删除（含 BoxWithConstraints 包装、重复 `dp` import、无用的 WindowInsets/ime/systemBars/LocalDensity/LocalView/LocalConfiguration import） |

### P1 — UI/导航横切面

| # | 问题 | 修复 |
|---|------|------|
| P1-1 | **edge-to-edge 下内容画进系统栏底下**：`contentWindowInsets = WindowInsets(0,0,0,0)` 显式清零，一级 Tab 标题行顶进状态栏；二级页底栏隐藏时底部贴进手势条 | 撤除清零，恢复 Scaffold 默认 systemBars insets（一级 Tab 吃 statusBar padding，二级页 navigationBar 由 content padding 回收） |
| P1-2 | **系统栏图标随系统明暗**：无参 `enableEdgeToEdge()` 跟随系统，系统浅色 + 本 app 深色唯一主题时亮底色图标画在 `#0F1319` 近黑画布上不可读 | `enableEdgeToEdge(statusBarStyle/navBarStyle = SystemBarStyle.dark(TRANSPARENT))`——深色唯一裁定下固定值 |
| P1-3 | **配对成功返回「我的」仍显示访客**：ProfileViewModel 只有 `init{refresh()}`，popBackStack 返回时 VM 存活不重建 | VM 注入 SyncStateBus（Ok → refresh，首拉落库数据即见）+ ProfileScreen 挂 ON_RESUME → refresh（登录/打卡返回都覆盖）；streak 口径同步改全史（见 P2-6） |
| P1-4 | **打卡卡轨 ◀▶ 重排后页面错位**：Pager 对位 `LaunchedEffect(cards.size, lastCardId)` 不含卡序，moveCard 不改 size → effect 不重跑，页面内容跳变、lastCardId 与眼前卡片分叉 | key 改 `LaunchedEffect(cards, lastCardId)`（List.equals 逐元素，重排即重跑） |
| P1-5 | **编辑卡把卡挤到卡轨末位**：updateCard 单条 `upsert`（REPLACE）给被编辑行发新 rowid，而 moveCard 明确依赖「rowid 序 = 列表序」——编辑后卡轨顺序突变并随 push 同步出去 | updateCard 改为 DB 实读列表原位替换后 `upsertAll` 整表重插（与 moveCard 同口径） |
| P1-6 | **release 混淆规则全注释却已开 minify+shrink 且 1.0.1 已申报**：出货风险面 | proguard-rules.pro 转正：keepattributes + @Serializable DTO/serializer keep + zxing 留位；头注保留「release 构建后真机冒烟」门禁要求 |
| P1-7 | **Bearer token 明文随云备份/D2D 迁移外带**：allowBackup=true 且无 backup 规则 | 新增 `res/xml/backup_rules.xml`（fullBackupContent，API≤30）+ `data_extraction_rules.xml`（cloud-backup + device-transfer），排除 `muhe_auth.xml`；训练数据（Room）正常备份 |

### P1 — 同步层

| # | 问题 | 修复 |
|---|------|------|
| P1-8 | **SyncScheduler 单消费者循环无异常护栏**：`client.syncNow()` 多条真实路径会抛（buildLocalDoc 读表/errorBody 读取/409 合并落库）——未捕获异常杀死消费者协程，此后所有 requestSync 静默积压永不执行；且 Syncing 已发布终态永不到，横幅永久转圈 | runSync 整体 try/catch：CancellationException 透传，其余转 `SyncUiState.Failed("sync-error")` 上横幅 |
| P1-9 | **SyncClient push 裸奔路径**：buildLocalDoc、errorBody().string()（IOException 窗口）、整个 409 合并重推全在 try 之外 | push 主体（组档→PUT→200 落基座→409 处理→递归重推）包进统一护栏；errorBody 读取单独包护 |

---

## 二、已修复（P2/P3，共 16 项）

**同步/网络面**

- **SyncMetaStore 戳回退**：recordDeletion/recordEdit 无条件覆盖既有戳——本地时钟慢于合并进来的远程戳时，删除保护回退、条目可复活。改为与既有戳取 `max`（戳只增不减语义）。
- **SyncMetaStore 余部读-改-写竞态**：remainder 单键整串 RMW，UI 写路径与 writeThrough 并发交错会整块回滚刚拉取的余部。加 `synchronized(lock)` 覆盖 remainder/setRemainder/recordDeletion/recordEdit（临界区极短）。
- **SyncClient 吞取消**：三处 `catch(e: Exception)` 补 CancellationException 透传（未来任何 scope 取消不再被转成 Failed）；toError 增补 `SerializationException → "bad-response"` 分支（代理页/HTML 劫持不再误报「离线」，与 redeem 侧同款防御）。
- **StateMerge 空档短路泄漏服务端只读字段**：`local.isEmpty() → return remote` 原样返回，`_rev/_wid/_wids` 首拉后常驻 remainder 每次推送上报。新增 `stripServerReadonly`，短路/收尾两出口同名单剥除。
- **StateMerge bodyweight 注释与实现不符**：注释引用 mergeBodyweight（t 晚者胜）而实现是 n 先手胜。**行为保持**（StateMergeTest 按现口径断言、changelog 已裁定 M2 对齐项），注释改为如实描述 + 标注改语义须同步改测试。
- **SyncScheduler 防抖注释如实化**：实况是 trailing 静默窗（每次续到重计 5s，可推迟），非「首请求起窗」fixed-window。行为保持，注释改为如实描述。
- **ServerConfig.normalize("") → "/"**：持久化坏值后 Hilt 首次 provision `Retrofit.baseUrl("/")` 必崩且每次启动复崩。空值回退 DEFAULT_BASE_URL（读侧自愈）。
- **MuheAuthApi.ping()**：suspend 里同步 `execute()` 不切线程——`withContext(Dispatchers.IO)`；redeem 兜底 catch 补取消透传。

**Viewmodel 面**

- **CheckInViewModel.checkIn**：`savingVisit` 无 try/finally，中途异常后按钮永久锁死——补 finally 复位。
- **CheckInViewModel.loadMonth 快速翻月竞态**：读「执行时」的 selectedMonth，旧月协程晚返回会错位覆盖——写回前校验 `month == uiState.selectedMonth`。
- **CoachViewModel.accept/dismissProposal**：挂起前捕获 `ready`，resolve 返回后整体覆盖 messages（900ms stub 窗口内必现抹掉用户新消息）——写回前重读 `uiState as? Ready`（sendInternal 尾部既有纪律补齐两处遗漏）。
- **CoachApi「ViewModel 零改动」声明勘误**：与事实相反（真服务是 job 队列，需 status 轮询/pending 解析/过期结算，demo 状态机真绑定后必误触发）——头注改如实，防交接误导。
- **LibraryViewModel「最后输入者胜」不闭合**：`repo.search` 无挂起点，取消不生效仍会写 uiState——赋值前 `ensureActive()`。
- **StatsViewModel**：补 SyncStateBus Ok → refresh（原注释自承的「M1 批次落点」兑现，停留在统计 Tab 期间后台 pull 即见）。
- **ProfileViewModel.monthStreak 截断于月初**：每月 1 号「我的」页显示 0/1 天而统计页显示真实 streak，两页数字矛盾——改全史口径（与 StatsCalc.dayStreak / 打卡屏一致），monthSessions 保持月窗。

**UI/数据面**

- **ScanSupport 相机初始化竞态**：覆盖层先退场、Future 回调后到 → bind 到已消失的覆盖层（相机真开着）+ executor 已 shutdown（RejectedExecutionException）——加 `disposed` 守卫，退场后回调只 unbind 不 bind。
- **ScanSupport 权限恢复**：去系统设置授权返回不經 ActivityResult，覆盖层永远停「被拒」态——ON_RESUME 重查权限回写。
- **CheckInScreen「记录到场」**：`savingVisit` 守卫补进 onRecord 回调（原按钮无闸，双击双 Toast、写库前误报成功）。
- **Stamps 默认值移除**：`ts: Long = 0` 配 REPLACE 整行覆盖 = 漏盖章静默打 0（entryTime/欠账判定/getSince 全失真）——去默认值让漏填变编译错（4 处 preview 种子补显式 `Stamp(ts = now)`）。
- **Converters.jsonToWids**：DB 读侧脏数据直接抛→整表 getAll 崩——降级 null（fail-soft，与 ExercisesRepo「让它炸」的启动期炸法区分面）。
- **GoldenTestBase.diff**：数值容差通道要求 `exp.isString == act.isString`（字符串 "80" 与数字 80.0 判等会漏掉序列化形状漂移——golden 对拍要抓的正是它）。
- **HomeScreen 跨午夜过期**：`remember(Unit)` 缓存 LocalDate 永远停在昨天——改为直接计算（纳秒级）。
- **StatsScreen maxSets**：forEachIndexed 体内每行 O(n) 重算——提到循环外。
- **CoachScreen 滚动少算一项**：tailCount 未计尾部固定 demo 标注 item，最底一行永远滚不进视口——+1。
- **strings en 两处口径漂移**：`profile_month_summary` sessions→active days（数据是日期并集）、`stats_heatmap_title` 补 heatmap 语义；中英仍 178 键 diff 零冲突。

**构建面**

- **gradle.properties** 补 `org.gradle.parallel=true` + `org.gradle.caching=true`（KSP Room+Hilt 两套，收益明显）。

---

## 三、核实后不修 / 留待批次裁定（附理由）

| 问题 | 级别 | 不修理由 |
|------|------|---------|
| bodyweight/measurements 合并未实现 mergeBodyweight 语义（n 先手胜 vs JS t 晚者胜） | P2 | 项目文档已裁定 M2 对齐项，StateMergeTest 按现口径断言；改语义须连同测试一起改并跑全量验证，本沙箱无 gradle 不宜盲改。注释已如实化，**建议 M2 批次首位落实** |
| updateCard 先查再写 TOCTOU（并发 pull 下幽灵卡复活） | P2 | 窗口毫秒级；闭合需 `db.withTransaction` 包住检查+写入+recordDeletion 校验，牵涉 Room-ktx 事务面与 MetaStore 联动，建议随 M2 对齐项一起做 |
| streak 回溯窗固定两个月跨度（>62 天封顶且随日期波动） | P3 | CheckInViewModel 注释已自知取舍；改惰性回溯需新查询面，收益低 |
| 扫码 POSSIBLE_FORMATS 全制式全幅解码（功耗/掉帧） | P3 | 「读多种、调用面把关」是 scan.js 移植的明文纪律；收敛制式属语义改动，建议实机调优批次（下一批清单 #10 ④）一起定 |
| FAB `offset(-24dp)` 凸出部分不计入 Scaffold padding，遮挡列表底部中央 | P3 | 视觉裁定项（Material 规范做法是 floatingActionButton + Center 交给框架算 padding）；▶Start 接 Workout 路由后自然消解 |
| Type.kt 只覆写 5 槽位、其余回落 M3 默认（11sp chips 偏小） | P3 | 设计基线裁定属 login-design 域，勿在本目录另立设计 |
| themes.xml windowStatusBarColor/navigationBarColor（targetSdk 35 失效且 deprecated） | P3 | enableEdgeToEdge 已接管系统栏（P1-2 修复后），残留属清理项不紧急 |
| SyncMetaStore.remainder 解析失败静默空档（理论上可推空 settings 覆盖服务端） | P3 | SP 字符串损坏概率极低；正确修法是 parse 失败阻断推送并上报，属协议面改动 |
| attendance maxStamp 扫 `ts`、entryTime 扫 `_ts` 口径不一致 | P3 | 取决于 stamps.go delLists 对 attendance 的盖章行为，需对真源核实后才能动 |
| release 无 signingConfig | P3 | 出货管线签名注入方式未定（行动项 #3 keystore 随该批次落） |
| gradle wrapper distributionUrl 走腾讯镜像 | P3 | 供应链信任面；建议 CI 侧加 `distributionSha256Sum` 校验而非改 URL |
| 二版 coroutines（1.9.0 test vs 传递 1.8.x）斜置 | P3 | 无实际故障；出现挂起行为差异时再钉版本 |
| StatsViewModel `start` 缺失时排序锚 0.0（「最新会话」可能失真） | P3 | 仅脏/导入数据触发；回退 workoutDate 排序可随统计深化批次（下一批 #12）做 |
| LibraryScreen selected 不 saveable、CheckInScreen remember 时区/语言键 | P3 | 配置变更丢态属可接受粒度（工程已有先例裁定：编辑草稿非 Saveable） |
| ExercisesRepo alias 单跳解析、1.01MB lazy 解析无主线程防线 | P3 | 数据生成器自家脚本可控；建议 MuheApp 启动预热（Application 内 IO 触发一次 load）随下批做 |
| getSince 八表无 `_ts` 索引 | P3 | 生产代码零调用（增量判定走文档级 rev）；真用时随迁移加索引 |

---

## 四、改动清单（26 文件）

**main（20 改 + 2 新）**：MuheNavHost / MainActivity / SyncScheduler / SyncClient / SyncMetaStore / StateMerge / ServerConfig / MuheAuthApi / CheckInViewModel / ProfileViewModel / StatsViewModel / LibraryViewModel / CoachViewModel / CoachApi / ProfileScreen / CoachScreen / CheckInScreen / ScanSupport / HomeScreen / StatsScreen / Stamps / Converters / AndroidManifest.xml；🆕 res/xml/backup_rules.xml + data_extraction_rules.xml
**test（1）**：GoldenTestBase
**构建/资源（3 改 + strings 1）**：proguard-rules.pro（转正）/ gradle.properties / values-en/strings.xml（2 键）

自查：23 个 Kotlin 改动文件括号/圆括号配平全过；strings 中英 178 键 diff 零冲突；StateMerge/StateMergeTest 行为语义零改动（只动注释与短路出口剥除）。

## 五、验证门（用户侧执行）

```bash
./gradlew :app:test          # 78 测试应全绿（含 golden 243 例——GoldenTestBase diff 收紧后若有
                             # fixture 字符串/数字同值异型会被抓出，那是修复生效而非回归）
./gradlew :app:assembleDebug # Manifest 新增 dataExtractionRules/fullBackupContent 引用校验
./gradlew :app:assembleRelease && 真机冒烟（配对→同步→扫码 + 首页/我的/统计/打卡全走一遍）
                             # proguard 转正后的首验门禁；重点关注 Retrofit DTO 序列化与 zxing
```
