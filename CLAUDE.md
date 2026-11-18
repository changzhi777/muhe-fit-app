[← 根 CLAUDE.md](../CLAUDE.md) · **android-app · Android 原生 APP**

> 版本: V1.6（同步接线批次：SyncScheduler 防抖调度 + SyncStateBus 四态广播 + SyncBanner 挂首页 + StateMerge 真测试）| 创建: 2026-11-17 | 最后核对: 2026-11-17 (CST) | 状态: 🟢 **骨架就绪**（⚠️ 构建未验证——本机无 Android SDK，见「未验证项」）

---

## 变更记录 (Changelog)

| 日期 | 版本 | 变更内容 |
|------|------|----------|
| 2026-11-17 | V0.1 | 🆕 目录骨架建立：仅本文档；gradle 工程未建，代码未开工 |
| 2026-11-17 | V0.2 | 📋 init-architect 第六轮增量核对：目录实况确认（仍 3 文件：CLAUDE.md / VERSION V1.0.1 / CHANGELOG）；补「易版权申报口径」节；versionName=1.0.1（VERSION 实码），包名/SDK 为规划值（gradle 工程未建，无 Manifest/gradle 可取证） |
| 2026-11-17 | V0.3 | 🆕 **工程骨架就绪**（scaffold-builder）：① Gradle 全套（settings/root/app 三层构建脚本 + version catalog = 依赖白名单 #12 落地 + wrapper properties + gradle.properties + .gitignore + proguard 占位）② 深色唯一主题（login-design.md §2.1 token 逐字落 Color.kt + darkColorScheme 全 token 派生注释 + textTertiary 扩展 + 深色启动主题防白闪）③ Manifest（INTERNET；CAMERA 注释 M2 开）+ MuHeApp/MainActivity + 导航占位（3 Tab + ▶Start 中央 FAB）④ **golden 对齐机制落地**：导出脚本 ck/scripts/export-golden-fixtures.mjs + onerm.json（4 函数块 32 例，JS 真源实算已核对）+ Onerm.kt 移植示范 + GoldenTestBase + 5 测试 ⑤ README 构建前置 ⑥ 申报口径节事实更新（见下表） |
| 2026-11-17 | V0.4 | 🆕 **Tab 导航实体化 + 三屏骨架**（屏单 v2 裁定 #5）：① MuheNavHost 实体化——底栏 4 位（首页/计划/中央凸起 ▶Start 56dp 圆 FAB offset -24dp/我的），选中 Filled 未选中 Outlined；中央 Start 不进路由直接动作回调（占位 Toast），路由 home/plan/profile 三条 ② screens/ 包 3 文件（HomeScreen §3.2 全卡位顺序 / PlanScreen 周行×7 / ProfileScreen §3.3 分组列表）+ 各自 @Preview（MuHeTheme 深色包裹）③ strings 42 键中英对齐（values/ + values-en/，裁定 #8；含 week_day_labels 数组），屏内文案零硬编码全 stringResource ④ 图标只用 material-icons-core（m3 传递依赖，白名单内；CalendarMonth 属 extended 不引入，以 DateRange 承载日历语义）⑤ Kotlin 源 9→12 文件。新增 3 文件 / 修改 3 文件 |
| 2026-11-17 | V0.5 | 🆕 **Room data 层八表建齐**（裁定 #17：八类 stamps 一次建齐）：① entity/ 八 Entity + Stamp 公共盖章类（@Embedded，列名 `_ts/_wid/_wids` 与 stamps.go 一字不差）+ raw 整条目 JSON 列（透传重建源，动态字段零丢失）② dao/ BaseDao 泛型基座（upsertAll/upsert/update REPLACE 整行覆盖）+ 八 Dao（getAll 全量 / getSince 按 `_ts` 本地增量 / getById 或 getDay / deleteById+clearAll）③ converter/Converters.kt（`_wids` List\<String\> ↔ JSON，kotlinx-serialization；时间戳 Long 直存免 Instant 往返）④ MuheDatabase.kt（@Database 八表 version=1 exportSchema + Migration 占位注释：客户端 Room 版本独立计数，服务端 0012 顺延是 PG 序列两不相干）⑤ app/build.gradle.kts 补 `ksp { arg("room.schemaLocation", ...) }`。同步协议逻辑本身不在本批（M1 网络层）；文档级 `_rev/deleted.*/edited.*` 存储载体由 M1 裁定。**编译验证待用户侧**（本机无 SDK） |
| 2026-11-17 | V0.6 | 🆕 **训练域移植扩面（下一批清单 #4 完成项）**：golden 对齐纪律下的 progression/backoff/finish-workout 全量移植——① fixture 扩块：`ck/scripts/export-golden-fixtures.mjs` 新增 progression.json（18 函数块 **139 例**：六策略全谱 nextPrescription ×59 + readSession 诚实读法 ×15 + stallCount/sessionsFor/applyPrescription + Epley deload 选择器与步进助手）/ backoff.json（5 块 **28 例**）/ finish-workout.json（2 块 **32 例**：sessionEnd 收尾语义 ×11 + buildCompletedWorkout 落盘白名单 ×21），用例值全部取自 JS 测试文件并由脚本对 JS 真源实算，**117 组断言值逐例核对全过**；fixture 格式扩展 `label`（叙事用例强制）与 `catalog` 段（最小目录数据，规则/数据分离——JS EXIDX 不搬，分类规则进 Kotlin Ex.kt）② Kotlin 移植 8 文件（lib/ 下 Js/Ex/WorkoutModel/History/Dumbbells/Backoff/Progression/FinishWorkout，逐函数注 JS 行号与来源文件；宿主模型 = JsonObject 承载 JS 动态对象语义，纯数值闭包用 data class）③ 测试 3 文件（Progression/Backoff/FinishWorkoutGoldenTest，走 GoldenTestBase）④ GoldenTestBase 修 classLoader 可空调用警告 + 新增 assertGoldenObjects 结构深比对（数值同容差、键集合两侧一致）⑤ docs/golden-testing.md V1.1（格式扩展 + 覆盖表 + 边界补记）。**编译验证待用户侧 `:app:test`**（沙箱无 gradle，静态自查已过；JS↔fixture 侧已实跑闭环） |
| 2026-11-17 | V0.7 | 🩹 **triple 背书勘误 + fixture 扩至 243 例**：V0.6 误记「triple 无独立 JS 测试」——实查存在专门的 `ck/frontend/src/lib/progression.triple.test.js`（全周期 3×8→…→5×12→+2.5、setsMax 属计划、第四组独爬、超额计数、三滞 Epley deload 57.5、辅助器械减助力、每组目标读判/落组/单侧拆分全覆盖）。① progression.json 139 → **151 例**（+12，全部该测试文件直接背书：tripleSetsOf 四态 :39-42、plannedOf/planChanged 的 setsMax :46-48、第四组独爬 :66、超额加组 :85、setsMax 重开 :160-162、辅助器械 :190、readSession 每组目标 :165-171、applyPrescription :173-184；最早按源注释构造的 4 例保留）② 核对扩至 **130 组全过 0 不符**（新增 13 组按 triple.test.js 断言对拍；其中 1 组为我核对期望写窄——JS plannedOf 的 weight 回显语义，fixture 无误）③ golden-testing.md V1.2 勘误 + 覆盖表更新。Kotlin 侧无改动（151 例全走已移植函数）|
| 2026-11-17 | V0.8 | 🆕 **登录屏落地**（M1 收尾清单 #3，login-design.md V0.1 逐区 1:1）：① `screens/LoginScreen.kt`——品牌区 weight(1f) / 认证区 / 页脚折叠服务器栏三段式，4px 网格间距逐条落（水平 24 / CTA↔次按钮 12 / 折叠行 44dp 等）；六组件按 §四规格（主 CTA 56dp pill primary / 次按钮灰填充 56dp 点击展开配对码输入 / ghost 先逛逛 textTertiary 无 primary / OutlinedTextField 等宽 tnum+自动大写过滤 A-Z0-9+8 位计数暗示（**不做分格**，规范已否决）/ 错误 banner errorContainer 就近 / 服务器状态点三态 crossfade）② 六状态齐备（§五）：加载（确认按钮内 20dp 圈+禁用防双击）/ 配对码错误（error 边框 150ms crossfade+框下文案，**无 shake**，清空保焦点）/ 服务器不可达（就近 banner+重试+状态点红）/ 扫码成功过场（scrim 60% + 对勾圆徽 Animatable 0.6→1.0 spring + 600ms 后导航 + CONFIRM 触觉）/ 相机无权限（inline+去授权跳系统设置，触发路径随 M2）/ 访客进入（回调直达）③ 动效全标准曲线 ≤360ms（§六）：进入三段 fadeIn+slideInVertically 240ms（延迟 60/120ms）、主/次按钮按压 scale 0.98 spring、展开 animateContentSize 200ms ④ 🆕 `viewmodel/` 包：**PairViewModel**（@HiltViewModel 六态状态机 + 码 clean 与服务端同构：大写→过滤 A-Z0-9→截 8 位）+ **AuthApi 接口占位**（redeem/ping 签名 + RedeemResult 三态 + StubAuthApi 占位实现 + LoginApiModule @Binds——**刻意不 import net/ 包**（并行属地），M1 网络层批次由 NetModule 的 MuheApi 实现本接口后删 stub 绑定，ViewModel 零改动）⑤ strings 双语 **+25 login_ 键**（总 68 键中英 diff 对齐核对），零硬编码；品牌名复用 app_name ⑥ 导航：Routes.LOGIN 全屏二级路由（bottomBar 条件隐藏，Scaffold padding 自动回收）+ ProfileScreen「设备配对登录」→ nav(login)。占位项：扫码 CameraX（M2，当前 startScan 演示成功过场 + Toast）/ qr-code 图标（icons-extended 白名单外）/ 真网络对接 / base URL 持久化（DataStore 不在依赖白名单，归网络层批次裁定）。**编译验证待用户侧**（同 V0.6 口径）|
| 2026-11-17 | V0.9 | 🆕 **网络层 + 同步协议客户端半落地**（M1 收尾清单 #2）：① 🆕 `net/` 包——MuheApi（Retrofit 接口：pair/create·redeem、data GET/PUT/GET rev、config GET，409 经 `Response<JsonObject>` 透明暴露，DTO 与契约 operationId 一一对应）/ AuthInterceptor（Bearer 注入，无 token 裸发）/ ServerConfig（baseUrl 可变持久化 = SharedPreferences 裁定，默认 `https://mufit.qingmulife.com`）/ NetModule（Hilt：宽松 Json + OkHttpClient（超时对齐前端 60s 口径）+ Retrofit kotlinx-serialization converter）/ **MuheAuthApi**（V0.8 交接点接管：AuthApi 真实现 redeem→pair/redeem+token 落盘 TokenStore、ping→/api/config 10s 探活；StubAuthApi/LoginApiModule 占位按交接注记删除，ViewModel 零改动）② 🆕 `sync/` 包——StateDoc（整档 JsonObject 模型：拆档/合档/盖章字段读取/highestStamp）/ StateMerge（mergeStates M1 子集：八类条目并集 `_ts` 晚者胜 + deleted/edited 只增不减（stamps.go MergeDeletions/MergeEdits 移植）+ edited 按戳取值；M2 对齐项注释在案）/ DocEntityMap（文档条目↔Room 八实体映射，raw 透传）/ SyncMetaStore（**文档级 `_rev/_wid/deleted.*/edited.*/余部` 载体裁定 = SharedPreferences**，V0.5 预留落点）+ 本地改动盖章助手 / SyncClient（协议编排：rev 先比再拉、整档 PUT 条件写 baseRev/baseWid + stamped:true、409 合并重推 ≤2 次、访客态跳过）③ 🆕 `di/DatabaseModule`（Room DB Hilt 提供，MuHeApp 预留落点）。data/ 包零改动、strings.xml 未动；LoginScreen 仅编译适配：@Preview 的 StubAuthApi() 换文件内私有 PreviewAuthApi（stub 已删，语义不变）。**编译验证待用户侧 `:app:test`**（同 V0.6 口径）|
| 2026-11-17 | V1.0 | 🆕 **M2 打卡屏落地**（android-app.md §3.4，B 层裁定 attendance 第八类首版消费；CheckIn.jsx 交互语义移植）：① gradle 补 **CameraX 1.4.2** 四件（core/camera2/lifecycle/view）+ compose-foundation 显式声明（HorizontalPager 载体，BOM 统一版本）——**白名单外过审注记**：打卡扫码系 M2 清单首项（§3.4），用户批准 M2 批次启动即隐含放行，注记落 libs.versions.toml `androidxCamera`；Manifest CAMERA 权限启用 ② 🆕 `screens/CheckInScreen.kt`——会员码横滑卡轨（HorizontalPager：QR 全屏亮码 = zxing core BitMatrix→ImageBitmap 自绘黑码白底 + FilterQuality.None 最近邻保码格锐利 + 卡名/码值回显 + ◀▶ 重排 + 尾页「添加卡片」位 + 点位指示 + lastGymCardId 重开记忆）、「记录到场」按钮落 attendance（gymCardId+ts+note 可空，防双击）、打卡历史月度列表 + 本月 N 次·连续 N 天（getRange 本月窗+前月回溯 streak）、空态引导、全屏扫码覆盖层（CameraX Preview+ImageAnalysis KEEP_ONLY_LATEST + zxing MultiFormatReader YUV 解码含 rotationDegrees 转正修正；**非 QR 按 canRenderFmt 明确拒绝——禁手输纪律照搬**）；权限走 `ActivityResultContracts.RequestPermission` 原生 API（Accompanist 白名单外不用），被拒态引导系统设置；Screen+Content 拆分保 @Preview（VM 依赖 Room/SharedPreferences 无法预览构造）③ 🆕 `lib/CheckIn.kt` 纯函数移植（normalizeFmt/canRenderFmt ← qr.js + moveGymCard ← CheckIn.jsx，JS 行号在注）+ 🆕 `test/lib/CheckInTest.kt`（qr.test.js 三 describe + CheckIn.reorder.test.jsx 七例逐条对拍，**13 测试方法**；fixture 化留打卡域扩块）④ 🆕 `data/CheckInPrefs.kt`（lastGymCardId 记忆，SharedPreferences/ServerConfig 同款；JS 侧该键随档同步、Android 首版本机——差异注记在文件头）⑤ 🆕 `viewmodel/CheckInViewModel.kt`——@HiltViewModel 卡轨/历史两段状态：addCard（本地 `_ts` 盖章 + raw 整条目 JSON 透传重建源，uid 沿 format.js:140 语义）/moveCard（**顺序落库机制：REPLACE 重插保 rowid 序 = 列表序，push 组档同序——与 JS「gymCards 数组序即卡轨序」协议语义一致**；条目 _ts 不动，文档级 stampDocChange 记欠账）/checkIn（attendance 落库 + 刷新统计）⑥ DatabaseModule 按表拆 gymCardDao/attendanceDao @Provides（既有注记落点兑现）⑦ NavHost：Routes.CHECKIN 二级全屏路由（底栏隐藏同 LOGIN/COACH 口径）+ 首页金刚区「打卡」接线（HomeScreen +onCheckInClick 参数）⑧ strings 双语 **+22 checkin_ 键**（中英 diff 对齐核对，零硬编码）。**编译验证待用户侧**（同 V0.6 口径；CameraX/HorizontalPager/权限流均静态自查）|
| 2026-11-17 | V1.1 | 🆕 **AI Coach 对话屏骨架 + 我的页数据充实**（M2 客户端批次；Go 原生 coach 服务端 = G3 裁定的 M2 服务端批次）：① 🆕 `screens/CoachScreen.kt`——对话屏（Screen+Body 两层拆分，@Preview 不经 Hilt 同 LoginScreen 纪律）：消息线程 LazyColumn（用户/教练气泡 + 提案卡，kind 分类 chips = 契约 enum create/review/debrief → 计划/建议/训练复盘）+ typing 三点气泡（job 运行中，真服务分钟级轮询口径文案）+ composer（快捷「复盘我的训练」chip + 输入框 placeholder 随提案/thinking 三态分流）+ 未配置态引导卡（TokenStore 无 token → 「去配对登录」）② 🆕 `viewmodel/CoachApi.kt`——CoachApi 接口四方法对齐契约 operationId（getCoachStatus / postCoachPlan / postCoachReview / postCoachResolve；202 CoachJobQueued `{job:{id}}` DTO + status/proposal 宽承载 JsonElement 透传）。**契约语义澄清：coach 是异步 job 队列，无同步 chat/history 端点**——发起=POST 排队、结果=GET status 轮询、决策=pending/resolve；线程历史=客户端本地（S.coach.chat 同构，本批内存态不做持久化）。StubCoachApi demo 两轮（首问→review 提案卡、再问→nochange，resolve 真走链路：accepted 以索引占位/dismissed 全弃）+ CoachApiModule @Binds（ViewModelComponent）——**不动 net/ 包**（并行属地，MuheApi 无 coach 方法），真网络实现随服务端批次照 MuheAuthApi 接管 AuthApi 先例交接，ViewModel 零改动 ③ 🆕 `viewmodel/CoachViewModel.kt`——@HiltViewModel 状态机（NotConfigured / Ready(messages, pending, thinking)）④ 🆕 `viewmodel/ProfileViewModel.kt`——登录态（TokenStore）+ Room 计数聚合：workoutCount/measurementCount 真数、本月训练 = workouts(d)∪attendance(ts) 日期并集 + 连续天数纯函数（今天未练不断链；零改动 data/ 包，走既有 getAll/getRange 内存聚合，本地数据量级小 KISS 足够）⑤ ProfileScreen 数据充实——账号卡真登录态（paired/访客 + 本月聚合行 + 退出配对按钮）、训练数据组行副标题（训练历史/身体围度 Room 真数；进展照片/结构平衡无表保持占位）、AI 教练行 → nav(coach)；Screen+Body 数据参数化拆分保 preview ⑥ NavHost——Routes.COACH 全屏二级路由（底栏隐藏同 LOGIN 口径）⑦ strings 双语 **+37 键**（coach_ ×29 + profile_ ×8），零硬编码；**不动 checkin_ 前缀键与 data/net 包**（并行属地）。**编译验证待用户侧**（同 V0.6 口径）|
| 2026-11-17 | V1.2 | 🆕 **打卡屏二批：卡编辑/删除 + 登录屏真扫码 + 扫码组件共享化**（下一批 #10 ①②③ 完成项；CheckIn.jsx CardSheet/CardFace 语义）：① 🆕 `screens/ScanSupport.kt`——扫码组件面自 CheckInScreen 抽出共享（ScanOverlay 参数化 title/hint + onDenied 回调、CameraPreview、BarcodeAnalyzer，internal 可见性）：登录「扫码配对」与打卡「扫码添加」一套组件；通用键升 **scan_** 前缀（scan_close/scan_camera_denied/scan_camera_grant——V1.0 自有 checkin_ 键的共享化演进，camera_denied 文案去「添加」化）② 卡编辑/删除——CardPage 卡头 ✎/🗑（core 图标 Edit/Delete）+ EditCardPanel（重扫换码 + 卡名输入 + QR 预览 + 保存；**码值无手输面，唯一来源重扫**）+ ConfirmPanel（error 填充确认，CheckIn.jsx confirmSheet danger 语义）；CheckInViewModel 新增 updateCard（先查再写防他端删除的幽灵写，条目整体覆盖 + 新 `_ts` = 协议「同键 _ts 晚者胜」编辑合并；result(false) → 「卡片已在其他设备被删除」）/ deleteCard（**先 SyncMetaStore.recordDeletion("gymCards", id, entryTs, now) 记文档级删除戳再 deleteById——Dao 注释「同步层负责」的接线点兑现**；lastGymCardId 落在被删卡上时回第一张）；扫码结果按编辑面板开关分流（编辑态回填重扫草稿，否则扫码添加）③ **登录屏真扫码接线**（V0.8 预留项完成）——LoginScreen 撤 onScanRequest 参数与 Toast demo，主 CTA 拉起共享 ScanOverlay；识别结果经 **PairViewModel.onScannedCode**（clean 与手输同构，满 8 位直接 redeem，非配对码 → CodeInvalid 复用既有错误态）；权限被拒 onCameraDenied → 认证区 inline 行（LoginUiState.CameraDenied 触发路径生效）；PairViewModel.startScan demo 撤除、onCodeInput 复位条件补 CameraDenied；login_toast_scan_m2 键撤除 + 新 login_scan_hint ④ QR 位图 **LruCache（按字节 ~12MB，键 = 值@像素尺寸）**——pager 来回滑动免重复编码 ⑤ strings 双语 **+13 键**（login_scan_hint ×1 + checkin 编辑删除 ×12；撤 login_toast_scan_m2；3 键升 scan_ 前缀，总 **138 键**中英 diff 对齐）。**编译验证待用户侧**（同 V0.6 口径）|
| 2026-11-17 | V1.3 | 🆕 **打卡历史月切换 + 前台恢复刷新**（打卡屏三批，屏面级收尾）：① 历史区月切换——设计「打卡历史列表」的实缺口补齐（原先只显本月）：MonthSwitcher（‹ yyyy/MM ›，下一月在本月处禁用——未来无数据）+ selectedMonth 状态与 shiftMonth(±1)（钳制上限 = 本月）；CheckInUiState 拆分口径：`monthEntries`（选中月列表，单月窗 getRange）与 `monthCount/streakDays`（**统计恒锚本月**，streak 窗口仍回溯一月跨月延续）两不牵动；loadHistory 拆 loadStats/loadMonth ② 前台恢复刷新——CheckInScreen 挂 LifecycleEventObserver（DisposableEffect + LocalLifecycleOwner，ON_RESUME → reload）：同步挂接（下一批 #8）拉下来的新卡/新记录回到本屏即见；addObserver 落在已 RESUMED 生命周期会补发一次 ON_RESUME（与 init 重叠，两次本地查询可忽略，注释在案）③ 空态文案随选中月泛化（「该月还没有打卡记录」）+ strings 双语 +2 键（checkin_prev_month/checkin_next_month，总 **140 键** diff 对齐）。零新依赖（LifecycleEventObserver 走 lifecycle-common 传递）；纯屏面/VM 改动，不触 sync/net 包。**编译验证待用户侧**（同 V0.6 口径）|
| 2026-11-17 | V1.4 | 🆕 **动作库数据源工程 + LibraryScreen 主体**（动作库融合「我的」二级页——本批只做屏体与数据，接线归收口）：① 🆕 导出脚本 `ck/scripts/export-exercises-assets.mjs`（零依赖，镜像 catalogue build.mjs 的 EXDB/EXALIAS 语义）：读 catalogue/exercises/*.json + i18n/zh.json → `app/src/main/assets/exercises/catalog.json`——**5,632 条实动作**（922 条 variantOf 占位不进目录只落 alias 表）+ alias 922，单行压缩 JSON **1.01MB**（<2MB 目标），字段 {id, name（zh 覆盖全 5,632 条）, nameEn, muscles（target 首位+协同去重）, equipment?, category?}，说明/指令/媒体 M2+ 再进精简包；默认序 = web 库同款 name 排序（跨数据集刷新不跳变），幂等可重跑（数据面确定性输出，仅 generatedAt/sourceCommit 随运行变）② 🆕 `data/ExercisesRepo.kt`——assets 一次性解析进内存（lazy 首访触发，调用方在协程默认派发取数勿主线程冷触发），查询面 all / byId（变体 id 经 alias 解析正身，绝不落「未知动作」）/ search（中英 contains 大小写不敏感）/ byMuscle / byEquipment；IOException 回落空目录屏面呈空态；Hilt = @Inject 构造注入即 SingletonComponent 单例，无需 @Module；不进 Room（D2 静态打包裁定）③ 🆕 `screens/LibraryScreen.kt`——顶部常驻搜索框 + 结果 LazyColumn（中文名+英文名+肌群/器材 chips；词表中文映射肌群 50/器材 33/分类 12 全收录，未收录词原样回显）+ 条目详情 ModalBottomSheet（双语名+分类/器材/肌群全量 chips；说明文字 M2+ 进精简包，占位注释在案）+ loading/空目录/无结果三态 + Screen/Body 拆分保 @Preview（深色）④ 🆕 `viewmodel/LibraryViewModel.kt`——@HiltViewModel query/results/loaded 三态，即时过滤（5,632 条 ms 级）+ 在途检索 Job 取消保序=最后输入者胜 ⑤ **本批 strings.xml 冻结**（收口统一接）：屏内文案暂以文件常量承载，LibraryScreen.kt 头注附 library_* 键名清单（7 键）供收口换 stringResource。**接线待收口**：MuheNavHost 加 Routes.LIBRARY 二级路由 + ProfileScreen 入口行/金刚区 + strings library_ 键双语落位。**编译验证待用户侧**（同 V0.6 口径）|
| 2026-11-17 | V1.5 | 🆕 **统计 Tab 回归 + StatsScreen 真数据版**（**裁定 #5 用户实机反馈修订**（2026-11-17）：统计回归独立 Tab，底栏 4 → **5 位**，首页融合撤销；decisions.md 变更记录同步）：① MuheNavHost——底栏改 5 位（首页/计划/**▶Start 中央凸起保持**/统计/我的）：LEFT_TABS(2) + 中央 FAB Box(weight) + RIGHT_TABS(2)，两侧共用 🆕 RowScope.TabItem 组件（V0.4 内联 NavigationBarItem 的 DRY 化）+ goTab 切换 lambda 抽取；Routes.STATS 路由 + composable 接线（空态按钮回首页走 goTab）；统计图标 = core 白名单 List（别名 ListIcon 避让 kotlin.collections.List，BarChart 属 extended 不引入）② 🆕 `screens/StatsScreen.kt`——Stats.jsx 结构移植真数据版，Screen+Body 拆分保 @Preview：汇总瓦片四枚（总训练/本月/连续天数/30 天体重变化）→ **训练热图**（近 12 周，列=周×行=日，当日完成组数按 Heatmap.jsx 四分位阈值着色 l0-l4，Grid 自绘零依赖）→ **体重完整趋势**（BodyweightDao 全量 → Canvas 折线 + 末点圆，LineChart.jsx 数据形状/y 轴 12% 留白语义，x 轴首版按序均分简化注记在案）→ **周训练量**（本周大数 + 近四周总组数比例条，肌群细分待 M2+ 注记在 StatsCalc）→ **重点动作 Est.1RM**（每动作最新会话最优组 → epley 估算，降序取 8）→ **肌图占位卡**（BodyMap 三视图「M2 后段移植」标注）→ **空态**（无训练记录引导去首页开练）③ 🆕 `lib/StatsCalc.kt`（main 侧第二文件）——统计聚合纯函数：epley1RM（onerm.js estimate1RM epley 路径子集，test 侧 Onerm.kt 在 test source set 不可依赖，数值由 StatsCalcTest 对拍 golden onerm.json 闭环）/ setsDone（doneUnits 单侧 ×2 + workoutVolume 热身排除纪律）/ dailySetTally / heatThresholds·heatLevel·heatGrid（Heatmap.jsx q(.25/.5/.75) 语义，窗口化适配注记）/ mondayOf·weeklySetTotals / dayStreak（ProfileViewModel 同口径）/ monthWorkoutCount / bwDelta30 / fmtNum·fmtSigned / latestEstimates ④ 🆕 `viewmodel/StatsViewModel.kt`——@HiltViewModel，Room 只读（既有 getAll，不建新 Dao）raw 解析聚合进 StatsUiState（含 @Preview 样例态）⑤ 🆕 `test/lib/StatsCalcTest.kt`——**18 测试方法**：epley 4 例直取 golden onerm.json + 边界 5 例；组数/热图/周量/streak/bwDelta 为 Android 侧新增聚合（无 JS fixture 对拍，期望值按注记语义手工推演，文件头注记）⑥ HomeScreen 统计摘要区整块撤除（SectionLabel/PendingCard 保留——streak/金刚区仍用），相关 home_section_stats/home_card_* 四键按「不碰现有键」纪律保留不删（注释标注已无消费方）⑦ strings 双语 **+28 键**（stats_ ×27 + nav_stats；总 **168 键**中英 diff 对齐核对，checkin_/coach_/profile_/scan_ 前缀零触碰）。**编译验证待用户侧**（同 V0.6 口径）|
| 2026-11-17 | V1.6 | 🆕 **同步接线批次**（下一批 #8，net/sync 属地收尾；SyncClient 协议逻辑零改动，只挂触发器与状态广播）：① 🆕 `sync/SyncScheduler.kt`（@Singleton）——**防抖调度面**：`requestSync(reason)` 单消费者 Channel 循环 + `withTimeoutOrNull` 起窗（**5s 窗口内多次请求合一，原因标签 latest-wins**；执行中到达的请求排队下一轮，DROP_OLDEST 旧因无价值）；`syncNow(reason)` 旁路窗口立即执行；`Mutex` 两路互斥防并发写档；访客态（无 token）→ bus 停 Idle 从不触网（SyncClient 纪律照搬）② 🆕 `sync/SyncStateBus.kt`——**四态广播**（sealed SyncUiState：Idle 访客/未同步 / Syncing / Ok / Failed(error)）MutableStateFlow 单写（internal publish）多读，Failed 随行 SyncError（status null=离线 / 401 待重配对，分流数据已备）③ 🆕 `screens/SyncBanner.kt`——**状态横幅**（语义对齐 PWA SyncBanner.jsx/ServerSync connectionView 分档收敛）：Syncing 转圈小字（14dp CircularProgressIndicator）/ Failed 红字 + 重试 TextButton（点击 `requestSync("banner-retry")`）/ Ok·Idle 静默不显；Screen+Content 拆分保双 @Preview；挂首页列顶（HomeScreen 🆕 header 槽位——预览零 VM 可构造，NavHost 注入 `{ SyncBanner() }`）④ **触发源三路接线**：① 登录态变化——PairViewModel 🆕 注入 Scheduler，redeem Success → `syncNow("pair-redeemed")` 立即拉取（新会话 baseRev 未立 → pullIfChanged 首拉建基座，等效 PWA boot pull）② 数据变更——CheckInViewModel 🆕 注入 Scheduler，**五写路径**（addCard/moveCard/updateCard/deleteCard/checkIn）落库后 `requestSync("data-change")` ③ 前台恢复——MainActivity 🆕 `LifecycleEventObserver`（零新依赖）ON_RESUME → `requestSync("foreground")`，首次进入 = 启动引导同步（boot pull 口径），权限弹窗收起同样触发由 5s 窗口吸收 ⑤ **server→local 闭环**——CheckInViewModel 订阅 bus，Ok 即 `reload()`（V1.3 注释「同步挂接后拉下来的新卡/新记录即见」的兑现；每 Ok 一次本地查询 ms 级）⑥ 🆕 `test/sync/StateMergeTest.kt`——**15 用例真测试**（V0.9 特列④「静态对拍未跑」转正）：八类并集（n 先手/o 追加）×3（含 bodyweight 同键 n 先手口径如实断言，t 晚者胜差异注明 M2 对齐项）/ deleted·edited 只增不减 ×5（含 laterDel 平局负值加回胜、删除戳落地、删后编辑防复活）/ 余部透传 ×2（新副本决定无戳字段、_ts=max + 服务端只读戳剥除）/ 补充面 ×5（OWN_MERGE 跳过、favEx 并集、STAMPED_MAPS 键级、空档短路 ×2）⑦ strings 双语 **+3 sync_ 键**（sync_syncing/sync_failed/sync_retry；总 **178 键**实点 = V1.5 记载 168 + library_ 7 收口落位 + 本批 3，中英 diff 对齐零冲突）。**编译验证待用户侧**（同 V0.6 口径）。**M1 遗留**：30s 前台轮询（useStore poll 口径）、401 → 重配对分流（横幅暂统一 failed 档）|
| 2026-11-18 | V1.7 | 🩹 **全量代码审查修复批次**（四路并行深审 → 逐条核实 → 27 项直修，报告 = [docs/code-review-2026-11-18.md](./docs/code-review-2026-11-18.md)；**⚠️ 全部静态修改，待 `:app:test` + `assembleDebug` 复验**）：**P0 ×1**——MuheNavHost 组合期 Toast 调试探针删除（每次重组必弹）+ insets 清零撤除（恢复 Scaffold 默认 systemBars，修「内容画进状态栏」）+ enableEdgeToEdge 固定深色系统栏样式。**同步层 P1/P2**——SyncScheduler runSync 异常护栏（原单消费者循环可被 SyncClient 未封堵路径杀死→同步永久失效+横幅永转圈）/ SyncClient push 主体（buildLocalDoc·errorBody·409 合并重推）包护栏 + 三处 catch 补 CancellationException 透传 + toError 增 bad-response 档 / StateMerge 空档短路剥服务端只读戳（`_rev/_wid/_wids` 不再常驻 remainder 上报）/ SyncMetaStore recordDeletion·recordEdit 与既有戳取 max（防时钟回退致删除保护失效）+ 余部 RMW 加 synchronized / ServerConfig.normalize 空值回退默认（防 "/" 崩 Retrofit provision）/ MuheAuthApi.ping 落 Dispatchers.IO。**UI/VM P1/P2**——CheckInScreen Pager 对位 key 改整表（修 ◀▶ 重排页面错位）+ checkIn 走 try/finally（savingVisit 防死锁）+ updateCard 保 rowid 序落库（修编辑卡跳卡轨末位）；ProfileViewModel 接 syncBus Ok→refresh + ProfileScreen ON_RESUME→refresh（修配对成功返回仍显访客）+ monthStreak 改全史口径（修与统计页数字矛盾）；CoachViewModel resolve 写回重读（修挂起期抹消息）+ CoachApi「VM 零改动」声明勘误；StatsViewModel 接 syncBus；LibraryViewModel ensureActive；ScanSupport disposed 守卫（修相机 bind 到已退场覆盖层）+ ON_RESUME 权限重查（修设置授权返回不恢复）。**数据/构建面**——Stamp.ts 去默认值（漏盖章变编译错）/ Converters.jsonToWids fail-soft / GoldenTestBase diff 数值通道要求同型（字符串"80"≠数字80.0）/ **proguard-rules.pro 从占位转正**（release 已 minify+shrink）/ **Manifest 备份规则**（🆕 res/xml/backup_rules.xml + data_extraction_rules.xml，muhe_auth token 排除出云备份与 D2D）/ gradle.properties 补 parallel+caching / strings en 2 键口径修正（178 键中英 diff 仍零冲突）。**勘误**：构建实况已跑通（app-debug.apk 14MB + 78 测试×2 变体全绿）——V1.6 前历版「待首建验证」特列已被实机构建背书，本文件「未验证项」节相应降级；遗留 M2 清单见审查报告第三节（bodyweight mergeBodyweight 真实现、updateCard TOCTOU 事务闭合、签名配置、release 真机冒烟门禁） |

## 定位

**沐禾健身 APP 客户端**（Android 原生）：

- 技术栈：Kotlin 2.x + Jetpack Compose + Material 3 全局深色 + Room + Hilt + Retrofit/OkHttp
- SDK：minSdk 26 / targetSdk 35
- 包名：`com.muhe.fit.app`

## 易版权申报口径（V0.3 工程建立后更新）

| 项 | 值 | 取证来源 | 性质 |
|----|----|----------|------|
| 应用显示名 | 沐禾健身 | `app/src/main/res/values/strings.xml`（values-en/ = Muhe Fit） | 实码值 |
| 包名 applicationId | `com.muhe.fit.app` | `app/build.gradle.kts` + Manifest | 实码值（未实机构建验证） |
| versionName | 2.0.1 | `app/build.gradle.kts` 构建期读本目录 `VERSION`（内容 `V2.0.1`） | 实码值 |
| versionCode | 1 | `app/build.gradle.kts` | 实码值 |
| minSdk / targetSdk | 26 / 35 | `app/build.gradle.kts` | 实码值（未实机构建验证） |
| 入口 Activity | `.MainActivity`（exported，单 Activity Compose 架构） | `app/src/main/AndroidManifest.xml` | 实码值 |
| 代码规模 | Gradle 全套 + Kotlin 源 79 文件（V1.6 实点：main 62 = 主题 3 + 入口 2 + 导航 1 + 屏包 10（🆕 V1.6 SyncBanner；ScanSupport 计入屏包）+ viewmodel 8 + net 5 + sync 7（🆕 V1.6 SyncScheduler + SyncStateBus）+ di 1 + data 层 23（递归计）+ lib/ 2；test 17 = golden 基建 5 + 训练域移植 9 + CheckInTest 1 + StatsCalcTest 1 + 🆕 V1.6 StateMergeTest 1）+ assets 动作目录 catalog.json（5,632 条 + alias 922，1.01MB，V1.4）+ golden fixture 243 例（onerm 32 / progression 151 / backoff 28 / finish-workout 32）+ StateMerge 合并语义测试 15 例（V1.6，手工 JSON fixture）+ strings 178 键中英对齐（🆕 V1.6 +3 sync_（sync_syncing/sync_failed/sync_retry）；V1.5 记载 168 → 实点 178 = library_ 7 收口落位 + sync_ 3，逐轮 diff 核对） | 目录枚举 | 实况（构建未跑） |

> 申报提示：Manifest/gradle 取证已可提供，但均为**未实机构建**的静态代码；正式申报前建议先走通 `assembleDebug` 出首个 APK。

## 权威文档（先读再动工）

| 文档 | 路径 |
|------|------|
| 设计文档（V0.2） | [../ck/docs/app-design/android-app.md](../ck/docs/app-design/android-app.md) |
| 登录设计（色彩 token 真源） | [../ck/docs/app-design/login-design.md](../ck/docs/app-design/login-design.md) |
| 技术裁定 #1-31 | [../ck/docs/app-design/decisions.md](../ck/docs/app-design/decisions.md) |
| **golden 对齐机制** | [docs/golden-testing.md](./docs/golden-testing.md) |
| 构建前置 | [README.md](./README.md) |

## 工程结构（V0.3 实建）

```
android-app/
├── VERSION / CHANGELOG.md / CLAUDE.md / README.md / .gitignore
├── settings.gradle.kts · build.gradle.kts · gradle.properties
├── gradle/libs.versions.toml            # 依赖白名单（裁定 #12）落地清单
├── gradle/wrapper/gradle-wrapper.properties  # ⚠️ wrapper jar 用户侧生成
├── docs/golden-testing.md               # JS↔Kotlin golden 机制文档
└── app/
    ├── build.gradle.kts                 # versionName 构建期读 ../VERSION；compileSdk/targetSdk 35
    ├── proguard-rules.pro               # 占位（release 混淆随首个 release 构补）
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml      # INTERNET + CAMERA 权限（V1.0 打卡扫码启用）
        │   ├── res/values/              # 中文默认 strings + 深色启动主题（values-en/ 英文，裁定 #8）
        │   ├── 🆕 assets/exercises/catalog.json # V1.4 动作目录精简包（5,632 条 + alias 922，1.01MB；生成物勿手改，再生 = node ck/scripts/export-exercises-assets.mjs）
        │   └── java/com/muhe/fit/app/
        │       ├── MuHeApp.kt           # @HiltAndroidApp
        │       ├── MainActivity.kt      # edge-to-edge + MuHeTheme
        │       ├── navigation/MuheNavHost.kt  # 🆕 V1.5 底栏 5 位（首页/计划/中央凸起 ▶Start/统计/我的——裁定 #5 修订）+ home/plan/stats/profile 路由 + login/coach/checkin（全屏二级，底栏隐藏）；两侧 Tab 共用 RowScope.TabItem
        │       ├── screens/             # 屏包（各带 @Preview）+ ScanSupport.kt（扫码共享组件面：ScanOverlay 参数化 title/hint + CameraPreview + BarcodeAnalyzer，internal；打卡/登录两屏共用）+ 🆕 V1.4 LibraryScreen.kt
        │       │   ├── HomeScreen.kt    # §3.2 卡位（🆕 V1.5 统计摘要区撤除——职责移交统计 Tab）：问候/今日训练卡(周历+Start)/streak/金刚区（打卡位已接 checkin 路由）+ 🆕 V1.6 header 槽位（SyncBanner 挂列顶，默认空保预览）
        │       │   ├── PlanScreen.kt    # 周计划骨架：周一~日行 ×7 + 安排训练占位
        │       │   ├── ProfileScreen.kt # §3.3 聚合页（🆕 V1.1 数据充实）：账号卡(登录态+本月训练聚合) + 行副标题读 Room 真数 + AI 教练行→coach
        │       │   ├── 🆕 V1.5 StatsScreen.kt # 统计 Tab（Stats.jsx 结构移植真数据版）：汇总瓦片四枚 → 训练热图(近12周, 组数四分位着色, Grid 自绘) → 体重趋势(Canvas 折线) → 周训练量(本周+近四周比例条) → 重点动作 Est.1RM(降序) → 肌图占位卡(BodyMap M2 后段) → 空态引导开练；Screen+Body 拆分保 preview
        │       │   ├── LoginScreen.kt # 登录/配对页（login-design.md 1:1）：品牌区/主 CTA 扫码(🆕 V1.2 真接线：共享 ScanOverlay→onScannedCode 满 8 位即兑)/手输展开/ghost/折叠服务器栏/六状态/成功过场
        │       │   ├── 🆕 V1.1 CoachScreen.kt # AI 教练对话屏（M2 骨架，StubCoachApi demo 两轮）：消息线程(气泡+提案卡 kind chips)/typing/composer/未配置引导；Screen+Body 拆分保 preview
        │       │   ├── 🆕 V1.0 CheckInScreen.kt # 打卡屏（§3.4）：会员码横滑卡轨(HorizontalPager+QR 亮码 Lru 缓存+◀▶重排+尾页添加位+卡头 ✎/🗑 编辑删除) /「记录到场」落 attendance / 历史月切换(‹ yyyy/MM ›+选中月列表, 统计恒锚本月)+前台恢复刷新(ON_RESUME→reload) / 扫码经共享 ScanOverlay(非 QR 拒收, 禁手输) / EditCardPanel(重扫换码+改名)+ConfirmPanel；Screen+Content 拆分保 preview
        │       │   └── 🆕 V1.4 LibraryScreen.kt # 动作库（「我的」二级页屏体，接线待收口）：常驻搜索框 + 结果列表（中文名+英文名+肌群/器材 chips 词表中文映射）+ 详情 ModalBottomSheet + loading/空态/无结果三态；Screen/Body 拆分保 @Preview；文案暂文件常量（library_ 键清单在头注）
        │       │   └── 🆕 V1.6 SyncBanner.kt # 同步状态横幅（V1.6）：Syncing 转圈小字 / Failed 红字+重试（requestSync 防抖）/ Ok·Idle 静默；SyncBannerViewModel（bus 读取 + Scheduler 重试）+ Content 拆分保双 @Preview；经 HomeScreen header 槽位挂首页列顶
        │       ├── 🆕 lib/               # 纯函数层（main 侧；与 test source 的训练域移植 lib/ 分居两源集）
        │       │   ├── CheckIn.kt       # 打卡域（V1.0）：normalizeFmt/canRenderFmt（qr.js 移植）+ moveGymCard（CheckIn.jsx 移植）；golden = CheckInTest.kt
        │       │   └── 🆕 V1.5 StatsCalc.kt # 统计域聚合纯函数：epley1RM（onerm.js epley 路径子集）/ setsDone / dailySetTally / heat 着色四分位 / weeklySetTotals / dayStreak / bwDelta30 / fmtNum / latestEstimates；epley 对拍 golden onerm.json，其余 Android 侧新增（语义来源注记在文件头）
        │       ├── 🆕 net/                # 🆕 V0.9 网络层（契约：api-go/openapi.yaml）
        │       │   ├── MuheApi.kt        # Retrofit 接口 + DTO（pair/data/config，409 透明 Response<JsonObject>）
        │       │   ├── AuthInterceptor.kt# Bearer 注入（无 token 裸发）
        │       │   ├── ServerConfig.kt   # baseUrl 可变持久化（SharedPreferences；登录页服务器栏接此）
        │       │   ├── NetModule.kt      # Hilt：Json/OkHttp/Retrofit/MuheApi 提供
        │       │   └── MuheAuthApi.kt    # viewmodel/AuthApi 真实现（redeem+token 落盘 / ping 探活）+ @Binds
        │       ├── 🆕 sync/              # 🆕 V0.9 同步协议客户端半（真源：useStore checkRev/doPush + stamps.go）
        │       │   ├── StateDoc.kt       # 整档 JsonObject 模型（拆档/合档/_ts·_rev·_wid·_wids/highestStamp）
        │       │   ├── StateMerge.kt     # mergeStates M1 子集（条目并集 + deleted/edited 只增不减，纯函数）
        │       │   ├── DocEntityMap.kt   # 文档条目 ↔ Room 八实体映射（raw 透传）
        │       │   ├── SyncMetaStore.kt  # 文档级元数据载体（SharedPreferences：_rev/_wid/余部 + 盖章助手）
        │       │   ├── SyncClient.kt     # 协议编排（rev 先比再拉 / 整档 PUT / 409 合并重推 / 访客跳过）
        │       │   ├── 🆕 V1.6 SyncScheduler.kt # 防抖调度面：requestSync 5s 窗口合一（latest-wins）/ syncNow 旁路立即 / Mutex 互斥；三触发源汇入（pair-redeemed / data-change / foreground）
        │       │   └── 🆕 V1.6 SyncStateBus.kt  # 四态广播 StateFlow（Idle/Syncing/Ok/Failed(error)，Scheduler 单写 UI 多读）
        │       ├── 🆕 di/                # 🆕 V0.9
        │       │   └── DatabaseModule.kt # Room DB Hilt 提供（MuHeApp 预留落点）
        │       ├── 🆕 viewmodel/         # 🆕 V0.8
        │       │   ├── PairViewModel.kt # @HiltViewModel 六态状态机（Idle/Loading/PairSuccess/CodeInvalid/Unreachable/CameraDenied）+ 码 clean 同构 + 🆕 V1.2 onScannedCode(扫码满 8 位即兑)/onCameraDenied（startScan demo 已撤）+ 🆕 V1.6 注入 SyncScheduler（redeem 成功 → syncNow("pair-redeemed") 立即拉）
        │       │   ├── AuthApi.kt       # 认证接口 + RedeemResult 三态（✅ V0.9 已由 net/MuheAuthApi 接管实现，stub 已删）
        │       │   ├── 🆕 V1.1 CoachApi.kt   # 教练接口（四方法对齐契约 operationId）+ DTO + StubCoachApi（demo 两轮）+ CoachApiModule @Binds（服务端批次照 MuheAuthApi 先例接管）
        │       │   ├── 🆕 V1.1 CoachViewModel.kt # @HiltViewModel 教练状态机（NotConfigured/Ready(messages,pending,thinking)）+ demo 两轮编排 + resolve 决策链路
        │       │   ├── 🆕 V1.1 ProfileViewModel.kt # 我的页数据源：TokenStore 登录态 + Room 计数聚合（workouts/measurements 计数 + 本月并集/streak）
        │       │   ├── 🆕 V1.0 CheckInViewModel.kt # 打卡屏数据源：卡轨(cards+lastCardId)/历史(history+monthCount+streakDays)；addCard(本地盖章+raw 透传)/moveCard(重插保序+doc 欠账戳)/checkIn(attendance 落库) + 🆕 V1.2 updateCard(先查再写防幽灵写, 条目覆盖+新 _ts)/deleteCard(recordDeletion 记戳→删行→lastCardId 善后) + 🆕 V1.6 五写路径落库 → requestSync("data-change") + bus Ok → reload（server→local 闭环）
        │       │   ├── 🆕 V1.4 LibraryViewModel.kt # 动作库屏数据源：query/results/loaded 三态；即时过滤（repo.search，5,632 条 ms 级）+ 在途检索 Job 取消保序
        │       │   └── 🆕 V1.5 StatsViewModel.kt # 统计 Tab 数据源：Room 只读（getAll）raw 解析 → StatsCalc 聚合 → StatsUiState（瓦片/热图/周量/1RM/体重点列，含 @Preview 样例态）
        │       ├── data/                # 🆕 Room 存储层（V0.5，八类 stamps 表一次建齐，裁定 #17）
        │       │   ├── MuheDatabase.kt  # @Database 八表 version=1 exportSchema + Migration 占位
        │       │   ├── converter/Converters.kt     # _wids List<String>↔JSON（kotlinx-serialization）
        │       │   ├── entity/          # 八 Entity + Stamps.kt（公共盖章类 _ts/_wid/_wids）
        │       │   ├── dao/             # BaseDao 泛型基座 + 八 Dao（见「数据层」节）
        │       │   ├── 🆕 CheckInPrefs.kt # 打卡屏本地偏好（V1.0）：lastGymCardId 记忆（SharedPreferences/ServerConfig 同款；本机不入协议档）
        │       │   └── 🆕 ExercisesRepo.kt # 动作目录数据源（V1.4）：assets catalog.json 一次性解析进内存（lazy 首访触发）；all/byId(alias 变体 id 解析正身)/search(中英 contains)/byMuscle/byEquipment；不进 Room（D2 静态打包裁定）；@Inject 构造注入即单例
        │       └── ui/theme/            # Color.kt（token 真源）/ Theme.kt（深色唯一）/ Type.kt
        └── test/
            ├── java/com/muhe/fit/app/
            │   ├── golden/GoldenTestBase.kt   # fixture 读取与断言基类（标量 + 🆕 V0.6 结构深比对）
            │   └── lib/
            │       ├── Onerm.kt               # onerm.js 移植示范（逐函数注 JS 行号）
            │       │   OnermGoldenTest.kt     # golden 对齐测试（32 例 / 5 测试方法）
            │       ├── 🆕 Js.kt               # JS 动态对象语义助手（spread/删键/`||`/`??`/jsRound）
            │       │   Ex.kt                  # exercises.js 分类规则（EXIDX 数据经 fixture catalog 注入）
            │       │   WorkoutModel.kt        # workout-model.js 子集（行形态/phase/per-side 聚合）
            │       │   History.kt             # history.js 子集（modeOf/rerampWarmups/bestWeightForEntry/cleanupSg）
            │       │   Dumbbells.kt           # dumbbells.js 子集（哑铃库存 ownedWeightsFor 等）
            │       │   Backoff.kt             # backoff.js 全量（回退阶梯）
            │       │   Progression.kt         # progression.js 全量（6 策略渐进引擎，含 rep-range/pyramid 依赖）
            │       │   FinishWorkout.kt       # finish-workout.js 全量（完成会话落盘边界）
            │       │   ProgressionGoldenTest.kt / BackoffGoldenTest.kt / FinishWorkoutGoldenTest.kt
            │       │   🆕 V1.0 CheckInTest.kt  # 打卡域纯函数对拍（normalizeFmt/canRenderFmt ← qr.test.js + moveGymCard ← CheckIn.reorder.test.jsx，13 方法）
            │       │   🆕 V1.5 StatsCalcTest.kt # 统计域聚合对拍（epley ← golden onerm.json 4 例直取；组数/热图/周量/streak/bwDelta/1RM 列表 = Android 侧新增聚合按注记语义手工推演，18 方法）
            │       │   🆕 V1.6 sync/StateMergeTest.kt # StateMerge 合并语义真测试（15 方法，手工 JSON fixture：八类并集/_ts 晚者胜/deleted·edited 只增不减/余部透传——V0.9 特列④静态对拍转正）
            └── resources/golden/
                ├── onerm.json                 # 4 块 32 例
                ├── progression.json           # 18 块 151 例（六策略 + triple 背书 + 诚实读法 + Epley deload）
                ├── 🆕 backoff.json            # 5 块 28 例
                └── 🆕 finish-workout.json     # 2 块 32 例（生成物勿手改）
```

## 构建前置（详见 README.md）

- **Wrapper jar 需用户侧生成**：`gradle wrapper --gradle-version 8.10`（需本地 gradle 8.10+，一次性）；仓库只带 `gradle-wrapper.properties`
- JDK 17+（建议 21）+ Android SDK Platform 35
- 版本目录钉一组互配已知稳定版（Kotlin 2.1.0 + AGP 8.7.3 + compose-bom 2024.12.01），首次真实构建时验证/整体升档；升 Kotlin 时 KSP 前缀必须同步

## 数据层（Room data/，V0.5）

八类 stamps 表一次建齐（裁定 #17 / android-app.md §2.2）。每表统一结构：**业务查询列（投影）+ `raw` 整条目 JSON（透传重建源）+ `stamp` 盖章列**。同步协议逻辑本身不在本批（M1 网络层批次）。

| # | 表（=JS 键名） | 主键 | 业务列 | 协议合并语义 | JS 条目真源 |
|---|---------------|------|--------|-------------|------------|
| 1 | workouts | id | d/index, start, end, name, routineId, vol | 只增不减（防复活） | useStore.js DEF.workouts；构造 demoSeed.js/sheets.jsx |
| 2 | routines | id | name | 可变（mergeEdits 以 `_ts` 晚者胜） | useStore.js；ex 槽位 lib/history.js |
| 3 | customEx | id | n, eq | 可变（mergeEdits） | sheets.jsx 编辑保存；lib/exercises.js healCustomEx |
| 4 | bodyweight | d | w, t | 只增不减（键=d） | sheets.jsx `{d,w,t}`；lib/bodyweight.js |
| 5 | measurements | d | t | 只增不减（键=d） | lib/measurements.js emptyMeasurement（19 固定键+other 进 raw） |
| 6 | gymCards | id | label, value, fmt | 只增不减 | useStore.js DEF.gymCards 注释（四键） |
| 7 | equipProfiles | id | name, accV | 可变（mergeEdits） | lib/equipment.js newProfile |
| 8 | attendance | id | gymCardId, ts/index, note | 只增不减（**裁定 #17 新增**） | android-app.md §2.2（无 JS 先例） |

**与服务端半 stamps.go 的语义对照**（真源 `ck/backend-go/internal/data/stamps.go`）：

- **盖章列**：每表 `Stamp`（`@Embedded`）三列 `_ts/_wid/_wids`，列名与 JS 条目盖章键一字不差；条目嵌套 `_f` 字段时间戳不建列，随 raw 透传；文档级 `_rev` 不落条目表（属 states 整档，存储载体 M1 网络层裁定）
- **只增不减/可变**：按 stamps.go delLists/delTime 实际语义——条目物理删除一律先记文档级 `deleted.<field>` 戳（同步层负责）再删行，协议防复活；「只增不减」指合并语义非物理必留
- **Dao 口径**（对齐客户端半职责：`GET /api/data/rev` 先比版本再拉全量；上传是整档合并，客户端半负责本地增改）：BaseDao 三件套 `upsertAll/upsert/update`（REPLACE 整行覆盖 = 条目级整体语义）；各 Dao `getAll`（上传重建整档）/`getSince`（按 `_ts` 本地辅助查询，**非协议增量下载**）/`getById`·`getDay`/`deleteById`·`deleteDay`/`clearAll`；attendance 另有 `getRange`（月度统计，M2 打卡屏消费）
- **时间戳**：一律 ms epoch Long 直存（JS number 同源），无 Instant 往返；`_wids` 经 kotlinx-serialization 编解码
- **迁移纪律**：客户端 Room version 独立计数（服务端 0012 顺延是 PG 序列，两不相干）；改 Entity 必须递增 version + 落 MIGRATION + schemas/ 快照，禁破坏性迁移（访客数据只有本地这一份）

**编译验证待用户侧**（本机无 Android SDK）：KSP 注解处理、`@Embedded` Stamp 展开、converter 注册等以首次真实构建为准。

## 网络与同步（net/ + sync/ + di/，V0.9）

**契约面**（端点 ↔ Retrofit 方法对照，真源 `api-go/openapi.yaml` 4,319 行，DTO 按 operationId 命名）：

| 契约 path | MuheApi 方法 | 说明 |
|-----------|--------------|------|
| POST /api/pair/create | `pairCreate()` | 铸 8 位配对码（Bearer 面端点，Android 备用） |
| POST /api/pair/redeem | `pairRedeem(@Body)` | 码换 Bearer token（security []，400 = 码无效；经 MuheAuthApi 包装） |
| GET /api/data | `getData()` | 全档 `{state, rev}`；state null = 服务端无档 |
| GET /api/data/rev | `getDataRev()` | 免整档比版本 `{rev, wid}`（前台轮询入口） |
| PUT /api/data | `putData(@Body JsonObject)` | `Response<JsonObject>`：200 `{ok,ts,rev,wid}` / **409 `{error,rev,state}` 透明暴露**（errorBody 手工解析） |
| GET /api/config | `getConfig()` | 登录面旗标 + ping 探活复用 |

**同步协议流程**（SyncClient，语义真源 `ck/frontend/src/store/useStore.js` checkRev/doPush + `api.js`）：

```
访客态（TokenStore 无 token）→ GuestSkip，一切留本地从不触网
syncNow = pullIfChanged + pushIfOwed
  pullIfChanged: GET /api/data/rev → rev/wid 与基座一致 → UpToDate；不同 → pull
  pull:  GET /api/data → StateMerge.mergeStates(本地档, 服务端档) → writeThrough
         （Room 八表 clearAll+upsertAll 单事务重建 + SyncMetaStore 余部替换 + 立基座 rev/_wid）
  pushIfOwed: 文档最高活动（_ts/条目盖章/deleted·edited）> lastSyncTs 才推
  push:  组档（Room raw 透传 + 余部）→ PUT {state, stamped:true, baseRev, baseWid}
         200 → 基座更新 rev/wid、lastSyncTs=doc._ts
         409 → 解析 {error,rev,state} → 合并落库 → 以新 rev 重推（≤2 次，同前端 doPush 上限）
         401/413/网络错 → Failed（status 透传；调用面处理重配对/离线提示）
```

**本地载体分工**（V0.5 预留「文档级元数据载体」的裁定落点）：Room 八表 = 条目数组（raw 透传零丢失）；**SyncMetaStore（SharedPreferences `muhe_sync`）= 文档级余部**（`_ts/_rev/_wid/_wids`、`deleted.*`/`edited.*` 戳、settings 键整块 JSON）+ 乐观并发基座（baseRev/baseWid/lastSyncTs）。DataStore 不在依赖白名单故弃用；M2 如换载体接口不变。本地删除/编辑先经 `SyncMetaStore.recordDeletion/recordEdit` 记戳再动行（各 Dao 注释「同步层负责」的落点，M2 UI 批次接线）。

**合并口径**（StateMerge = `sync-merge.js mergeStates` 的 M1 子集 + stamps.go MergeDeletions/MergeEdits 移植）：八类条目并集（同键 `_ts` 晚者胜；bodyweight/measurements 按 d 一天一条 t 晚者胜）；deleted 平局负值（加回）胜、删除戳 ≥ 条目最后编辑即移除（防复活）；edited 涉及的设置键按戳取值（week/dayPlan 点分子键同口径）。**M2 对齐项**（注释在案）：mergeEntry 条目内字段级 `_f`、undo 标记、unit 换算、resetAt/sinceReset、routineOrder、exWeights beatsWeight、workout media 并集。**已知时间窗**：attendance 删除戳在 api-go 服务端 delLists 扩到八类前，会被服务端 StampPut 的七类合并丢弃（条目本体透传不受影响）——api-go 扩展八类时自动消除。

**调度面**（V1.6 同步接线；SyncClient 协议逻辑零改动，只挂触发器与状态广播）：

```
SyncScheduler（@Singleton，防抖调度）
  requestSync(reason)  5s 窗口合一（单消费者 Channel + withTimeoutOrNull，latest-wins）
  syncNow(reason)      旁路窗口立即执行（Mutex 与防抖路径互斥）
  访客态 → SyncStateBus 停 Idle，从不触网
触发源：① pair-redeemed（PairViewModel redeem 成功 → syncNow 立即拉，boot pull 口径）
        ② data-change（CheckInViewModel 五写路径落库 → requestSync）
        ③ foreground（MainActivity ON_RESUME → requestSync，首进 = 启动引导同步）
SyncStateBus 四态 StateFlow（Idle/Syncing/Ok/Failed(error)）→ SyncBanner（首页列顶：
  syncing 转圈 / failed 红字+重试 / ok 静默）+ CheckInViewModel（Ok → reload 闭环）
测试：StateMergeTest 15 用例（八类并集/_ts 晚者胜/deleted·edited 只增不减/余部透传）
```

## Golden 对齐机制（移植纪律入口）

- 真源 = `ck/frontend/src/lib/*.js` + `*.test.js`；导出脚本 `ck/scripts/export-golden-fixtures.mjs` → `app/src/test/resources/golden/*.json`
- 现有 fixture（V0.7 共 **4 文件 243 例**，容差 1e-6）：`onerm.json`（4 块 32 例）+ `progression.json`（18 块 **151 例**）+ `backoff.json`（5 块 28 例）+ `finish-workout.json`（2 块 32 例）；fixture 段含 `label`（用例名）与 `catalog`（Kotlin Ex.kt 的最小目录数据——分类规则进 Kotlin、目录数据走 fixture）
- 移植面：`Onerm.kt` + 🆕 `Progression.kt`（含 rep-range/pyramid 依赖）/ `Backoff.kt` / `FinishWorkout.kt`（含 history.js 的 bestWeightForEntry/cleanupSg 依赖闭包）；宿主模型 = JsonObject 承载 JS 动态对象（spread/删键/透传零丢字段），全文见 [docs/golden-testing.md](./docs/golden-testing.md) V1.1
- 流程：JS 语义改动 → 先再生 fixture → 再移植 → 测试全绿。**fixture 是生成物勿手改**
- ⚠️ JS 测试覆盖缺口（V0.7 勘误后如实）：triple progression **有**专门测试 `progression.triple.test.js`，fixture 的 triple 用例已改为该文件直接背书（V0.6 的「无测试」论断系只查了 progression.test.js 所致，已在 changelog 勘误）；owned-bells 路径无 JS 用例（Dumbbells.kt 已移植未入库）——补测试时应对拍

## 里程碑

- **M1**：训练闭环 + 同步八类 + 登录
- **M2**：打卡 + AI Coach + 我的页

## 下一批工作（骨架后）

1. **首次真实构建**：装 Android SDK 35 + 本地 gradle 生成 wrapper → `./gradlew :app:test` 验 golden 243 例全绿 → `assembleDebug` 验依赖图
2. ~~Tab 导航成形~~ ✅ **V0.4 完成**（底栏 4 位 + 中央凸起 Start + 首页融合统计卡位 + 我的聚合页骨架；三屏为静态占位，数据域随 M1 批次接入）→ 🔄 **V1.5 修订**（裁定 #5 用户实机反馈：底栏 5 位，统计独立 Tab 回归，首页融合撤销——见 V1.5 changelog）
3. ~~Room schema：八类 stamps 表一次建齐~~ ✅ **V0.5 完成**（data/ 层八 Entity + 八 Dao + MuheDatabase；同步协议逻辑归 M1 网络层批次，文档级 `_rev/deleted.*` 载体届时裁定）
4. ~~训练域移植扩面~~ ✅ **V0.6 完成**（progression/backoff/finish-workout 全量移植 + fixture 扩块至 4 文件 231 例 + 依赖闭包 8 文件；JS↔fixture 侧已实跑核对，Kotlin 侧编译验证随 #1）
5. ~~i18n 资源~~ ✅ **V0.4 完成**（屏文案已全量入 values/ + values-en/ 共 43 键；后续批次延续「零硬编码、中英同步落」纪律；🆕 V0.8 登录屏 +25 login_ 键至 68）
6. **app icon + release 签名**：mipmap 全密度 + keystore（行动项 #3）+ proguard 实则
7. **登录屏占位对接**（V0.8 遗留，均注释标位）：① ~~扫码 CameraX（M2 批次）~~ ✅ **V1.2 完成**（共享 ScanOverlay → PairViewModel.onScannedCode 满 8 位即兑；CameraDenied 触发路径生效；qr-code 图标仍属 icons-extended 白名单外未引入，主 CTA 无图标态）② ~~网络层——net/ 落地后由 MuheApi 实现 `viewmodel/AuthApi.kt` 接口并删 StubAuthApi/LoginApiModule 占位绑定~~ ✅ **V0.9 完成**（net/MuheAuthApi 接管，ViewModel 零改动）③ ~~base URL 持久化载体~~ ✅ **V0.9 完成**（裁定 SharedPreferences：net/ServerConfig；改址属重配对事件，需同步 TokenStore.clear + 重建网络栈的时机随登录屏服务器栏接线批次定）
8. ~~**同步接线（M1 后续批次）**~~ ✅ **V1.6 完成**（① SyncScheduler @Singleton：`requestSync` 5s 窗口防抖合并（latest-wins）+ `syncNow` 旁路立即执行 + Mutex 两路互斥 ② 触发源三路——登录态变化（PairViewModel redeem 成功 → `syncNow("pair-redeemed")` 立即拉）/ 数据变更（CheckInViewModel 五写路径落库 → `requestSync("data-change")`）/ 前台恢复（MainActivity ON_RESUME → `requestSync("foreground")`，首进 = 启动引导同步）③ SyncStateBus 四态 StateFlow（idle/syncing/ok/failed）+ SyncBanner 挂首页列顶（ok 静默 / syncing 转圈 / failed 红字+重试）④ CheckInViewModel 订阅 bus Ok → reload（server→local 闭环）⑤ StateMergeTest 15 用例（V0.9 特列④ 静态对拍转真测试）。**遗留 → M1 后续**：30s 前台轮询（useStore poll 口径，触发源已留 Scheduler 面）、401 → 重配对分流（横幅暂统一 failed 档，`SyncUiState.Failed.error.status` 已随行保留分流数据））
9. **🆕 V1.1 遗留——Coach 真接线（M2 服务端批次，G3 裁定）**：api-go `internal/coach` 首版 Go 原生重写（33 文件 sidecar 桥 → 原生）→ CoachApi 真实现落 `net/`（照 V0.9 MuheAuthApi 接管 AuthApi 先例：实现 + @Binds 换绑删 StubCoachApi，CoachViewModel 零改动）→ `getCoachStatus` 轮询挂前台生命周期（CoachChat.jsx useCoachStatus 口径）→ 提案 JSON 解析实体化（changes[] 逐条勾选 + bundle 计划卡 + debrief score 卡）→ Intake 问卷屏（CoachIntake 契约形状）→ 线程持久化（S.coach.chat 同构，LocalSql 或文档余部载体随批次裁定）
10. ~~**V1.0 遗留——打卡屏二批**~~ ✅ **V1.2 完成**（① 卡编辑/删除：EditCardPanel/ConfirmPanel + updateCard/deleteCard，删除先 `SyncMetaStore.recordDeletion("gymCards",…)` 记戳再 deleteById——Dao 注释「同步层负责」接线点兑现 ② 登录屏扫码对接：组件抽 ScanSupport.kt 共享 + LoginScreen 真接线 + PairViewModel.onScannedCode/onCameraDenied（startScan demo 撤）③ QR 位图 LruCache）。遗留：④ 扫码节流/对焦优化与多码场景（页面多码选一）实机调优 ⑤ streak 窗口（当前回溯一个月）跨年延续已由 minusMonths 窗口覆盖，实机核对
11. **🆕 V1.4 接线——动作库收口**：MuheNavHost 加 `Routes.LIBRARY = "library"` 全屏二级路由（底栏隐藏同 coach/checkin 口径）+ ProfileScreen 入口行（屏单 v2 裁定：动作库为「我的」二级页，训练数据组加行即可）+ strings.xml / values-en 补 library_* 7 键（键名与现值清单见 LibraryScreen.kt 头注：中文照抄、英文直译）并逐处换 stringResource。屏体与数据（V1.4）零改动可直接接
12. **🆕 V1.5 遗留——统计屏 M2+ 深化**：① BodyMap 三视图（肌平衡/疲劳/力量）移植——人体 SVG/Canvas 绘制工作量大，本批占位卡标注「M2 后段移植」；② 周肌肉量细分——需动作→肌群映射，V1.4 ExercisesRepo.muscles 已具备数据面，接线时把 StatsCalc 的 latestEstimates/组数聚合扩肌群维度；③ 体重卡时间轴改真时间刻度（首版按序均分，注记在 WeightChart）；④ streak 周口径与 JS streakWeeks 对齐（当前日连击，与「我的」页一致）；⑤ 统计数据变更事件驱动 refresh（同步接线 #8 完成后挂）

## 版本规程

- `V2.0.1` 起步，第 3 位递增；tag 前缀 `app-`（如 `app-v2.0.2`）；双仓拆分后独立版本线（2026-11-19）
- `versionName` 读本目录 `VERSION` 文件（`app/build.gradle.kts` 构建期读取）；`versionCode` 当前手工 1
- 算号/落地：`sh scripts/next_version.sh calc|apply app patch ["变更内容"]`

## 测试门

- `./gradlew :app:test`（JVM 单测）——首闸 = golden fixture 对齐（**243 例**，机制见 docs/golden-testing.md；progression 151 / backoff 28 / finish-workout 32 / onerm 32）+ 🆕 V1.0 打卡域纯函数对拍 `CheckInTest`（13 方法，用例直取 qr.test.js / CheckIn.reorder.test.jsx，非 fixture 路径——fixture 化随打卡域扩块）+ 🆕 V1.5 统计域对拍 `StatsCalcTest`（18 方法：epley 4 例直取 golden onerm.json；其余 Android 侧新增聚合按语义注记手工推演，无 JS fixture 对拍）+ 🆕 V1.6 同步合并语义 `StateMergeTest`（15 方法，手工 JSON fixture，M1 子集口径逐例推演——V0.9 特列④转正）
- CI：[app-ci.yml](../.github/workflows/app-ci.yml)（**占位未建**；建议 `assembleDebug` + `:app:test` 两步，与 api-ci / agent-ci 三套 paths 隔离）

## 依赖白名单纪律

白名单 8 项 + 二版 ML Kit（清单见 decisions.md 裁定 #12 / C8）**已全部落进 `gradle/libs.versions.toml`**：Compose 全家桶 / Retrofit（+kotlinx converter）/ OkHttp / kotlinx-serialization / Hilt / Room / Coil / zxing-android-embedded（+core）。ML Kit 只留注释位不进依赖树。白名单外加依赖必须过审才能进 build.gradle。

**🆕 V1.0 过审一项——CameraX 1.4.2**（core/camera2/lifecycle/view 四件）：打卡扫码系 M2 清单首项（android-app.md §3.4），用户批准 M2 批次启动即隐含放行；注记落 `libs.versions.toml` `androidxCamera` 行，仅打卡扫码消费不另开用途。同批显式声明的 compose-foundation 非 新依赖（Compose 全家桶白名单内成员，BOM 统一版本，HorizontalPager 载体）。

## 未验证项（如实）

- **构建未实跑**：本机无 Android SDK / gradle，全部 gradle 脚本、Compose 代码、KSP/Hilt 注解处理为静态正确性推演，以首次真实构建为准；🆕 V0.5 Room data 层同口径（@Embedded 展开 / converter / KSP schemaLocation 均未编译验证）；🆕 V0.6/V0.7 训练域移植 8 文件 + 3 测试类同口径（沙箱无 gradle/kotlinc，静态自查 + JS↔fixture 侧实跑闭环已做）；🆕 V0.8 登录屏 3 文件（LoginScreen/PairViewModel/AuthApi）+ NavHost 接线同口径——Hilt 图完整性（StubAuthApi @Binds + @HiltViewModel）与 ColumnScope.AnimatedVisibility(weight) 用法以首建为准，静态自查已过（键对齐/引用闭合/括号配平）；🆕 V0.9 net/sync/di 11 文件同口径（静态自查已过），**特列**：① retrofit-kotlinx-serialization converter 的 `asConverterFactory` 扩展包名（2.11.0 first-party `retrofit2.converter.kotlinx.serialization`）② Hilt 图（TokenStore @Binds / DatabaseModule @Provides / NetModule 三 Provider / MuheAuthApi @Binds 接管后旧 LoginApiModule 删除无重复绑定）③ kotlinx JsonObject 作 Retrofit @Body 的 serializer 解析 ④ StateMerge 合并语义只做了静态对拍未跑用例（列批次清单 #8；🩹 V1.6 已转真测试：`test/sync/StateMergeTest.kt` 15 用例，期望值按 M1 子集口径逐例推演，待 `:app:test` 实跑）；🆕 V1.6 同步接线 4 新 + 6 改（新：SyncScheduler/SyncStateBus/SyncBanner/StateMergeTest；改：MainActivity/PairViewModel/CheckInViewModel/HomeScreen/MuheNavHost/strings×2）同口径（静态自查已过：括号配平/引用闭合/178 键中英 diff 零冲突），**特列**：① SyncScheduler 防抖循环（Channel 单消费者 + withTimeoutOrNull 起窗，进程级 scope 无取消面）与 syncNow 旁路的 Mutex 互斥以首建为准 ② MainActivity @Inject 字段 + LifecycleEventObserver 组合（ON_RESUME 补发/首进触发）同 V1.3 口径 ③ SyncBanner 的 header 槽位注入（HomeScreen 预览零 VM）与 hiltViewModel 默认参以实机为准；🆕 V1.0 打卡屏 5 文件（CheckInScreen/CheckInViewModel/CheckInPrefs/lib/CheckIn + CheckInTest）同口径（静态自查已过：括号配平/引用闭合/中英键 diff 对齐），**特列**：① CameraX PreviewView + ImageAnalysis YUV 解码（含 rotationDegrees 转正数组换算 rotate90/180/270 索引推导）与 MultiFormatReader decodeWithState 复用语义以实机为准 ② `androidx.compose.ui.platform.LocalLifecycleOwner` 在 ui 1.7.x 已 deprecated（可用，收口时换 `androidx.lifecycle.compose.LocalLifecycleOwner` 需 lifecycle-runtime-compose）③ moveCard「REPLACE 重插保 rowid 序」依赖 SQLite INSERT OR REPLACE 赋新 rowid + getAll 无 ORDER BY 按行号扫描的行为（SQLite 一贯行为，首建时应以真机验证顺序稳定性）④ HorizontalPager settledPage/snapshotFlow API 面（foundation 1.7.x）；🆕 V1.2 二批 6 文件同口径（静态自查已过：括号配平/键对齐/引用闭合/138 键中英 diff），**特列**：① ScanSupport 抽取后 CheckInScreen/LoginScreen 的 AnimatedVisibility 覆盖层叠放依赖 NavHost 目的地 Box 容器的兄弟节点层叠序（composition order 后者在上）② updateCard 的先查再写防幽灵写是最好努力（无事务锁，与他端删除竞态窗口极小且失败安全——result(false) 如实回报）③ 编辑面板草稿用 remember 非 Saveable（配置变更丢草稿，可接受不复杂化）④ LruCache 12MB 上限按 230dp@3x≈2.1MB/张可容 ~5-6 张，超限自动逐出；🆕 V1.3 2 文件（CheckInScreen/CheckInViewModel）同口径——月切换钳制（未来月不可达）与 ON_RESUME 补发重叠 reload 均注释在案；🆕 V1.5 统计 Tab 6 文件（MuheNavHost/StatsScreen/StatsViewModel/StatsCalc/StatsCalcTest/HomeScreen/strings×2）同口径（静态自查已过：括号配平/引用闭合/168 键中英 diff 对齐零冲突），**特列**：① NavigationBar 子级等宽布局在 5 子项（4 TabItem + 1 FAB Box）下的实际排布以实机为准（V0.4 的 4 子项同款算式，预期等比）② material-icons-core 的 `Icons.Outlined.List`（ListOutlined）存在性——core 集有 outlined 面，首建时如缺则改用 filled 单态 ③ StatsCalc 的 raw JSON 解析依赖 WorkoutEntity.raw 为整条目 JSON（V0.5 纪律），同步挂接（#8）前的本地数据形状一致
- **版本目录未实机解析**：catalog 各坐标为已知稳定版组合（未连 Google Maven 校验最新版），首建时可能需微调（尤其 KSP 与 Kotlin 配对）
- **golden 测试未执行**：fixture 由 JS 真源实算生成（progression/backoff/finish-workout 共 211 例已与 JS 测试断言值 **130 组**逐例核对全过，V0.7 含 triple.test.js 背书的 13 组），Kotlin 侧断言逻辑等 wrapper 生成后 `:app:test` 验证

## 骨架期纪律（延续）

- 设计与契约以 `ck/docs/app-design/` 为准，勿在本目录另立设计
- 移植期间**不改 JS 侧语义**；发现算法 bug 先修 JS + 补测试，再同步移植
