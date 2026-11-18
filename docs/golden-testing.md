[← android-app CLAUDE.md](../CLAUDE.md) · **docs / golden-testing**

# Golden 对齐机制 · JS 真源 ↔ Kotlin 移植（训练域纯函数）

> 状态：V1.0（2026-11-17，随工程骨架建立）· 设计依据：[android-app.md §2.1](../../ck/docs/app-design/android-app.md) §2.1

## 机制（四条）

1. **fixture 真源 = JS 测试**。训练域纯函数的唯一语义真源是 `ck/frontend/src/lib/*.js` + 同目录 `*.test.js`（原仓库铁律：训练逻辑必须是纯函数 + 单测）。Android 端**不另立语义**：JS 侧语义改动先改 `ck/frontend` 的源与测试，再再生 fixture，最后移植对齐。
2. **导出脚本**。`ck/scripts/export-golden-fixtures.mjs`（Node ESM，零 npm 依赖）直接 import JS 源函数实算代表用例，产出 JSON：

   ```bash
   node ck/scripts/export-golden-fixtures.mjs
   # → android-app/app/src/test/resources/golden/onerm.json
   ```

3. **Kotlin 基类逐条断言**。`app/src/test/java/com/muhe/fit/app/golden/GoldenTestBase.kt` 负责读 fixture、逐 case 跑 Kotlin 移植函数、断言「同输入 → 同输出」；具体测试（如 `OnermGoldenTest`）只写参数映射。JSON 解析用 kotlinx-serialization 的 `JsonElement` 手工导航——该库在白名单主依赖内，**测试期零新增依赖**。
4. **双向一致纪律**。移植/修改任何训练域函数：
   ① JS 侧先动（源 + `*.test.js`）→ ② 跑导出脚本再生 fixture → ③ Kotlin 移植改到 fixture 全绿 → ④ 两侧提交同步。**禁止**只改一侧或手改 fixture 文件（fixture 是生成物）。

## fixture 格式

```jsonc
{
  "module": "onerm",                  // JS 源模块名
  "source": "ck/frontend/src/lib/onerm.js",
  "generatedBy": "ck/scripts/export-golden-fixtures.mjs",
  "tolerance": 1e-6,                  // 数值断言容差（见下）
  "constants": { "REP_CAP": 12, ... },// JS 侧导出常量，测试启动时与 Kotlin 常量比对
  "functions": [                      // 每个函数一块
    { "fn": "estimate1RM",
      "cases": [ { "args": [100, 5], "expected": 116.7 },
                 { "args": [0, 5],  "expected": null } ] }
  ]
}
```

- `expected` 三态：数值 / `null`（JS 返回 null 的语义）/ 字符串（如 `formulaOf` 回落的公式名）。
- `args` 按位对齐 JS 函数签名；缺位 = JS 默认参（Kotlin 侧测试负责填默认值）；对象入参（如 `formulaOf` 的状态 `S`）以最小形状 `{ "oneRmFormula": "..." }` 表达。
- 🆕 `label`（V1.1，progression/backoff/finish-workout 起全量携带）：用例的人类可读名（`nextPrescription`/`readSession` 的叙事用例强制带，供维护与核对脚本按名对拍）。断言层忽略此字段。
- 🆕 `catalog`（V1.1，训练域 fixture 必备）：`id → { eq, bp, n }` 的最小目录数据——JS 的 `EXIDX` 是整本目录索引，Android 侧不搬数据，只搬分类「规则」（`Ex.kt`：isCardio/isBodyweightEq/isLoadedEq/isAssisted/defaultIncrement），目录「数据」由导出脚本从 EXDB 取用例实际引用的动作注入。查不到的 id 按「未知动作」处理（与 JS 对 EXIDX 未命中的 falsy 路径一字不差）。

## Kotlin 侧断言两层（V1.1）

- `assertGoldenCases`：标量结果（数值/字符串/布尔/null），数值走 1e-6 容差。
- `assertGoldenObjects`：对象/数组结果（readSession/nextPrescription/applyPrescription/backoffWeights/buildCompletedWorkout…）——`expected` 是 JS `JSON.stringify` 的产物，Kotlin 侧以 kotlinx `JsonObject` 结构等值深比对（键集合必须两侧一致：JS undefined 键不落 JSON ↔ Kotlin 侧不写该键；数值沿同一容差）。

### 宿主模型纪律（progression / backoff / finish-workout）

这三个 JS 模块在对象上大量使用 spread、delete、透传（finish-workout 的契约是「无标记的行按引用原样透传」），任何按白名单重建 data class 的形态都会在透传链上丢未知字段。故行/条目/状态一律以 kotlinx `JsonObject` 为载体（`lib/Js.kt` 的 JS 语义助手），函数体逐行对位 JS 源；纯数值闭包形态（`RepRange`/`TripleSets`）用 data class。

## 容差口径：1e-6 绝对值

- rounded 输出（`estimate1RM` 0.1 步进、`calculate1RMAccuracy` 0.01 步进）远大于容差，实错（公式错、权重错、语义错）都在 1e-2 量级以上，一测即爆。
- 未取整输出（`weightedEstimate` 全精度浮点）只可能受 JS/JVM `Math.pow`/`exp` 库的 **1-ulp 级差异**（~1e-13）影响，容差足够吸收且不失灵敏度。
- 求和顺序敏感：JS 对象键序 = Kotlin `LinkedHashMap` 插入序，移植时**不许重排** FORMULAS/WEIGHTS。
- 取整陷阱：JS `Math.round` = `floor(x + 0.5)`（半值向 +∞），**不是** `kotlin.math.round`（ties-to-even）——移植统一走 `jsRound` 助手（见 Onerm.kt）。

## 当前覆盖

| fixture | 函数块 | 用例数 | 覆盖语义 |
|---------|--------|--------|----------|
| `onerm.json` | 4 | 32 | Epley 典型值与默认参 / 六公式族各一 / 单次组=测量 / 单式路径 RIR 不参与 / weighted 集成（含 RIR 换算）/ REP_CAP 与 WEIGHTED_REP_CAP 上限拒绝 / 非法输入 null / weightedEstimate 全精度 / calculate1RMAccuracy 边界 / formulaOf 未知值回落 |
| 🆕 `progression.json`（V1.1，V0.7 扩至 151 例） | 18 | 151 | policyFor 覆盖性判定 ×7 / weightIncrement ×3 / epley1RM·deloadTarget1RM·deloadFactorOf ×9 / snapWeight·addStep·stepWeight·deloadTo ×10（含 Math.round 半值陷阱与 #175 离格直加）/ plannedOf·planChanged（#275 + setsMax 属计划，triple.test.js:46-48）×10 / tripleSetsOf ×4（triple.test.js:39-42 直接背书）/ selectDeloadCandidate ×2 / readSession 诚实读法 ×17（hit/短次/未勾/缺组/无处方/计时/per-side 翻倍行/热身过滤/phase 权威/back-off 取轻/triple 每组目标 ×2）/ stallCount ×6（含 double run 最优与打破豁免）/ sessionsFor ×3 / **nextPrescription ×63**（六策略全谱：linear 18 + bodyweight 8 + 0kg 2 + greyskull 4 + double 8 + #175 离格 3 + time 4 + triple 8（first/基组爬/加组/三滞 deload + 第四组独爬/超额加组/setsMax 重开/辅助器械减助力，triple.test.js 背书）+ #275 计划改动/借用/复制 4 + 排除/旧历史 8）/ applyPrescription ×16（只动未记录行/热身 ramp/加组只长工作行/drop-set 保 drops/triple 已记录保留+每组目标/单侧拆分） |
| 🆕 `backoff.json`（V1.1） | 5 | 28 | isBackoff 开关与三排除 ×6 / backoffAt ×10（在格吸附、离格直降、下限钳步长）/ backoffWeights ×6（含 1.25 步一位小数算术）/ backoffStepOf ×4（inc 覆盖/默认/计时关/辅助器械不适用）/ applyBackoff ×2（热身与已勾不动、per-side 两肢同降） |
| 🆕 `finish-workout.json`（V1.1） | 2 | 32 | sessionEnd ×11（Finish 紧随保留/次日止于末组/长休补记不拉长/30min 有氧收尾/per-side 双侧计时/tick+len reach 语义）/ buildCompletedWorkout ×21（旧形状逐字/planSec·weightOrigin 行与侧剥除/无标记行原样透传/routineIds↔routineId 三态/rid·noProg·planned 条件写入/topW 从完成行取不认陈旧/legacy topW 回落/excludeFromProgression 镜像 iff 全 noProg/笔记与 pin/超级组落单掉标） |

## 已知边界（如实记录）

- **NaN 不入 JSON**：JS 测试里的 NaN 用例无法经 JSON 表达，由 JS 侧原测试覆盖；Kotlin 侧对应分支（`isFinite` 守卫）靠代码审读对齐。
- **DB 依赖函数未进 golden**：`bestSetOf` / `e1rmSeries` / `best1RM` / `is1RMRecord` 依赖 exercises/history/dumbbells 模块，等对应模块移植后扩 fixture（导出脚本届时追加函数块即可，格式不变）。🆕 V1.1 注：bestWeightForEntry 链已随 finish-workout 批次移植（History.kt），其 golden 覆盖经 buildCompletedWorkout 的 topW 用例间接钉住。
- 🆕 **回调参数不可 fixture 化**：`buildCompletedWorkout(active, { snapshotFor })`（muscleSnapshot 快照）由 JS 侧原测试覆盖；`end` 默认参 `Date.now()` 不可复现，fixture 一律显式钉死。
- 🆕 **rerampWarmups 的 owned-bells 数组步长**（issue #376）：scalar 步长已移植，库存数组分支随 dumbbell 批次落地（golden 未覆盖，代码有注）。
- 🆕 **triple progression 的测试背书（V0.7 修正）**：V1.1 曾误记「triple 无独立 JS 测试」——实际存在专门的 `progression.triple.test.js`（全周期 3×8→…→5×12→+2.5、setsMax 属计划、第四组独爬、超额计数、三滞 Epley deload 57.5、辅助器械减助力、每组目标 readSession/applyPrescription/单侧拆分全覆盖）。V0.7 起 fixture 的 triple 用例改为该文件直接背书（tripleSetsOf 四态 / plannedOf·planChanged 的 setsMax / 第四组独爬 / 超额加组 / setsMax 重开 / 辅助器械 / readSession 每组目标 ×2 / applyPrescription 保留+加组与单侧拆分），最早按源注释构造的 4 例保留（同一代码路径，deload-at-cycle-start 场景该文件未直接断言）。
- 🆕 **哑铃库存路径未入 fixture**：JS 测试无 owned-bells 用例（ownedWeightsFor/ownedUp/ownedDeload/ownedAround 已随依赖闭包移植进 Dumbbells.kt），按「用例值取自 JS 测试」纪律不入库，靠代码审读对齐。
- **fixture 是生成物**：不手改；改动语义唯一入口是 JS 源 + 导出脚本。

## 运行

```bash
# 再生 fixture（JS 真源变化后）
node ck/scripts/export-golden-fixtures.mjs

# Kotlin 侧对齐测试（需先按 android-app/README.md 生成 gradle wrapper）
cd android-app && ./gradlew :app:test
```

建议 CI（app-ci.yml 落地时）：`assembleDebug` + `:app:test` 两步，测试失败即阻塞合并。

## 变更记录

| 日期 | 版本 | 内容 |
|------|------|------|
| 2026-11-17 | V1.0 | 初稿：四条机制、fixture 格式、容差口径、onerm.json 覆盖表（4 块 32 例）、NaN/DB 依赖边界 |
| 2026-11-17 | V1.1 | 🆕 训练域移植扩面：① fixture 格式扩展 `label`（叙事用例强制携带）与 `catalog` 段（Ex.kt 最小目录，规则/数据分离）② Kotlin 断言双层（assertGoldenCases 标量 + assertGoldenObjects 结构深比对）③ 新增 progression.json（18 块 139 例）/ backoff.json（5 块 28 例）/ finish-workout.json（2 块 32 例）④ 宿主模型纪律：行/条目/状态以 JsonObject 为载体（lib/Js.kt），纯数值闭包用 data class ⑤ 边界补记：回调参数/owned-bells 步长/哑铃库存路径（其中「triple 无 JS 测试」论断有误，V1.2 勘误） |
| 2026-11-17 | V1.2 | 🩹 勘误 + triple 背书扩块：V1.1 误记「triple 无独立 JS 测试」——实际存在专门的 `progression.triple.test.js`（全周期/setsMax 属计划/第四组独爬/超额计数/三滞 Epley deload 57.5/辅助器械/每组目标读判与落组全覆盖）。progression.json 扩至 **151 例**（+12：tripleSetsOf 四态 :39-42、plannedOf/planChanged 的 setsMax :46-48、第四组独爬 :66、超额加组 :85、setsMax 重开 :160-162、辅助器械 :190、readSession 每组目标 :165-171、applyPrescription :173-184）；核对增至 **130 组全过** |
