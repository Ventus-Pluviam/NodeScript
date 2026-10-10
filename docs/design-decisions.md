# AutoScript 决策记录

> **本文件收两类东西**：① 已拍板的开放决策点（从框架设计 §18 外迁，
> §号与编号保持原样）；② 被实测推翻/改过的口径（原口径与新口径并列，**不删旧**）。
>
> 契约在 [`docs/design/`](design/) 12 卷（入口 [`framework-design.md`](framework-design.md) 索引）；落地状态见
> [`design-status.md`](design-status.md)。三者分工：
> **契约说「是什么」，台账说「实现到哪」，本文件说「为什么这么定、什么被改过」。**
>
> **只追加**：新决策按日期加在「已拍板」顶部；推翻旧口径时在「已推翻」加一行，
> 原文照抄，不编辑历史。

---

## 60. 控制台「活着」三件：拉不推、流与尾部并存、历史与审计史纪律相反（2026-10-10，批 90）

**背景**：用户口径是一句体验描述 ——「控制台从『现取一次』变成『活着』」。这句话底下是
三个各自独立、但同属一类的问题（**界面看起来是活的，实际每一步都要人推一下**）：
输出是跑完才给的、进度要手动刷、历史关掉就没。三条裁定写在这里，因为三条都**反直觉**
（都能想出"更自然"的做法，而那个做法各有具体的坏处）。

**① 为什么是拉不是推（`:ui` 的 `LivePoll`）**。宿主确实有推送面：`InstallCoordinator.progress`
那条 `SharedFlow`。但它是 `MutableSharedFlow(extraBufferCapacity = 256)`，**没有重放** ——
`extraBufferCapacity` 是"发射方不阻塞"的缓冲，**不是历史**，订阅者晚一步就永久丢那一批。
而"用户切进控制台时命令已经跑了一半"**正是常态**，不是边缘情形。两个 seq 游标读口
（`consoleOutput`/`npmInstallEvents`）本来就是为这件事设计的：它们答的是"从 seq N 起有什么"，
晚到也能取全。故**拉**。这条同时也是对"要不要给 SharedFlow 加 replay"的回答：加了 replay
就得决定 replay 多少、丢了怎么告诉用户，而那正是 `SeqRing` + 游标已经在做的事 —— 两套并存
只会让"谁先看到新行"分家。

**三条自我约束**（缺一条就把"活着"变成"一直在问"）：**有界**（`maxTicks` 到顶必停 ——
宿主永远说在跑时界面不能跟着无限轮询，那比停下来更糟：用户看到一个永远转的圈）；
**只依据宿主给的事实停**（`ConsoleCmdState.running`/`NpmState.installing`，不是界面自己猜
"多久没新行就算完"）；**每轮走同一个读口**（`tick` 就是那个现取函数本身）。间隔与轮数
**可注入**是纪律不是方便：单测要验"有界"就得让 `maxTicks` 跑满，用真实间隔（400ms）
会把那条用例拖成四分钟，而**一条拖慢整条 CI 的用例迟早会被人 `@Disabled` 关掉，那等于没有
这条守卫**。

**② 流与 `outputTail` 并存，且流报过就不补尾部**。`HeavyOpOutcome.outputTail`（批 84）
是**摘要面**（谁都要得到、进审计与终态行），`OutputSink`（本批）是**过程面**（拿不到也
不该让安装失败）。两条并存的理由很实际：流是**尽力而为**的（执行体可以在没有订阅者时不报），
而尾部是**结果的一部分**。但**不能两条都显示**：流真报过时再补一行 `outputTail`，同一段
文本会在控制台出现两遍，用户会以为 npm 跑了两回（`var streamed` 标志就是为这个）。
空行**不报**：控制台是"一行一条、各自带时刻"的账，而真实 npm 输出以空行结尾 ——
`takeLast(8000)` 在流式模式下取到的正是那些空行（`HostNodeNpmE2ETest` 抓到的原话：
`OUTPUT 行必须是 npm 自己说的那段话（实为「」）`）。

**③ 命令历史与审计史纪律相反，且写口在派发点**。`ConsoleHistory` 与 `InstallHistory`
是同一个目录下、同一种格式（jsonl + 追加）的两个文件，**但纪律相反**：前者是**审计事实**
（只追加、永不清理，清理策略是 backlog A13，动它会碰 §8.5 的幂等锚点），后者是**便利缓存**
（允许修剪：超阈值整体重写成最近 N 条）。这条差别写进类 KDoc 的**唯一理由**是：
下一个人很容易把修剪也搬到 `InstallHistory` 上去 —— 那才是真错（丢掉的不是"一条命令"，
是审计事实）。两条附带裁定：
- **按项目分开读**（与 `history()` 的无参全量相反）：控制台的命令跑在某个项目上，
  在 A 项目敲的 `npm install axios` 翻到 B 项目去点，落的是 B 的 `node_modules`，
  而按钮上那行字一模一样。两问不同："这个宿主发生过什么" vs "我在这个项目里敲过什么"。
- **带凭据形态的行整条不记**（`_auth`/`_password`/`--otp`/`npm_token`）。`npm install
  --//registry.example.com/:_authToken=…` 是**能敲进控制台的**（批 84 那条"入史只记子命令、
  完整 argv 一个字不改地交给 npm"的注释已经点破了这个风险），而内存里的环随进程消失、
  这份历史落盘跨重启还在。**不打码后记录**：打码后的历史看起来是一条能跑的命令，用户点它
  填进输入框、得到的是「认证失败」—— 一个由历史自己造出来的假故障；而"这条没进历史"
  最多是少一格便利。**边界**：认的是**参数名形态**不是熵启发式 —— 用户把 token 直接当包名
  敲不会被认出来。本类是便利缓存，不是 DLP，这条边界写在 `looksSecret` 的 KDoc 里。
- **写口在 `:ui` 的派发点**（`recordConsoleHistory`），不在执行入口：历史要记的是**用户敲的
  那行原文**，而执行入口拿到的是**已经定形**的东西 —— shell 面那条只收得到剥掉入口词的正文
  （`su id` 变成 `id`），记下来再点一次就会在默认模式里被当成 npm bin 解析。副作用是
  「拒收的行与进/退模式不进历史」变成了**自然结果**而不是特判（那些分支在派发之前就返回了）。

**同批修一处真错（探针测出来的，不是读代码读出来的）**：`InstallCoordinator` 里两处执行体
sink 调用点写的是 `events.tryEmit(ev)` —— 只喂了 `SharedFlow`，**没经 `emit`**。而 `emit`
是唯一写三个地方的那个（`installEventRing`/`consoleRing`/`events`），它自己的 KDoc 写着
「全部阶段都经这里」。后果：执行体独有的 `DOWNLOAD`/`REIFY` 阶段**从未进过事件环** ——
脚本侧 `onProgress` 收不到它们，阶段条停在 `RESOLVE`。**为什么一直没被发现**：三个环各自的
测试都绿，因为它们各自只喂自己那条路，没有一条测试问"执行体报的阶段有没有到环里"。
探针（`SinkRingProbeTest`，已删）打出的 `PROBE-PHASES-IN-RING=[QUEUED, RESOLVE, POST_CHECK]`
是这件事唯一的证据。**教训**：`tryEmit` 与 `emit` 在类型上兼容、在语义上分家，
而分家的方向是**静默少写**——凡是"一个入口写多处"的地方，测试要问的是**"有没有到"**，
不是"这条路上有没有"。

**落地**：`OutputSink`（`:app-service:npm` `InstallSeams.kt`，**非挂起**且实现方不抛 ——
读流线程不该为一个 push 让出、界面少显示一行绝不该让安装失败；`fun interface` 抽象方法
不能带缺省值，正好逼每个实现方显式表态「我报不报流」）/ `HostNodeExecutor.runNpm` 读流线程 +
`READER_JOIN_MILLIS` / `InstallCoordinator` 的 `streamed` 标志与 `recordConsoleHistory` /
`:domain` `consoleHistory`/`recordConsoleHistory`（读写两口径）+ 契约锚定测试同步 /
`:app` 生产实现 / `:ui` `LivePoll.kt` + `ConsoleCmdState.history` + `ConsoleScreen.HistoryRow`
（横向滚动，点一下**填进输入框而不直接执行** —— 历史里那条多半要改一改再跑）。

---

## 59. Shizuku 反射面与 R8 keep 规则必须机械化对齐，且「门必须先自己红一次」（2026-10-10，批 89）

**背景**：2026-10-09 那次真机 bug 的形态是「**保错的那条规则正好掩盖了跑错的那条**」——
`proguard-rules.pro` 保的是 `rikka.shizuku.ShizukuRemoteProcess.waitForTimeout(long, TimeUnit)`，
而真跑的路径反射的是 `moe.shizuku.server.IRemoteProcess.waitForTimeout(long, String)`。
两条规则分居两个模块（反射面在 `:platform:capabilities` 的 `ShizukuInput`，keep 在 `:app`），
中间**没有任何机械联系** —— 谁都不知道对方改了没有。外审据此提了「加守卫 test」。

**六裁定**：

1. **做门，且判据是「集合相等」不是「包含」**。三条：类名集合相等 / 方法
   `(名字, 参数表)` 集合相等 / keep 的每条签名在真类上 `getMethod` 真解析一遍。
   **双向都红**：只在 keep = 死规则（保了不再反射的类，2026-10-09 那条正是此形态），
   只在源 = 反射了没保（release 上静默失效，而 **debug 不过 R8，本机全绿**）。

2. **参数表是判据的一部分，不能只比名字**。2026-10-09 那个 bug 的名字是对的、签名是错的 ——
   只比名字的守卫在它面前**照样绿**。同理，判据 1/2 只证明两边**彼此一致**，不证明它们**对**，
   所以还要判据 3 在真类上真解析一遍（AIDL 接口在 JVM 单测类路径上真能加载，
   `dev.rikka.shizuku:aidl` 是传递依赖 —— 实测三件 `Class.forName` 全 OK）。

3. **否决「把三个 AIDL 接口 `{ *; }` 化」**（外审原提议的一半）。`{ *; }` 会让判据 2 退化成
   **恒真**，而那张显式方法表本身就是「本应用反射了哪几个方法」的**唯一文档**。
   保住表、用门让它自维护，比删掉表换一个恒真的门强。常量池扫描也否决：这里要的是
   `(名字, 参数表)`，参数表在字节里只剩描述符，与 keep 规则的源码语法对不上。

4. **解析 proguard 文件前必须剥注释行**。本文件里有一段注释**逐字引用**了 2026-10-09 删掉的
   那条 `ShizukuRemoteProcess` 规则 —— 不剥就会被当成活规则。门第一次跑出来的就是这条**假红**
   （报「保了不再反射的类」）。**注释里逐字引用的历史规则是一条会骗过解析器的活规则**。

5. **读文件的测试必须把那个文件声明成 Gradle 任务输入**。Gradle 看不见测试代码里那次
   `Files.readAllBytes`：不声明则改完 keep 规则 `:app:testDebugUnitTest` 照样 `UP-TO-DATE`、
   门**静默不跑**（2026-10-10 实测复现）。**一道永远绿的假门比没有门更坏** —— 这条与
   `check-doc-links.sh` 的「扫到 0 条也红」、nightly 的 `check-e2e-ran.sh` 是同一条纪律，
   只是这次漏在了 Gradle 的增量判定上。

6. **门必须先自己红一次才算立住**。本批反证三条（各自命中，改回即绿）：签名改 `TimeUnit` /
   整条删 `IRemoteProcess` 的 keep / 源集加一处新反射不保。这与 2026-10-09 那条
   `ArchitectureTest` 常量池门的教训同形（当时只找斜杠形态，新抄一份 `Class.forName` 照样绿）。

**边界（明写，不当已办）**：本门只覆盖 `ShizukuInput` ↔ `proguard-rules.pro` 这一对。
另两处反射不在门里 —— `:app` 的 `Class.forName(component.className)`（保的是本仓自己的
`MainActivity`）与 `:engine:node-process` 的 `Process::class.java.getMethod("pid")`
（保的是 `java.lang.Process`，平台类不参与收缩）。要一并机械化得先有「反射点 → 期望 keep」
的共享清单，当前三处形态各异，硬凑一张表只会多一处会漂的事实来源。

---

## 58. 控制台 shell 面：必须显式进模式，且 `adb` 档 = Shizuku（2026-10-09，批 88）

**背景（用户口径，逐字）**：「也需要让控制台能执行 shell」+「root 执行二进制需要输入 `su` 进入
root 模式执行，不然就是 adb」+「输入 `exit` 是退出 adb 或者 root 权限模式」+「这部分说的是控制台的」。
本项是对 **第 54 项第 2 条**（「命令范围 = 只有 npm，不加 shell」，用户 2026-10-09 早先裁定
「不用加 sh 啊」）的**修订**：shell 进控制台，但**不是**「裸命令一律当 shell」——
**要显式进模式**。原裁定不删，见「已推翻 / 已改口径」。

**七裁定**：

1. **必须有模式，不许静默挑一条**。root 与 Shizuku 是两条**不同身份**的通道（root uid vs
   shell uid），能做的事不同、留下的痕迹不同、失败话术也不同。静默替用户挑一条 = 让
   「我以为我在用 root」与「实际用的是 shell」不可分辨 —— 与 §9.3「三通道必须显式指定」
   是同一条纪律。进模式后**徽标常驻可见**：用户不该在以为敲的是 npm 时把命令送进 root shell。
   **模式是界面侧状态**（`ConsoleCmdState.mode`），不是宿主状态：宿主每次只收一条**已定形**的
   命令（`Shell(command, mode)`），它不需要知道用户是不是还在特权模式里。换个项目不必重置模式
   —— 模式是人的姿势，不是项目的属性。
2. **入口词在任何模式下都优先**（`npm`/`npx`/`su`/`shizuku`/`exit`）。否则进了 root 模式就
   **再也退不出来**（那五个词会被当成 shell 命令发给 `/system/bin/sh`，而设备上多半没有
   叫 `exit` 的程序）。`exit` 带参数即拒（它只用来退模式）。
3. **`su <cmd>` 与 `su` 是两件事**：单独一行 = 进模式；带参数 = **就地跑那一条**（不切模式）。
   两种用法都自然 —— 前者不需要用户先切模式再切回来。`shizuku` 同理。
4. **`DEFAULT` 一律拒，且拒要落一行**。拒绝路径**先落 RESULT 行再抛**：抛是因为「没跑」与
   「跑了但非零退出」是两件事（折成同一个 DTO 会让调用方无从分辨）；落行是因为用户敲完 `ls`
   之后总得看见「为什么没跑」，而不是一行 ECHO 后面什么都没有。**拒绝不碰执行体**
   （否则就是「先跑了再说」）。话术要**点名怎么进特权模式**（`su` / `shizuku`）。
   同理，默认模式下裸首词查不到 bin 时，`ERR_NOT_FOUND` 那句话也要带这半句指路 ——
   「node_modules 里没有声明 bin「ls」的包」是对的但**没用**，用户想要的从来不是某个叫 ls 的包。
5. **非零退出是结果不是异常**：命令跑了、退出了、退成非零 —— 照原样进 RESULT 行（`ok = false`）。
   把非零退出折成抛异常会让「命令的输出」与「宿主自己出错了」在界面上长得一样。
   执行体**抛错**才走异常（原文进 RESULT 行，原异常继续上抛给调用方）。
   `CancellationException` **原样上抛**：吞成一行「失败」会让协程取消变成"命令跑失败了"。
6. **超时不渲染半截输出**：`withTimeoutOrNull` 套在 `ConsoleShellRunner`（TTL 契约），超时即
   `ERR_TIMEOUT` 且**一行都不落** —— 命令没跑完，画一行「退出码 N」就是编一个没发生过的退出。
   真实现（Shizuku 的 `waitForTimeout`）自己也超时，两层并存不冲突（先到的那个说了算）。
7. **`adb` 档 = Shizuku，且只在装配层换**。`AndroidShellExecutor` 的 `ShellMode.ADB` 与 `DEFAULT`
   是同一行（`sh -c`，**应用 uid**）—— 那是它自己的口径（「设备侧已在 adb shell 内」），
   不是控制台要的。**不改那个类**：改它会动到 a11y 的输入注入那条路（`ShellInputProvider.adb`
   传的是 Shizuku 缝，不走 `ShellMode.ADB`）。两条面各要各的语义，故在 `PlatformWiring` 分流
   （`ConsoleShellExecutor`：ROOT/DEFAULT 转平台 shell，ADB 转 `ShizukuInput.exec`），两边都不动。

**另外两条落点选择**（不是裁定，是分工）：

- **`ConsoleShellRunner` 独立一件**（不从 `InstallCoordinator` 里再长出来）：shell 链与依赖树
  **无关** —— 不建事务、不占安装会话、不碰项目锁（敲一条 `ls` 不该占住全局安装会话）。
  `projectId` 只用于把输出落进那个项目的控制台环。
- **判据仍住 `:domain`**（`NpmConsoleKeys.parse` 收 mode 参数）：界面侧当场拒与宿主侧执行
  读的是同一句话，与第 54 项第 6 条同一条理由。

**顺手修的两处真问题**：

- **`ShizukuInput.shizukuClass` 只接 `ClassNotFoundException`**：JVM 单测跑 ADB 档时实测抛的是
  `NoClassDefFoundError: Could not initialize class rikka.shizuku.Shizuku`（类在、静态初始化炸），
  它**不是** `Exception` —— 那条路径会越过 `AutojsException` 直穿到调用方，用户拿到的是一个栈
  而不是一句「去装/去更新 Shizuku」。补接 `LinkageError`。**刻意不写 `catch (Throwable)`**：
  那会把 `OutOfMemoryError` 这类也折成「Shizuku 没装」，是把真故障说成用户可修的问题。
- **`NpmScriptResolver` 的纯 JS 探测拆出 `PureJsProbe`**：解析器已有 11 个函数、越过 detekt
  `TooManyFunctions` 线。拆的判据不是「凑数」—— 解析器管**怎么找到** bin（manifest 声明、
  路径逃逸、包目录扫描），`PureJsProbe` 管**找到之后它是不是 JS**（读那个文件的头几百字节）。

**未验**：真机行为（无设备，按既定纪律由用户自测）—— Shizuku 装好后 `newProcess` 那条反射链、
`su -c` 在真 ROM 上的话术、特权模式下软键盘的观感。**已知缺口**：`ShizukuExecResult` 无
`truncated` 字段 → adb 档输出被截到 4 KiB 时控制台**不会**打「已截断」（root 档有真判据）。

## 57. 面板的变更半边复用控制台那条宿主口；进度报阶段不报百分比（2026-10-09，批 87）

**背景**：§10.9 第 1 条要「安装输入行 / 旗标 / 阶段进度条」。批 81 落了清单那半截并把这三样
登记为未落，理由是"没有执行体"；批 84 把 `InstallCoordinator.runConsoleCommand` 做出来之后，
这条理由**已经不成立** —— 剩下的是没人把面板接上去。接的时候有三处要拍。

**裁定一：不新开「按 spec 装」的宿主口，面板拼出来的那一行走的是控制台同一条入口。**
新口的写法很自然（`install(projectId, spec, dev)`），代价是**第二份安装入口**：两份入口的差别
只在「谁先忘了加某道门」上体现 —— 多镜像交叉校验、磁盘/配额预检、项目锁、全局安装会话、
git 说明符拒收，每一样都得在两处各写一遍。故落的是 `HostSummary.runNpmPanelCommand(projectId, line)`
→ 同一个 `runConsoleCommand`。**这条不是为了省代码，是为了让"门禁强度不取决于用户从哪个界面
按下去"成为结构性事实**：只有一个入口时，忘了加门这件事无处发生。卸载同理，走
`npm uninstall <name>` 命令通道，不新开 `uninstall()` 口。

**连带**：拼装（`buildInstallCommand`）留在呈现层，判据（`NpmConsoleKeys.parse`）留在 `:domain`
—— 拼装规则要看得见"界面上有哪些开关"，契约不该知道这件事；而界面拼完仍要过同一份判据，
于是「面板能拼出来的东西」与「控制台能敲的东西」判据同源。界面侧调判据只为**当场告知**
（敲错的东西不往返一趟），**不是**第二份判据。

**裁定二：进度条画的是六档阶段，不是百分比 —— 这是对 §10.9 第 1 条原文的收窄。**
原文写的是「阶段进度条（packument→下载→解包→链接，**job 数**）」。实测：`InstallEvent.Progress.percent`
**全仓从无赋值**（`grep -rn 'percent *='` 零命中），`HostNodeExecutor` 起的是 vendored npm CLI
进程、reify 在它进程内是黑盒，宿主只在前后发得出三枚粗标记（`DOWNLOAD`→`REIFY`→`DONE`）。
**画一条会动的百分比条就是编一个拿不到的数** —— 用户看着它走到 90% 停住，比看着它诚实地停在
「写入 node_modules」更糟。原文那句「job 数」同样拿不到。契约原文**保留不删**，收窄理由与
替代形态写在同一段下方（与第 56 项「原文照抄」同一条纪律）。

**裁定三：「离线优先」不是「仅离线」，且 `npm-cache` 尺寸另开一条读口。**
旗标拼的是 `--prefer-offline`（先查缓存、缺了仍联网）。真正断网也要装上，靠的是缓存里恰好有
全部闭包 —— 那是 `offlineGap` 答的问题，不是这颗勾选框能承诺的。界面文案与旗标名都不许把
一个词当两件事用。尺寸那条：`cacheStorage()` 与 `storage()` **分开而不是并进去**，因为两者的
键空间不同 —— `storage()` 是「每个项目各占多大」，缓存按内容寻址、**全机只有一份**（§10.2），
按项目铺开就是同一个数字抄 N 份，而那 N 份会让人以为"删掉这个项目的缓存"说得通。只报
`content-v2` 的体积：这个数字的用途是回答"回收能腾出多少"，而回收动的正是 content-v2。

**裁定四：换项目要把阶段条与拉取游标一起归零。** `SeqRing` 的 `seq` 是**环内全局单调**的，
`drain(projectId, sinceSeq, …)` 才按 `projectId` 过滤 —— 于是沿用上一个项目的游标会**漏掉**
新项目 seq 更小的那些事件（它们对新项目是新的、对那个游标却不是），包括 `Finished`，
阶段条会永远停在「进行中」。归零不是"重头开始"，是"把这个项目还留在环里的那些事件全取回来"
（与 `ConsoleCmdState.withProject` 同一条纪律，那一处早就这么写了）。归零的**只有项目域
那两样**：草稿与旗标是用户的输入（`axios` 换个项目照样是要装的东西），`installing` 记的是
全局在途（安装会话全局互斥，换个项目看不会让它停下来）。

**没做的（如实登记）**：`update()` 至今零生产调用方（本批接的是命令通道，不是那个方法面）；
阶段条是**现取**不是常驻轮询，故通常只走到 `QUEUED`，续拉靠用户点「刷新」—— 要它自己走，
缺的是宿主侧一条常驻推送，那是独立一件事，别在 `:ui` 里塞 `while(true)` 轮询。

**边界**：真机行为未验（无设备）；输入行在软键盘下的观感随下次真机冒烟一并看。

---

## 56. 缓存回收按 lock 闭包（不叫 cache clean），且必须同时修 index（2026-10-09，批 86）

**背景**：§10.9 第 5 条那颗「cache clean」按钮。摆着两条路，语义完全不同：

- **A：`npm cache clean --force`** —— 把 `cacheDir/npm-cache` 整个删掉，npm 自己就有这个子命令；
- **B：按 lock 闭包回收** —— 只删「没有任何项目 lock 需要」的 content 条目。

**用户 2026-10-09 裁定：走 B。** 理由是 §10 整卷的离线能力（`--prefer-offline`、精选种子首装、
`offlineGap` 体检、离线 bundle 导入）**全建在这个缓存上**：全清等于把紧挨着的那颗「按 lock 重装」
按钮变成**必须联网**——用户按「清理」是为了腾地方，不是为了把自己踢下线。npm 自己没有 B 这个子命令
（`cache clean` 只有全清），故**必须新开一个 facade 方法**。

**裁定一：保留集是「所有项目 lock 闭包的并集」，不是当前项目。** 只按当前项目算会删掉别的项目
离线重装要用的包，而那种「省了空间、坏了别的项目」的后果，用户在点按钮时**完全看不见**
（他站在 p1 的页面上，毁的是 p2）。lock 读不出来的项目**点名入史**（`unreadable`）：
那些项目的包没有被保护，用户有权知道 —— 但**没有 lockfile 的项目不算**（那是「还没装过」，
不是「保护不了」，两种情况混为一谈会让每个新项目都在审计里报一次假警）。

**裁定二：不叫 `cache clean`，审计 op 名是 `cache_reclaim`。** 名字跟着语义走。审计页上
「全清」与「按闭包回收」长得一样的话，将来真接了全清就分不出来了。

**裁定三：回收必须同时摘掉指向已消失 content 的 index-v5 行 —— 这不是附赠，是半件事的另一半。**
实测（2026-10-09，npm 10.9.8 本机，五组受控实验）：

- `npm ci --offline` 走 `cacache.get.stream.byDigest`，**不看 index**：content 在就命中，
  缺就 `ENOTCACHED`（与 `NpmCacheSeedDeployer.CACHE_HIT_NOTE` 同一条实测口径）；
- 但**在线**路径读 index：index 指向一份已消失的 content 时，`npm install` 报
  `ENOENT … Invalid response body while trying to fetch`（**不是**回源重下）。

即**悬空 index 会让缓存从「没用」变成「有害」，而且界面上看不出来**（不报错到用户眼前，
只在下次联网安装时炸）。第二件事对「本来就已经悬空的 index」（系统清缓存、上次崩在半路）
同样有效 —— 那种状态下连在线安装都是坏的。`Report.indexRebuilt` 报的就是这次有没有修到；
`integrity` 为 `null` 的行是 cacache 的**删除标记**（`compact` 里的记法），**保留** ——
它不是悬空引用。

**裁定四：认不出形状的一律保留；删不掉的不计入 removed。** `integrityOf` 只认
`sha512/<2>/<2>/<124>` 且全小写 hex 这一种形状，其余（sha1 目录、路径段数不对、大写 hex）
回 null = 保留。理由：本函数的职责是回收缓存，不是打扫看不懂的东西；**删一个读不懂的文件是
「猜」，猜错的代价是别人的离线能力，收益只是几个字节**。同理，`Files.deleteIfExists` 失败
（并发占用/权限）的条目计入 `keptEntries` 而不是 `removedEntries` —— 报出来的数字必须是真发生的事。
`keptEntries` 因此会**大于**真正被 lock 引用的条目数（index 里那些「指向仍存在、只是没人再需要」
的 packument 条目刻意不清：几 KB 的小文件，清它们要重建整棵桶树，收益与风险不成比例）。
**`Report` 刻意不提供 `offlineUsable` 这类派生判断**：`keptEntries > 0` 不等于「离线可用」
（认不出形状而留下的条目一个都命中不了 `byDigest`）——想下这个结论得看 `keepCount` 与具体 lock，
那是调用方的事。

**裁定五：三颗维护按钮共用 `NpmMaintenanceAction` 枚举，回收缓存不进这个枚举。**
`prune`/`dedupe`/`ci` 形状相同（都是一次安装会话、都返回 `InstallHandle`、都过 per-project 互斥锁
与磁盘/配额预检），界面只需要知道「哪一颗在跑」，故一个枚举 + 一条 `HostSummary.runNpmMaintenance`
够用。回收缓存**返回的是一份读数而不是句柄、也不占安装会话**，塞进去会让「跑一次 npm 会话」
（几十秒量级）与「删几个缓存文件」（毫秒量级）在界面上共用一套进度语义。
**`CI` 那颗不绕过任何门禁**：走的是 `facade.ci(offline = true)`，`lockSigner.verifyOrThrow` 照旧先跑。

**裁定六（同批修的一处真错，不是附赠）：`cacheRoot` 成为「npm 的缓存在哪」的唯一一份判据。**
实测（`grep -rn "npm-cache"`）发现四处读者各拼各的、**互不相同**：喂给 npm 的 `--cache` 拿到的是
`cacheDir` 本身（少一层）、`CacacheIndex` 读 `cacheDir/npm-cache`、离线 bundle 导入落在第三个目录
（协调器 `npmCacheDir` 未传时的 `projectsRoot` 同级兜底），而 `NpmCacheSeedDeployer.cacheRoot`
自己当时是**恒等映射**（`= cacheDir`）却挂着「缓存根（cacheDir/npm-cache）」的注释 ——
注释与实现相反，正是这次分家的起点。后果不是报错而是**静默失效**：`offlineGap` 恒报缺口、
导入完 `ci --offline` 照样不命中。已全部改走 `cacheRoot`。**`--cache` 取值变化被判定为安全**：
生产上那个目录此前**基本从没被填过**（缓存从没播过种），故没有「换目录 = 丢缓存」的实际损失。

**未落（如实划界）**：`npm-cache` 那一栏的尺寸读数没进 `QuotaCard`（`CacacheIndex.contentBytes()`
有实现，今天只画 `node_modules`），故回收回执里的删/留数字取自报告本身；§10.2 写的
`cacheDir/npm-cache-seed` **没有生产部署路径**（`NpmCacheSeedDeployer` 只有测试调用方）——
已登记 backlog。

**边界**：真机行为未验（无设备）；index 修复的实测证据来自本机 npm 10.9.8，随包 npm 是 12.2.0，
桶格式同源但未在该版本上复跑。

---

## 55. 审计史另开一条读口，不并进依赖面板快照（2026-10-09，批 85）

**背景**：§10.5-2 原话是「审计日志（approve/registry 变更/lock 重签）落 App **且可导出**」。
实测复核发现前半截成立、后半截不成立，而且不是「导出没做」这一件事：

- `InstallHistory` 一直在写（`files/.autojs/install-history.jsonl`，只追加、失败也记），
  写点覆盖 install/ci/uninstall/prune/dedupe/registry/import/export 与 T1 三种放行动作；
- 但 `PackageManagerFacade` **没有任何读口**，全仓 `InstallHistory` 的非测试引用只有
  `NpmShellKit` 那一处构造 —— **生产零消费方**。用户点过的每一次批准、每一次镜像源变更、
  每一次失败安装，都落在盘上而界面上一个字也看不到。

**裁定一：另开 `history()`，**不**并进 `snapshot()`。** 两者问的是两件事 ——
`snapshot()` 答「**此刻**装了什么」（当前事实，每次现算，含目录遍历）；`history()` 答
「**过去**发生过什么」（历史事实，落盘即定，与当前状态无关）。并进去的直接后果是：每刷一次
依赖面板就把全部审计历史重读一遍，而依赖面板根本不显示它。这与批 84 把控制台输出另开
`consoleOutput`（而不是塞进 `drainEvents`）是同一条理由：**读口按「问的是哪件事」分，不按
「界面上挨着」分**。

**裁定二：读口无参，筛在呈现层。** 与 `pendingApprovals` 同一取舍。按项目问会让
`Op.REGISTRY` 那条从任何一次筛选里掉出去 —— 它改的是 `files/.npmrc`（全局），`projectId`
是**空串**，不属于任何项目，而它恰恰是审计最该看见的那一类。呈现层因此把「空串」单独
标成「全局」一档，并在筛掉失败行时明说「全部记录里共 N 条失败」。

**裁定三：`InstallHistoryEntry` 的五字段由落盘格式决定，不由界面想显示什么决定。**
`op`/`projectId`/`success`/`detail`/`atMillis` 就是 jsonl 那五个键。加字段 = 动那份**审计**
文件的形状 = 让历史行与将来行不可比 —— 呈现层要的派生（时间格式化、操作名翻人话）一律在
`:ui` 侧算。

**裁定四：`op` 保持 `String`，取值域开放；`InstallHistoryOp` 只给已知名一个锚。**
落盘侧 `opName(args)` 把**任何** argv 首词直接当 op 记（`ls`/`audit`/`update`/`run-script`…），
T1 那三个来自 `ApprovalAction.name.lowercase()`（`run_script`/`exec`/`install_script`）——
从来没人拦过它们。收窄成枚举就是**让不认识的行消失**，而审计里消失的行等于没发生过。
`InstallHistory.Op` 因此改为指向 `:domain` 那份的**别名**（`:ui` 依赖方向上看不见
`:app-service:npm`，字面量抄两份必然漂，漂的那份正好是呈现层用来分组的）。

**裁定五：审计页的入口在依赖管理页顶栏，不另立管理面板第五项。** 它记的就是依赖面那些操作
（安装/卸载/镜像源变更/审批放行），从面板直进会让人以为它与依赖管理是并列的另一件事。
三条呈现口径：**失败行不藏**（审计要能回答「用户当时看到成功了吗」）、**未知 op 原样显示**
（不编「未知操作」—— 那是把「还不认识」说成「记录有问题」）、**成败用字和颜色同时表达**
（只靠颜色，色觉障碍用户读不出这条到底成没成）。

**同批补的一条漏账**：`enqueueHeavy` 的磁盘/配额**预检拒绝**原先**不入史**
（`crossCheckRegistry` 的预检拒绝一直在记）。后果具体：用户屏幕上是一句
「项目 node_modules 已达配额」，审计页上却是「什么都没发生」—— 两份账对不上。
已补记（`opName(args)` + 失败原因原文）。

**未落（如实划界）**：§10.5-2 的「**可导出**」那半截 —— SAF 选目录 + 写文件那条通道没接，
故界面上**不画导出按钮**（按下去什么都不发生的按钮比不画更糟）。另有包大小管理页的
**动作半边**（prune/dedupe/ci 重装/cache clean 一键按钮）：配额满时那句提示今天把用户指去
**控制台**敲 `npm prune`（那条路是真的通的，批 84 的白名单里有 `prune`），但不是一键。

**边界**：真机行为未验（无设备）。

---

## 54. 控制台改做命令面，日志整体搬去「日志管理」（2026-10-09，批 84）

**背景（用户口径，逐字）**：「控制台不是放系统日志的地方，是用来执行命令的，比如 npm。或者装的一些依赖会有命令」。
当时控制台页的全部内容是 §7.3 那条 `HostSummary.console` 读口的呈现（宿主事件 + 脚本 console 输出 +
在途执行块），与 2026-10-07 批 70 新建的「日志管理」页**职责重叠** —— 第 40 项当时把控制台记成
「不动（现在不知道干啥用）」，本项就是给它一个用途。

**六裁定**：

1. **日志去向 = 日志管理，一行不少**。控制台原有的三段内容全部落到管理面板 → 日志管理：
   宿主行 → 「系统日志」段；脚本 console 行 → 「脚本输出」段；在途执行块（带「停止」）→ 也进
   「脚本输出」段置顶（它是**执行**面的东西，跟"敲命令"无关）。原「任务日志」段不动，故日志管理
   从此是**三段**（系统日志 / 脚本输出 / 任务日志，用户 2026-10-09 裁定「三个钮」）。
   **数据面一个字没动**：`ConsoleCollector`、游标语义、`stopRun`、`ConsoleSnapshot` 的字段与
   呈现纪律（游标只进不退、读失败保留旧行、丢包非零不藏）全部原样 —— 搬的是 `:ui` 的消费方，
   不是采集面。`TaskCenterScreen` 的在途执行块也原样保留（两处画同一份 `ConsoleState.activeRuns`）。
   **顺手收掉一处真重复**：`summaryText()` 曾在两屏各有一份，收进 `ActiveRunState.summaryText()` 一处；
   版式**不合并**（`TaskCenterScreen.RunRow` 是 TG `UserCell` 56dp 那种，日志管理那段是 `Cell` + 行尾钮，
   强行合并会把其中一处的版式改坏）。

2. **命令范围 = 只有 npm，不加 shell**（用户 2026-10-09 裁定「不用加 sh 啊」）。认四种形态：
   `npm <sub> [args…]`、`npm run <script> [-- args…]`、`npx <bin> [args…]`、`npm exec <bin> [-- args…]`。
   其余一律拒收并点名用户输入的那个串。本仓没有 shell，任意命令走脚本侧的 `auto.shell` 桥面
   （root/adb 三态门禁）—— 那是**另一个面**；在命令面假装支持 `sh -c` 只会让「看起来能跑、
   实际没人守」的输入进来。

3. **子命令白名单**：`install / uninstall / ci / ls / list / prune / dedupe / audit`。
   **不是黑名单** —— npm 有 60+ 子命令，`publish`/`login`/`token`/`owner`/`config`/`init`/`link`/`cache`
   要么改宿主全局状态、要么要凭据、要么在本平台没有意义（§10.6 的轻/重操作拆分只管这几个）。
   黑名单漏一个就是一条没人守的路。派发按 §10.6 原样二分：重操作走 `enqueueHeavy`（全局互斥 +
   事务 + 落位），轻操作（`ls`/`list`/`audit`）Kotlin 直读零 Node 进程。

4. **输出粒度 = 事件流 + npm 输出尾部**（用户 2026-10-09 裁定，否掉「新开真流式 stdout 事件」那案）。
   `InstallEvent` → `NpmConsoleLine` 的投影在**宿主侧一处**发生；npm 自己的输出取
   `HeavyOpOutcome.outputTail`（新字段，默认 null）。**不新开桥面事件** —— 脚本侧的事件契约
   （`bridge/js` 的 `onProgress` 等）不该因为宿主多了一个界面而改形状。
   新增 `NpmConsoleLine.ok: Boolean` 作终态行的**成败位**：失败标红由它决定，呈现层**不按文本猜**
   （`startsWith("失败：")` 那种写法会在宿主改措辞时静默失效）。**未落**：真流式 stdout。

5. **依赖提供的命令照实接线**（用户 2026-10-09 裁定「照实接线」）：`npm run` / `npx` 走 §10.3 T1 的
   `runScript`/`exec` 门禁。**未获批** → `ERR_PERMISSION_DENIED` 且审批请求已入队（话术指向**真的那一页**：
   管理面板 → 依赖管理 —— 原先写的是「能力中心的审批卡」，那是批 81 之前的位置，本批顺手改对）；
   **获批但 T1 spawn 桥未接** → `ERR_NOT_IMPLEMENTED`。两条都在 ECHO 行之后抛出，用户看得见自己敲的那行。
   **审批 ≠ 执行**：门禁只入队、不排队命令，批完要**重敲那一行**（写进了 KDoc 与界面回执）。

6. **判据唯一一份住 `:domain`**（`NpmConsoleKeys`，与批 82 `ScriptEnvKeys`、批 83 `NpmRegistryKeys` 同形）。
   两个消费方：宿主侧执行入口（`InstallCoordinator` 拿它决定这行能不能跑）与界面侧（`:ui` 输入行，
   用户敲错要**当场**被告知）。抄两份必然漂，而漂的方向最坏：界面放行的行在宿主侧被拒 ——
   用户看到的是「执行失败」而不是「你这行写错了」。**分词按空白切、不做 shell 引号解析**：
   本仓没有 shell，假装支持引号会让 `npm install "a b"` 产生「看起来对、实际是另一个包名」的结果，
   静默错比报错难查得多。同理 `packageSpecsIn` 只处理裸包名（旗标带值的形态会误读，**已写进 KDoc**），
   且**真 argv 一个字不改地透传**给 npm。

**门禁强度不取决于入口**：控制台敲 `npm install axios` 与依赖面板装同一个包，走**同一套**装前多镜像
交叉校验（§10.5-1）、磁盘预检/配额/项目锁/全局安装会话。入史那一栏只记**子命令**而非完整 argv ——
审计表长期留存，而用户手敲的 argv 可能夹着凭据形态的参数（`--//registry/:_authToken=…`、`--otp`）。

**读口不经桥**：`HostSummary.runNpmCommand`/`consoleOutput`（`:domain` 新 DTO
`NpmConsoleHandle`/`NpmConsoleSnapshot`/`SequencedConsoleLine`/`NpmConsoleLine` + 枚举 `NpmConsoleLineKind`）。
控制台是**宿主自己的界面**，桥面是脚本侧的面 —— §7.3 的方法表**一条不加**，与批 81 依赖面板同一条理由。

**同批修掉一条判据错**：`ScriptPaths.PROJECT_ID` 原放行 `.`，于是 `a.b` 是合法项目号，而
`projectsRoot.resolve("a.b")` 没事、`resolve("..")` 却正好**跳出项目根** —— 判据收掉 `.`
（`NpmConsoleKeys.rejectProjectId` 的话术跟着改，否则用户按话术写一个 `a.b` 却被拒）。

**未落**：真流式 stdout、shell 引号解析、命令历史持久化（不落盘、不跨进程重启保留）、
白名单外子命令、T1 spawn 桥本体（`scriptExecutor` 仍缺）、命令的**取消**
（`NpmConsoleHandle` 与 `InstallHandle` 刻意分开 —— 那个是「一次安装会话」的账，这个是「用户敲了一行」
的账，塞进同一个句柄类型会让 `cancel()`/journal 的语义含糊，故没有 `cancel()`）。
**边界**：真机行为未验（无设备）。

## 53. 镜像源管理的归属、粒度与解析链，以及 registry 的两处断链（2026-10-09，批 83）

**背景**：管理面板四项入口只剩「镜像源管理」还是 `toast?.show("镜像源管理尚未开放")`。
backlog 批 82 那条追记要求**先裁归属，别照抄「环境变量」的形状**（那是脚本面，这是 npm 面）。
探查时发现两条**比 UI 占位更严重**的断链，一并收口。

**五裁定**：

1. **归属 `:app-service:npm`**。它本来就在管这件事：`NpmConfigKey.REGISTRY`、项目 `.npmrc`
   的写入口 `config()`、`InstallHistory.Op.REGISTRY` 审计键、交叉校验的首选读取 —— 全在这个模块。
   搬去 `:app` 或新开模块就是第二份 registry 判据，而批 82 的教训正是「判据抄两份必然漂」。

2. **粒度：全局一份**（用户 2026-10-09 裁定）。对应 §10.2 三层链的 `files/.npmrc`(userconfig) 那一层 ——
   **该层此前零实现**（全仓只有 `NpmProjectLayout.npmrc(projectId)` 一个路径函数）。
   否掉的另两案：只做项目级（用户要的是「一次改完所有项目」）、全局缺省 + 项目覆盖的编辑面
   （项目那层仍可手编 `.npmrc`，界面代管它就要处理"项目覆盖了全局"的显示歧义，收益不抵成本）。

3. **解析链两层：项目 `.npmrc` → 全局 `files/.npmrc` → 出厂官方**，落在
   `InstallCoordinator.resolveRegistry` 一处。**校验的首选与安装的那家取同一个值** ——
   这是本批真正的交付物，UI 只是它的呈现面。

4. **判据唯一一份住 `:domain`**（`NpmRegistryKeys`，与批 82 `ScriptEnvKeys` 同形）：
   `canonicalize` 与 `reject` 同时被 `NpmRegistryVerifier` 的缝边界与 `:ui` 的输入校验调用。
   `NpmRegistryVerifier.OFFICIAL`/`MIRROR` 改成指向它的**别名**，URL 字面量从此只有一份。
   `:domain` 此前零 `java.net` 用法，本批是**第一处**（`java.net.URI` 只做形态判定，不涉 IO，
   `ArchitectureTest` 的禁令面是 `android..`/compose/awt/swing 与 `com.autoscript.bridge|platform|engine`，
   不受影响）。

5. **`HostNodeExecutor` 不再无条件注入 `--registry`**：构造参数 `registry: String` 改成
   `registryOverride: String? = null`，null 即不注入，让 npm 自己按 `prefix/.npmrc` → userconfig → 出厂解析。

**两条断链（实测证据留档，这是本批比 UI 占位更值得留的东西）**：

- **断链 1 —— 生产环境的 `--registry` 永远指向官方**：`HostNodeExecutor.registry` 缺省
  `NpmRegistryVerifier.OFFICIAL` 并写进 argv，而**全仓唯一的构造点** `AppShellKit.wireNpmExecutor`
  从不传这个参数 → 用户经 `setRegistry` 设的镜像对真实安装**毫无影响**。
- **断链 2 —— 项目 `.npmrc` 根本没被 npm 读到**：`prepareWorkDir` 只拷 `package.json`/`package-lock.json`，
  不拷 `.npmrc`；而 `runNpm` 用 `--prefix workDir`。

  本机用 **npm 12.2.0**（与仓里 vendored 同版本，`node-runtime-build/VERSIONS.env`）实测：

  | 场景 | 生效 registry |
  |---|---|
  | cwd 有 `.npmrc`、`--prefix` 指向别处 | **`--prefix` 那个目录的 `.npmrc`**（cwd 的被忽略） |
  | cwd 有 `.npmrc`、不给 `--prefix` | cwd 的 `.npmrc` |
  | 都不给 | 出厂官方 |
  | `--registry` 显式给出 | 赢 `.npmrc` 的 `registry=` 键（但文件仍被读，`@scope:registry` 等键照常生效） |

  即 **`--prefix` 一旦给出，npm 的项目级配置就只看 `prefix/.npmrc`**，cwd 不再参与。
  生产两处都不满足 → 项目 `.npmrc` 是死配置。

- **两条的净效果**：`setRegistry`/`config()` 的写入侧**生产上是空转**；唯一真读项目 `.npmrc` 的是
  交叉校验的 `readRegistryFromNpmrc` —— 于是**校验的首选**与**实际安装的那家**可以不是一家。

**顺带收口的写法裁定**：

- **全局 `.npmrc` 整文件重写，不做行级 append**（与 `InstallCoordinator.config` 写项目 `.npmrc` 同款，
  两处共用 `NpmrcFile`）：这是用户手编的文件、npm 自己也会改它，行级追加会让同一个键出现两行。
- **写入侧存原样（只 trim）不规整化**：规整化会丢 query，自建网关用 `?token=…` 的凭据会被静默剥掉 ——
  表现为「保存成功」之后永久 401。规整化只在**判据侧**（`canonicalize`，给交叉校验比对用）。
- **空输入 = 删键（恢复出厂）**，不是写一个空值行 —— 后者让 npm 拿到空 registry 而每次安装都失败。
- **`--registry` 与 `--userconfig` 两者都给、不二选一**：实测 `--registry` 赢 `registry=` 键，
  但 `--userconfig` 指的文件仍被读（`@scope:registry`、proxy、cache 等键都靠它）。
- **审计行 `projectId` 传空串**：全局变更没有项目；改 `InstallHistory` 的行格式会动审计契约，
  空串在审计页上正好读作「全局」。**不是笔误。**
- **`npmArgv` 与 `prepareWorkDir` 提为 public 而非 internal**：Kotlin `internal` 跨模块不可见，
  而 `:app` 的装配测试正是「`--registry` 不该再出现」这条不变量的最重要消费方。纯函数，无状态风险。
- **`ManagementBackHandler` 的 `else ->` 改成显式枚举分支**：留 `else` 会在加新子页时静默吞掉返回键
  （新页按返回 = 把日志管理关了，而不是关自己）。

**未做（明确划界）**：首启引导的 registry ping 探测与镜像候选表（§10.9 第 6 条）、审计页
（`InstallHistory` 仍无 UI 消费方，本批只保证写进去）、`proxy`/`cache-retention` 两个 `NpmConfigKey`
（桥面本来就没有入口）、`NpmServices.registryOf`（该成员全仓零引用，本批不模仿也不顺手删）。
**真机面**：镜像源在真机网络下能否连通（企业内网/自建 registry 的证书与代理）、`--userconfig`
在 Android 上的路径可达性 —— 要装包才验得到，归入真机验证积压。

---

## 52. 脚本全局环境变量的归属、生效时机与保留前缀（2026-10-09，批 82）

**背景**：管理面板批 46 画了四项入口，其中「环境变量」与「镜像源管理」两行一直是
`toast?.show("…尚未开放")` 占位。查 12 卷设计：**「环境变量」这一条在契约里没有对应条目**
（`grep -rn "环境变量" docs/design/*.md` 只命中 §11 的 token 段与 §13 的 apksigner 口令段），
所以本项不是接线既有契约，而是**新增契约** —— 先写进 §8.1 再动代码。

**归属裁定**：管理面板的「环境变量」= **给脚本用的全局环境变量**（用户口径原话
「是给脚本的全局环境变量」）：用户编一组 KV，**所有脚本执行时注入脚本进程的 `process.env`**。
不是宿主的、不是 npm 的、不是构建期的。

**拍板四条**：

1. **全局一份，不做按项目覆盖**。项目维度留给将来真有需求时再加；现在加等于先造一个
   「每个项目各自一套、界面要能切」的复杂度，而用户口径就是全局。
2. **每次 spawn 现读，不装配期定死**。`NodeEngineConfig.scriptEnv` 的形态是
   `() -> Map<String, String>` 而不是 `Map` —— 改完**下次执行即生效**，不必重启 App。
   装配期快照 = 用户在界面改完却不生效（本仓反复点名的坑）；与本仓「读口现取不缓存」
   同一条纪律。代价是每次 spawn 多一次内存表读取（`FileScriptEnvStore` 内存视图，无 IO）。
3. **不注入 npm 安装会话进程**。`HostNodeExecutor` spawn 的 npm CLI 是**宿主工具进程**，
   不是用户脚本；零 spawn 门禁（§11）的注入面越窄越好。这份表只进用户脚本的
   `NodeProcessEngine` 路径。
4. **拒绝保存 `AUTOSCRIPT_` 前缀的键**，保存时当场报错并**点名那个键**
   （`ScriptEnvKeys.reject`，`:ui` 与落盘实现共用这一份判据）。理由是实证：
   `AUTOSCRIPT_BRIDGE_TOKEN` 是宿主签发的一次性桥凭据（`main.cpp` 用它做 hello/ACK，
   认证后 `unsetenv`；JS 侧 `bootstrap.ts` 认证成功后 `delete process.env.AUTOSCRIPT_BRIDGE_TOKEN`），
   用户覆盖它 = 伪造桥身份或自断桥。spawn 时宿主键**后写**再兜一次底（顺序即契约，见 §8.1）——
   两道防线，写入侧拦住是"告诉用户"，spawn 侧兜住是"用户绕过界面改盘上文件也伤不到桥"。
   前缀判据**大小写敏感**：宿主键全大写，`autoscript_foo` 不撞任何宿主键，拦它属过度收窄。

**存储与分层**：契约（`ScriptEnvStore`/`ScriptEnvEntry`/`ScriptEnvKeys`）住 `:domain`
（`:domain` 零文件 IO，实测无 `java.nio.file.Files`）；落盘实现在 `:app-service:script-repo`
的 `core` 包（`FileScriptEnvStore`，jsonl 追加 + replay 收敛 + 最后半行容忍 + 值经 JSON 编码，
与 `tasks.jsonl`/`approve-ledger.jsonl` 同族纪律）。**不持有常开 channel**：这份表由用户点击
驱动（写入频率以「次」计），为它常开一个 `FileChannel` 换来的是「谁负责关」这个新问题
（`AssembledShell.close` 关的是意图日志/档案/任务注册表，本表不归壳）—— 改成每次追加现开现关
+ `force(true)`，语义一样（返回前已落盘），少一个生命周期。

**接线路径不碰装配链**：`scriptEnv` 在 `AppShellApplication` 的 `engineFactory` 闭包里捕获
store 后传入，`AppShell`/`AppShellKit`/`AssembledShell` **零改动** —— 那三处 `assemble` 有
12/20 个测试调用点，且 `LongParameterList` 已在 baseline 里，加参数是纯噪声。

**呈现面不冒充**：读失败**保留已读到的那份**并带原文（一次瞬时 IO 失败不该把用户编好的表
显示成空表）；键名不合法只进 `opError` 且**草稿留着**（清掉等于把用户打的字吞了）；
空串值是**合法值**且与「没设」在列表上是两条不同的事实（渲染成 `KEY=` 而不是留个洞）；
写失败**不清表**（宿主拒绝时先改表会出现「列表上没了但盘上还在」）。

---

## 51. 依赖面板的读口形态与审批决定的生产落点（2026-10-09，批 81）

**背景**：§10.9 第 1/2 条（依赖面板、审批卡）在契约里一直是 P0，而 §11.3 第 6 条把它记成
「UI 未排期」。实测不是排期问题，是**结构问题**：`PackageManagerFacade.resolveApproval` 与
`pendingApprovals` 全仓**零生产调用方**（只有测试调），`ApprovalLedger()` 的 `store` 缺省 null
（重启即蒸发 + `requestId` 从 `apr-1` 重来与历史票碰撞）—— 即「审批链永远停在 PENDING、
`runScript`/`exec` 永远过不了那道闸」。本项记四条口径。

**拍板四条**：

1. **读口一次现取**全量**快照，不按项目问**。`PackageManagerFacade.snapshot(): NpmPanelSnapshot`
   （`:domain`）= `projects`（按项目号排序）+ `pendingApprovals`（**全局**队列）。
   为什么不是 `snapshot(projectId)`：① 面板要能回答「我有哪些项目」，只列一个会让人以为其余
   项目不存在（与 §9.5 能力中心「列全量能力」同一条理由）；② 审批队列本身没有项目维度
   （`resolveApproval` 不带 projectId），按项目筛会让用户**漏掉别的项目上等着的那张卡** ——
   那正是本项要修的缺陷；③ 按项目问 N 次 = N 次目录遍历 + N 套失败语义。
   项目列表取 `storage()` 的键（= 项目根下的目录）而不是 lockfile 的键：装了依赖但还没落 lock
   的项目也有一棵 `node_modules` 要管，目录是超集。
2. **读口不经桥**。桥面（§12.2 的 `npm` 命名空间）是**脚本侧**的面，受 §10.5 人机分离约束；
   依赖面板是**宿主自己的界面**。故快照 DTO 住 `:domain`（`:ui` 只依赖 `:domain`，够不到
   `:app-service:npm` 的实现类），经 `HostSummary.npmSnapshot()` 现取。**桥面方法表一条不加**
   （仍 14 条）。
3. **`resolveApproval` 的生产落点只有一处**：`HostSummary.resolveNpmApproval(requestId, approve)`
   → `AppShellApplication` → facade。脚本侧只能 `requestApprove` 入队（桥面没有 resolve，
   `NpmBridgeHandlerTest` 已钉死这条），决定必须由 UI 回调带下来。本项落地前那句「人机分离」
   是**纸面约束**（没有"机"的那一侧）；现在两侧都在。
4. **DTO 命名 `NpmPanelSnapshot`（不叫 `NpmSnapshot`）**：`app-service/npm` 里已有一个
   `NpmSnapshot`（§10.9.4 高信任快照导出/验签的**执行体**），两个同名类会让
   `InstallCoordinator` 的 import 撞车（实际编译期已撞）。**名字先到先得，后者改名** ——
   那个类与 §10.7 的 `SnapshotRef` 是一条线上的，改名会牵动契约措辞。

**呈现面的三条「不冒充」**（落在 `NpmState`/`NpmScreen`，有单测钉）：尺寸没量到（`storage == null`）
**不画成 0%**（那是在说「这个项目不占地方」）；读失败**保留已读到的那份**并带原文，不抹成空清单；
配额黄/红两档直接读 `NpmProjectSnapshot.overQuota/quotaWarned` —— 判据只有 `InstallConfig` 一处，
呈现层不自己写死 512MB/80%（写第二份就会与真拦人的那份漂移）。

**仍未落（本项不涉及，已写进 §10.9）**：安装输入行/旗标/阶段进度条、依赖树、`hasInstallScript`
前置告警、白名单放行通道。

**仍未接线的旁证（记下来，别当成已做）**：`update`/`exportSnapshot` 有实现、**零生产调用方**，
也不在桥面 —— 它们要等「依赖面板的变更半边」与「打包向导」；`storage()` 同样是宿主内部读口。
这三条**不加进桥面**：§10.7 的 facade 是内部面，桥面是脚本面，两件事。

---

## 50. `child_process` 拦截 shim 的落位、注入与「落不上就不注入执行体」（2026-10-09）

**背景**：§10.11 P0 与 §10.12 末行把「安装会话强制注入 child_process 拦截 shim」写成 P0 承诺，
并给了 CI 金标准（child_process 替换为 throw 的 harness 里跑全命令矩阵必须全绿）。此前它一直
「未落」—— `--ignore-scripts` 只挡 lifecycle 脚本，挡不住**非脚本** spawn，而那条漂移在本平台上
表现为**静默失败**（装完了但东西不对，无人报错）。

**拍板四条**：

1. **shim 本体住 classpath 资源（`app-service/npm/src/main/resources/.../npm-spawn-gate.cjs`），
   落位到 `filesDir/.autojs/`**。不随 npm 素材树：素材树受 `npm-manifest.json` 逐件摘要对账，
   往里塞宿主自己的文件会让「清单与 APK 文件集合不一致」每次启动必红 —— 那是给别人的账本记自己的账。
   不写成 Kotlin 字符串常量：它是要被 Node 真加载的代码，落成真文件才有人读得懂、也才能被单测**真跑**。
2. **注入走 `NODE_OPTIONS=--require=<落点>`，追加不覆盖**。父环境里那份是别人的设置，
   为装自己的守卫把别人抹掉是越权；`--require` 对 `-e`/脚本/npm 自己 fork 的 node 子进程都生效，
   覆盖面比单点 argv 注入宽（argv 那条路在这里本来也走不通 —— npm 的 argv 是它自己的）。
3. **落位失败 = 不注入安装执行体（fail closed）**。它是 P0 承诺面，静默降级成「装是能装、守卫没了」
   正是那条风险本身。原因原文进 `AssembledShell.npmCliFailure`，不吞。缺省资源永远在 classpath 上，
   故这条分支由 `npmGateDeploy` 注入缝（装配参数）在测试里够到。
4. **被拦时的错误码按 shim 播报折成领域码**（`ERR_NPM_SPAWN_BLOCKED` / `detached:true` →
   `ERR_PERMISSION_DENIED` / `fork` → `ERR_NOT_IMPLEMENTED`），原文进 detail —— 而不是折成一句
   「npm 退出码 1」，否则脚本与 UI 无从判定「是守卫拦的」还是「是 npm 自己失败的」。

**边界（写死，不许当它是沙箱）**：本 shim 是**不变量守卫**，不是安全边界 —— 已获批的脚本可以
`delete require.cache[require.resolve('child_process')]` 后重新 require 拿到未打补丁的模块。
真正的对抗面是审批（§10.5-2 人机分离）与 T1 会话的最小 CapabilityMask（§10.5-4）。

**金标准落成 `NpmSpawnGateMatrixTest`**（三条：门禁注入下 install/ls/dedupe/prune/uninstall/ci 全绿；
不注入也全绿的反向变异；`npm run` 在门禁下确实被拦且**无产物**），已登记进
`check-e2e-ran.sh` 的 nightly 验尸清单（本机没 npm 时跳过是对的，CI 上恒真跑）。

**仍未落（本项不涉及）**：T1 spawn 桥本体 —— stdio 假管道、pgrp 杀树、node-shim PIE 与 PATH 注入。


## 已拍板（原 §18 全部九项：第 1–7 项 2026-09-26、第 8/9 项 2026-09-25；外加后续新增编号项）

2026-10-08 拍板（批 79：`lockKey` 生产接线；协调者按 §11.3 第 3 条已拍板的落点实施）：

49. **应用密钥落 Android Keystore，取钥判定是 get-or-create 且「取不动」绝不静默重建**（批 79）：
   - **接上的是什么**：`LockSigner` 的签/验与单测从 2026-10-01 就在，但生产装配
     `lockKey = null` → `npm ci` 不验签直接走（§11.3 第 8 条自记）。本项把钥匙面接上，
     **不改契约语义**（仍是 HMAC-SHA256、`v1 <hex>` 落盘格式、原子写）。
   - **落点**：`:app` 装配层 `LockKeyStore`（2026-10-01 backlog A1c 已拍板的落点）。
     `HmacKeys` 接口是**唯一碰 `android.` 的面**，`resolve` 与单测纯 JVM —— `:app` 无
     Robolectric，故判定必须与 Keystore 调用分开才测得到（同 `AndroidScreenGate` 的分法）。
   - **判定：只有「别名下真没有这把钥匙」才新建**。三种「取不到」各有各的失败方向，混成
     「建一把新的」会把它们全变成同一种灾难：锁被换过 / 库失效
     （`KeyPermanentlyInvalidatedException`）→ 新签名盖住旧签名，用户看不出「这份 lock
     曾被换过」；Keystore 整体不可用（锁屏态、厂商 ROM、`loadStore` 抛）→ 重建会把**所有**
     项目已有签名变成一律验不过。两条都是**显式失败**比「换把钥匙继续」诚实。
   - **别名带版本号**（`autoscript.lock.hmac.v1`）：落盘格式的 `v1` 前缀是将来换 ECDSA 的
     兼容开关；换算法时**换一把新密钥**，否则「v1 签名验不过」与「算法换代」诊断上分不开。
   - **装配期就取一次钥匙**，且**取不到不掀翻装配**：npm 只是能力之一，为一把钥匙让整个壳
     装不起来不成比例。该次传 `null` = 不装 lock 防线（`ci` 不验签直接走），原因原文进
     `AssembledShell.npmLockKeyFailure` —— **不吞**，与 `npmCliFailure` 同一条纪律。
   - **为什么必须装配期取**：推到用户第一次 `npm ci` 才炸的话，用户看到的是一条验签失败，
     **分不清是 lock 被换了还是钥匙取不动** —— 这两件事的处置完全不同。
   - **仍不改变 §11.3 第 3 条的性质**：它是**本地信任锚**，只证明「这份 lock 是本机签过的」，
     不是跨设备/跨用户可验证的来源证明。密钥丢失 = 显式安全降级失败，不静默放行。

2026-10-08 拍板（批 78：jsonl 写入侧删除 + 保守档放回 `SCHEDULER_WRITE`；用户裁定）：

47. **意图日志的 jsonl 回落已删（fail closed）+ 保守档放回 `SCHEDULER_WRITE`**（批 78，两件独立的事）：
   - **① SQLite 打不开 = 装配失败，不回落**。第 46 项里「打开失败如实回落 jsonl」那条**作废**。
     理由：回落那份 `JournalFileStore` 的 `runId` 分配（`max+1`）与「锁内先查后写」的幂等锚点
     **都以单写者为前提**（第 46 项自己写的锚点价值来自 SQLite 的**唯一索引**强制，jsonl 那份
     只是单写者下的自觉）。打不开就回落 = 把「调度坏了」伪装成「调度还在」，与 `images`/`dialogs`
     那条「缺件不伪造」纪律**同向但更严** —— 那里是明说降级，这里降级会连带失去锚点。
     落地：`JournalFileStore` 及其 14 个用例删除；`AppShellKit.intentStore` 由**可选改必填**
     （缺省值就是那个回落）；`PlatformWiring` 去掉 try/catch 与 `fallbackReason` 记账。
     **契约测试未缩水**：`InMemoryIntentStore` 落在 `:domain` **自己的 test 源集**（不是 `:app`
     或 `:platform`），故 16 例规格在任何机器上都不被 `assumeTrue(sqlite3)` 挡掉 —— 放 `:platform`
     会退回批 77 之前「没 sqlite3 就不跑」的形态。老设备 `intent-log.jsonl` 的**一次性导入仍可用**
     （读的是**格式**，纯 `:domain` parser，不依赖写入方）。
   - **② `SCHEDULER_WRITE` 放回保守档**（`UNKNOWN_DEFAULT` 由减三位改为减两位）。收走它的理由
     与第 45 项自写的「同 UID 脚本可直接往注册表追加一行即可绕过」是**同一条事实** —— 掩码收窄
     挡不住恶意脚本，只挡老实脚本。而第 45 项已裁定来源分级**不做**，于是「待来源元数据接入后
     恢复」这条路已关，留着这一位等于挂一句永远兑现不了的承诺。跨脚本那两位**不动**（其判据
     不依赖来源分级，且 `CrossScriptAuthorizer` 目标侧比较有独立防护价值）。
   - **③ 实测推翻了我自己的推荐**：只放掩码是**零收益** —— `authorizeCreate` 无条件要求
     `CROSS_SCRIPT_CONTROL`，掩码含 `SCHEDULER_WRITE` 仍被拒。故一并把该闸拆为二分：
     caller 的项目号（**由认证点从 lease 装填，非 wire 字段，脚本改不了**）与目标相同 → 自建
     放行；不同 → 走跨脚本全套；**空串 = 不知道 → 走跨脚本那条**（fail closed，不套默认项目名）。
     与批 75 把 `engines.stop/status` 的目录级要求降到 `NONE`、判据下沉进 handler 是**同一形状**。
   - **行为变化（真机口径）**：脚本可用 `auto.workManager.*` 建/删/列**自己项目**的定时任务，
     与批 74 的现行行为一致；替别人建仍 `ERR_PERMISSION_DENIED`；`engines.exec` 仍拒（目录要
     `CROSS_SCRIPT_CONTROL` **且** `SCHEDULER_WRITE` 两位，只放后一位不改可达性）；任务中心 UI 走
     `HostSummary.registerTask`、**不经桥面掩码**，行为不变。

2026-10-08 拍板（批 78：A11 —— run 终结按 runId 收口连接资源）：

48. **会话资源的收口点补在 runId 侧**：批 75 的 `ConnectionResourceRegistry` 挂在**桥连接**上
    （abort / dispose / EOF 三条路都收），但脚本若把桥 socket fd 继承给子进程，脚本主进程死了
    **不产生 EOF**，连接不结束 —— 挂在上面的进程级资源（投屏会话：`VirtualDisplay` +
    `mediaProjection` 前台服务）就没有撤销点。现场形态：通知栏一条「正在投屏」却没有任何脚本在跑。
    - **判据与映射住在接入端**（`:bridge:java` 的 `NewlineFrameServer`），因为连接号在那里发出
      （`connectionIds.getAndIncrement()`），而 runId 是**认证之后**才与它绑定（`authenticate`
      用 lease 装填）。`RuntimeController` 自始至终只认 runId；把映射塞进 controller 会要求它替
      接入端记账，两处各记一份必然错位。
    - **装填点**：认证成功后、**任何业务请求之前** —— 更早时 runId 尚不存在，晚了就有
      「run 已终结、映射才装上」的窗口。`dispose` 里条件摘除，不留悬挂索引。
    - **刻意只收资源、不关 IO**：run 自然结束时连接可能还在排空尾帧（§7.5 EOF 排空语义），
      硬关 socket 会把「脚本正常跑完」误走成硬撤销。收口目标是「脚本没了，投屏不该继续挂着」，
      与连接何时结束是两件事。
    - **接在全部六条 run 终结路径上**：`stop` / `killRun` / `settleDone`（自然退出）/
      `settleKilled` / `killAll` / `forceStopAll`。少接一条 = 那条路上脚本崩了投屏还挂着。
      （`forceStopAll` 是实现时漏了、写测试时才发现的 —— 那是「应用被杀/测试收口」那条路。）

2026-10-08 拍板（批 77：§8.5 意图日志落 SQLite 的架构选型；协调者裁定）：

46. **意图日志落 SQLite：搬接口，不给模块加 Android 依赖**。契约 §8.5 从第一天写的就是
   「append-only（**SQLite**，启动即回放）」，而生产一直跑 `JournalFileStore`（jsonl）。
   两条出路里**选前者**：
   - **选**：`IntentStore` 接口自 `:app-service:scheduler`（纯 JVM）**迁到 `:domain`**，
     SQLite 实现落 `:platform:system`（本就带 Android 依赖与 `SQLiteOpenHelper` 先例）。
     代价 = 一次跨模块契约迁移；收益 = **纯 JVM 可测性保住**（SQLite 语义层的单测跑在宿主
     `sqlite3` CLI 上，不需要设备），且依赖铁律 `:platform:*` → `:domain` 不反向仍成立。
   - **不选**：给 `:app-service:scheduler` 加 Android 依赖 —— 那会把这个模块从
     `autoscript.jvm` 变成 android-library，丢掉它的全部纯 JVM 测试位。
   - **不新增 Gradle 模块**：模块数 17 是从 `settings.gradle.kts` 的 include 派生的数字
     （`:domain` 的 `ModuleGraphTest` 守着，文档里写了就必须相等），新增模块 = 动冻结面。
     故 SQLite 实现直接住既有的 `:platform:system/persist/`。
   - **两份实现跑同一套契约**：`:domain` 新增 `testFixtures` 源集放 `IntentStoreContract`
     （抽象类，子类只提供 `open()`），jsonl 与 SQLite 两侧继承同一套。**这不是洁癖** ——
     本批的幂等锚点缺陷（见下）就是这套共享测试抓出来的。
   - **幂等锚点 = 一个部分唯一索引**：`ux_nonce ON intent_log(run_nonce) WHERE outcome IS NULL
     OR outcome <> 'INTERRUPTED'`。写成两个索引（`IS NULL` / `IS NOT NULL AND <> 'INTERRUPTED'`）
     **覆盖不重叠**，「同 nonce 已 COMMIT 之后再直投 START」两条都不拦 —— 共享契约套件里
     `同 nonce 已 COMMIT 拒绝直投` 先红才逼出单索引写法。
   - **迁移带原 runId，可重入，验完才归档**：`runId` 与 `run-archive.jsonl` 的
     `EngineRunLink.intentRunId` 是**同一批号**，新库从 1 发号会让新意向与历史 link 同号异义
     （`recordsOfIntent(3)` 把上辈子的执行当成这次意向的历史）。导入包在一个事务里，成功且
     验完才把 jsonl 改名 `intent-log.jsonl.migrated`（不删）；失败**响亮失败**并回落 jsonl，
     老日志原样在原处。
   - **打开失败如实回落 jsonl**：原因经 `AppShellKit.IntentStoreChoice.fallbackReason` 随装配
     日志输出。与 `images`/`dialogs` 同一条「缺件不伪造」纪律 —— 回落是明说的降级，不是静默换引擎。
   - **本批显式排除：保留期 / 清理策略**。日志仍**只追加、从不清理**，体积随 run 数线性增长
     （每次 run 恒定两条行，已无冗余可压）。老终态行、老 nonce 能否丢会直接动到 §8.5 的幂等
     锚点（丢了老 nonce = 重投会重放副作用），**属另一件事，本批不碰、未拍板**，入池 backlog。

2026-10-08 拍板（批 76：来源分级裁定不做；用户裁定）：

45. **不做「内置 / 自写 / 第三方 / 打包」的来源分级**：四档枚举（`TrustTier`）与注入缝
   （`TrustTierResolver`）**保留**，但**不接真来源元数据**，只作**记账词汇** ——
   生产唯一可达档仍是 `UNKNOWN`（保守档 A，见第 43 项），本裁定**不改任何掩码、不改任何代码**。
   - **理由：来源分级不产生防护。** §11.1 第 1 条那条事实（每脚本一进程但**同 UID 同权**）意味着
     脚本可绕开桥直接读写宿主私有目录、连别的 socket、杀别的进程。任何只写在
     `CapabilityMask` 上的「来源限制」都挡不住恶意脚本，只挡得住老实脚本。
     具体例：保守档 A 收走 `SCHEDULER_WRITE` 让脚本建不了定时任务，而任务注册表就是
     `filesDir/.autojs/intent-log.jsonl`（启动即回放）—— 同 UID 脚本直接往那个文件追加一行即可绕过。
     （此例为原理推论，未实测。）
   - **它原本能买的两样都已另有来源**：① 审计归因 —— 已由 A10 的 `engineRunId → projectId` 覆盖，
     不需要来源档；② 保守缺省 —— 那是**缺省**的价值，单档（全量 + 保守缺省）同样具备。
   - **副产品，如实记账**：`CrossScriptAuthorizer.authorizeTarget` 的第二半判据
     （调用方掩码必须**覆盖**目标掩码）在单档现实下**恒真** —— 所有执行拿同一张掩码，覆盖关系永远成立。
     真正拦人的只有前半（调用方要有跨脚本位），而它与来源无关，属**执行间正确性**。
     该半条判据**保留**（零成本，且是唯一档真的分化时的现成接缝），但台账不再声称它在生效。
   - **§11 同步范围**：§11.1 事实 3 与「仍未实现」bullet 改成「经裁定不做」+ 理由；
     §11.2 T5 的残余、§11.3 第 1 条尾句同批改。**§11 冻结矩阵四档（都写全量）原文一字未动** ——
     本裁定是「不接元数据」，不是「把四档改成窄掩码」。
   - **不改的**：`TrustTierMasks.UNKNOWN_DEFAULT`（保守档 A）与第 43 项全部内容原样有效；
     本次**不放开** `SCHEDULER_WRITE`（放开它属另一件事，不在本裁定内）。
   - 流水与验证见 [`2026-10-08`](log/2026-10-08.md) 批 76。

2026-10-08 拍板（A5 第一阶段落地 + §13/§16 的 306s 口径订正；用户裁定）：

43. **无来源证据档（`TrustTier.UNKNOWN`）的缺省桥面掩码 = 保守档 A**：全量减
   `CROSS_SCRIPT_CONTROL`、`CROSS_SCRIPT_OBSERVE`、`SCHEDULER_WRITE`。
   设备/自动化面（a11y / screen / dialogs / npm / floatingWindow / shell / …）保持全量不动。
   - **这是 §11 冻结矩阵之外新增的一档，不是把「第三方」改成窄掩码**：原矩阵四档
     （内置/自写/第三方/打包）都写全量，没有「无证据」这一档。本裁定补的是这一档。
   - `engines.stop` / `engines.status` 对**自己**任何掩码都放行、对**他人**要对应跨脚本位；
     `workManager.create` / `cancel` 要 `SCHEDULER_WRITE` —— 故脚本默认不能再自建定时任务，
     这是**已知功能回退**，待来源元数据（`TrustTierResolver`）接入后由分级恢复。
   - `PACKAGED` 档同样落保守档（无验证配置时不自动升全量），失败方向仍是「拒」。
   - **不做全局绕过**：`ScriptAuthorizationPolicy` 是注入缝（按 `projectId` 分级），
     不是三个全局布尔开关；`capabilityMask` 便捷覆盖只给测试/受信直投路径用。
   - **掩码只约束桥面命名空间**：不限制 Node 内建 `fs`/`http`，不是沙箱，也不改变
     设备侧三态门禁（系统层给不给仍由 `PermissionFacade` 现问系统；两张目录是两个枚举）。
   - **单一决策链（杀 TOCTOU）**：授权快照在 `RuntimeController.start` 的同一把锁下**算一次**，
     随后 池请求 → `EngineRunRequest` → `RunIdentityIssuer.issue` → lease →
     `AuthenticatedRunContext.capabilityMask` 搬的都是**同一份对象**，下游不重算；
     目标侧授权（`CrossScriptAuthorizer`）读的就是签发出去的那一份。
   - **跨脚本不得提权**：`engines.exec` 派生要求子掩码不超出父掩码；触达他人执行还要求
     调用方掩码**覆盖**目标掩码（低信任不得停高信任）；目标授权查不到 → fail closed。
   - **命名通道改为按发起执行私有**（名字索引按 owner 分桶）：不同执行同名通道各拿各的 id，
     否则通道就是一条绕过掩码的跨执行数据面。
   - 冻结面同步范围：§11 的「尚未实现」两条据此划掉并改口径、§13 的风险表与兼容矩阵
     两条 306s 措辞订正（见第 44 项）。模块表、Gradle 依赖、ArchUnit、CI 门禁均不放宽。
   - 流水与验证见 [`2026-10-08`](log/2026-10-08.md)。

44. **MediaProjection 会话不做自动续期，306s 上限由 FGS(`mediaProjection`) 消除**：
   `docs/design/13-roadmap-budget.md` 原写「会话状态机 + 可重授权引导；306s 超时前自动续期/提示」，
   兼容矩阵写「会话 306s 感知」——**两处都改**。理由是自动续期在 API 34+ 上做不到：
   每会话重新征询用户，没有可续的持久授权。正路是 `foregroundServiceType="mediaProjection"`
   的前台服务，它本身就免除了旧的 306 秒上限；到点情形（用户撤销、系统停止）如实
   报错并引导重新授权，**不假装续上了**。
   - 同一批落地：一次性同意（每次会话重新征询）、API 34+ 的顺序
     （FGS 起并确认 → `getMediaProjection` → 注册 `MediaProjection.Callback` → `createVirtualDisplay`）、
     幂等释放、迟到旧回调不影响新会话、帧经共享 `ImageAnalyzer.ingest` 入表。
   - 会话资源在连接终结时结构性收口（`ConnectionResourceRegistry`）：脚本崩了但投屏还挂着
     这条漏在结构上不成立，不靠每个调用方记得收。
   - **如实登记的残余**：真机行为未在本机验证（无设备）；`abortConnection` 对
     「子进程继承了桥 socket fd」这一形态没有覆盖（那种情况下脚本死了也不产生 EOF），
     需要 `runId → connectionId` 映射，属新范围，未在本批夹带半成品。

2026-10-07 拍板（批 74，A10① / A5；维护者审批的执行身份与日志归属方案）：

42. **归属来自宿主签发身份，不信任脚本自报 runId**：spawn 前 issue 一次性 256-bit 随机 token，
    hello/ACK 后把 engineId / engineRunId / connectionId 绑定到不可变协程上下文。
    UID fail-closed 保留；双方 PID 可得时再匹配，PID 不可得保留 noPid 路径。
    token 不落盘、不打印、不复用 runNonce；旧在线客户端无 hello 拒绝，不匿名回退、不自动重连。
    - **共享 Router，不共享请求号作用域**：键从 requestId 改为 `(connectionId, requestId)`；
      ticket 令完成/TTL/取消单次结算，旧结果不能清掉新请求。业务信封/方法表不变。
    - **自然退出与硬撤销分开**：自然退出/EOF 首次触发 5 秒有界排空，保留原 runId 读尾帧，
      重复 status 不续期；stop/kill/壳关闭则取消请求并关闭真实 IO。
      自停取消自身调用时，已有的停止/回池在不可取消收尾区域完成，不另加停止入口。
    - **宿主行仍为 0**，脚本行用 `EngineRunReceipt.runId`（不是 intentRunId/槽号/幂等键）。
      系统日志只显示宿主；控制台按执行显示脚本输出，任务日志仍是终态历史。
    - **不升级保证**：有界淘汰、TSF 丢包、强退尾行损失仍在，不做 console 持久化。
      `await consoleSink.log()` 正常兑现不是送达证明，验收看原始成功 ACK + 收集器实际行。
    - **明确残余边界**：A5 本批只完成身份/归属与请求隔离，不实现 CapabilityMask、跨脚本控制授权或沙箱。
      §11 原先把 CapabilityMask 写成现行防线，本批据代码订正为未实现；「同进程」亦订正为
      每脚本独立进程、同 UID 同权。能取得其他执行凭据的同 UID 代码仍可能冒用，清 env 不构成隔离。
    - 冻结面同步范围仅 §7/§8/§11 与 `SECURITY.md`；模块表、Gradle 依赖、ArchUnit、CI 门禁均不放宽。
      `app/detekt-baseline.xml` 仅迁移既有两条工厂签名，无新增违规豁免。
      流水与验证见 [`2026-10-07 批 74`](log/2026-10-07.md)。

2026-10-01 拍板（§8.5/§8.6 收口：无人 await 的 run 期限线；非 §18 编号项，原口径不涉）：

14. **时限归属判据 = 「发起方等不等」，不是「有没有声明超时」**：
    §8.6 原先自己记的诚实边界是「引擎侧 `waitCompletion` 超时不发起的场景还没人管（在途 run 没人收尾时 watchdog 是唯一兜底）」——
    而看门狗三路判据全看**进程表现**（心跳/CPU/RSS）：心跳正常、CPU 空闲、RSS 很低的长跑脚本三路都判健康，**没有任何一路收得住它**。
    - **判据选型**：加 `PoolAcquireRequest.timeoutEnforcer: TimeoutEnforcer{AWAITER, WATCHDOG}`（缺省 `AWAITER` = 调度链路，行为零改）。
      为什么不按「`EngineRunRequest.timeoutMillis != null` 就交给看门狗」判：调度链路也声明 `timeoutMillis`（透传给执行体），
      但它**自己 await 终结并超时强杀**（`ControllerRunDispatcher` → `killRun(REQUESTED)`）—— 声明了却已经有人在执行，看门狗再收一次就是两套到期口径。
      判据于是落在**谁 await** 上：无人 await 的（桥 `engines.exec`）才把期限线交给看门狗。
    - **期限线的位置**：`RuntimeController.WatchAnchor.deadlineMillis = startedAt + scriptTimeoutMillis`（纯函数，期限定下不再改），
      `EngineWatchdog.tick()` 里**先于** pid/心跳那两条「量不到」分支判 —— 期限不依赖任何度量，排在后面会让「宿主不给 pid」或「心跳未接线」的 run 连期限都够不着。
      落 `KillCause.TIMEOUT`（归 CRASHED：到点强制收账，不是调用方主动停；`Tick.timeoutKilled` 单列，与「病死」「分歧杀」分账）。
    - **`engines.exec` 的 `timeoutMillis` 由可选改必填**：缺席/`null`/`<= 0` → `ERR_INVALID_PARAM`（`PoolAcquireRequest.init` 里 `require` 同款守卫）。
      缺省值在这里没有诚实来源 —— 编一个（30s？5min？）等于替脚本静默决定它能跑多久，与 `queueTimeoutMillis` 传 0 视为漏配同一条纪律：**响亮失败**。
      JS 侧**不预检**（同 payload 发出去、宿主回错），两处校验必然漂移（与空事件名同一条纪律）。
    - **仍待覆盖**：期限只覆盖声明了期限的 run，且只在看门狗轮转真在跑时有效；池空退避周期内新起的 run 最坏晚一个退避周期才被看到。

2026-10-01 拍板（文档计数门口径；非 §18 编号项，原口径不涉）：

15. **文档里的「模块数 / 测试任务数」保持「手写 + 派生校验」，不改生成片段**（外审建议 → **作者本人撤回**）：
    第二次外审的文档组曾建议把 `ModuleGraphTest` 读的那三处文档数字（`CLAUDE.md`、`docs/design/06-modules.md`、
    `.github/workflows/ci.yml`）改成「生成到片段、文档 include」，理由是「改散文就会红」。**2026-10-01 作者撤回该建议，
    裁定「保持现状，不动作」**（条目已从 `docs/backlog.md` 移出，按该文件第 1 条纪律落到这里）。理由：
    - **前提不成立**：该测试**只在「散文写了数字且与派生值不等」时红**；改散文、或干脆不写数字，都不会红。
      「散文被测试绑住」的印象是错的 —— 这正是这条纪律按设计工作（数字可写，但必须等于派生值）。
    - **生成片段是口径变更，不是小改进**：能生成的只有数字本身；「15 个模块」「13 个测试任务」是嵌在句子里的
      人话（还带着「即全部带 `src/test` 的模块」这类限定），没法机器生成。为此把契约散文降级成生成物，
      与 `docs/design/` 「人读的契约」定位相悖。
    - **两条附带观察，均不构成动作**（记录备查）：(a) 抓取正则 `(\d+)\s*个?\s*(模块|测试任务)` 偏宽 ——
      任何 `N 个模块` 的写法都会被抓；逃逸阀是 `quotedCounts` 白名单（现仅一条：§6 引的批判建议原文
      「约 12 个模块」），引文照此登记即可。(b) `ciTestTasks` 只认含 `./gradlew` 的行 —— 方向是**缺省即红**
      （新模块漏登记 CI 任务行会被 `withTests == ciTestTasks` 断言抓住），比反向宽松安全。
    - `domain/build.gradle.kts` 已把被扫文件声明为 `inputs.files(...)`（只改文档不会 UP-TO-DATE 静默跳过门），
      该做对的地方保持不动。

2026-10-01 拍板（安全防线：本地状态不进系统备份；非 §18 编号项，原口径不涉）：

16. **`files/.autojs/` 不参与系统备份与换机迁移 —— `allowBackup="false"`，契约侧登记为 §11.2 新增 T9**：
    依 §11.4「新增/加强防线走 §18 决策台账，不在设计文档里悄悄添」，防线先在这里记账再落契约。
    - **要守的是什么**：`.autojs/` 里没有可再生的用户数据，全是**信任锚与审计面** —— `lock.sig`（「这份 lock
      是本机签过的」§10.5-1）、审批台账（`pkg+versionHash` 的 APPROVED）、安装 journal/history、意图日志。
    - **为什么关**：默认（`allowBackup` 缺省 = true）它们进 Auto Backup / D2D 迁移，落到**另一个设备上下文**：
      `lock.sig` 在新机上验不过（密钥不出本机，Keystore 不随备份走），而审批台账与安装 history 会以
      「已批准 / 已完成」的姿态照常出现 —— 恢复出来的状态**既不可信也不可解释**；`adb backup` 还是一条把
      信任锚整包拷出设备的现成路径。
    - **代价（如实说）**：换机不再自动带走过往审批与历史，需要重装/重批 —— 这是有意的，不是遗漏。
    - **落点**：`app/src/main/AndroidManifest.xml`（`:app` 是唯一设 application 级属性的模块，库 manifest 合并面
      不动）；契约行在 §11.2 T9。

2026-10-01 拍板（backlog 批 7 的三项 S 级；非 §18 编号项，原口径不涉）：

17. **`:app-service:permission-center` 维持独立模块，不并回**（backlog D1，91 行的小模块）：
    外部审查的疑问是「独立模块偏重」，逐条核对依赖图后维持现状 ——
    - **它是 §9.5 那条例外的物理载体**：契约写「所有模块不得直接查 Settings/ActivityCompat，一律经此门禁」，
      而 `:platform:*` 的 archUnit 黑名单含 `com.autoscript.appservice..`（所以门禁不能放在平台模块里）。
      独立模块把这条边界变成**依赖图上的硬边**（`ModuleGraphTest`：`permission-center → :domain` 单点），
      并回任何 `:app-service:*` 则要把「app-service 内部不许直查系统设置」变成口头约定 —— 用架构手段守的边界
      不该降级成约定。
    - **并回的代价大于收益**：它只依赖 `:domain`；并进 runtime/scheduler 任一个都要给那个模块新增一条上游
      依赖或开子包，而那些模块已经有各自的清晰职责。
    - **「等它长」已有征兆**：门禁面在长（`Capability` 九项），行为面（reader/launcher 两道缝）也在长。
    - 记这条是为了让下次再看到「91 行」时不必重查一遍。

18. **命名统一到此为止：`.autojs` 目录与 `autojs-lock-v1` 前缀**保留**（backlog D6 的剩余部分）**：
    描述面（9 处 `@autojs/*`）已按事实侧改成 `auto` / `bridge_native.node`（`AutoJsPro` 九处保留 —— 那是
    **对标产品名**）。但存储面两处**不改**，理由是它们不是命名而是**已落盘的格式**：
    - `files/.autojs/` 是 §10.2 写进契约的存储布局（`lock.sig`/审批账/journal/history 的落位），实现与测试
      共 30+ 处一致使用；
    - `autojs-lock-v1|` 是 **lock 签名前缀**（§10.5-1 带外信任锚的输入串）。改它 = 旧设备上已签的
      `lock.sig` **全部验不过**，而失败形态是「明明签过的 lock 被判未签」—— 对一个安全锚来说是往危险方向退。
    - 要改的话须带兼容策略（新前缀 + 旧前缀验签回退，或一次性重签），属契约变更，须先拍板。
    - **发布用的 npm scope 归属未核**（`bridge/js` 标 `"private": true`、不发布 registry）—— 需维护者确认。

19. **第三方许可声明 = 生成物，且随 APK 分发**（backlog D8；`README.md` 许可节同批改写）：
    - **为什么生成**：版本事实来源是 `node-runtime-build/VERSIONS.env`（Node/NDK/OpenCV/KleidiCV/npm 全部
      钉在那里），手写声明必然在某次版本变更后与事实脱节，而**许可声明脱节在分发时是法律问题**。
      `node-runtime-build/licenses/gen-notices.mjs` 从 VERSIONS.env 渲染 `THIRD_PARTY_NOTICES.md`，
      CI 有一道 `gen-notices.mjs && git diff --exit-code` 门（照抄 js-tests 的 `gen:wire` 形状）。
    - **原文与清单分开**：逐字许可原文入 `node-runtime-build/licenses/`（Node 1586 行 / OpenCV /
      KleidiCV / libjpeg-turbo 双许可含 IJG 原文 / libpng / zlib），生成器**只渲染清单面**——
      转述上游许可即失真，改原文即伪造。
    - **随 APK 分发**：仓里一份只解决审计面；装到用户手机上的二进制，其许可条款必须**随分发可达**，
      故 `prepareNoticesAssets`（`autoscript.engine-natives` 约定插件）把它与七份原文拷进
      `assets/third-party/`。**判据与前三件不同**：本件在 git 里（生成物已入库），缺件是**仓库破损**而不是
      「本机没构建」——按 `bridgeDist` 口径红，不按选填件口径只 warn。
    - **NDK/libc++ 一行如实写「见 NDK 随附 NOTICE（未随包）」**：工具链 NOTICE 体积大且随 NDK 分发，
      声明它而不复制，比复制一份可能过期的副本诚实。

20. **APK 体积超支：接受 + 在能力中心明示安装体积**（backlog E1；§15 唯一被实测推翻的条目）：
    - **选项 (a)**，2026-10-02 拍板。(b) 按需分发与 (c) 继续裁 OpenCV 面都否掉，理由是实测：
      OpenCV 只占 7.0 MiB / 6.6%（按 ICU 后 92MB 分母折算，原 7.8%/8.6% 同样不改变结论），
      **(c) 的收益上限就是那 6.6%**，而 `imgcodecs` 已只留 PNG/JPEG，再裁要动 SPI 承诺；
      (b) 要给 `libnode.so` 找 exec 之外的落位链（§19 未解决），是比"披露一个大数字"大得多的工程。
    - **改的是披露，不是预算**：§15 表里 `≤ 40MB release` 那一行**保留原样并标注已超支**，
      证据链（`node-slice` artifact 体积 + `SHASUMS256` + `VERSIONS.env`）也保留。推翻一个契约要留账，
      静默把数字改大就是"账被擦了"。
    - **UI 给的是实测值，不是抄文档**：§15 记的 ≈92MB 是 node-slice 产物的**未压缩** jniLibs
      三件套合计，用户装的是**压缩后**的 APK，两者不是同一个数。抄文档数字进 UI 就是呈现层说谎。
      故 `InstallSizeRead`（`:app`，纯 JVM 可测）量 `applicationInfo.sourceDir` 与
      `nativeLibraryDir` 的**真实文件**，`CapabilityCenterSnapshot.installSize` 传下去，
      `InstallSizeState.text()` 出文案（`MiB` 口径，不与 MB 混用）。
    - **三件都要说全**：总数、其中引擎那一段（超支全在它身上，不分段就答不上"为什么这么大"）、
      引擎 .so 在不在。**量不到就说未量到，不显示 0** —— 0 会被读成"安装包是空的"；
      引擎缺失时体积照报（同一条纪律的另一面：装了个跑不了脚本的壳是事实，隐去比显示更糟）。
    - **→ 2026-10-05 改口径（批 47）**：设置页**撤下**「安装体积 xxx」那句渲染（用户口径
      「安装体积xxx那个文字去掉」）—— 本条「明示」的一半作废；「接受超支」的预算记账、
      §15 证据链、`InstallSizeRead` 实测链与 `InstallSizeState` 换算**全部不变**
      （测试仍钉着文案）。见本文件「已推翻 / 已改口径」表。

21. **shell 捕获输出超限：静默截断 + 显式日志 Warning + 返回截断标志**（backlog A2b；§9.6 原口径只写「双流并发读干」，没定过上限）：
    - **背景**：`AndroidShellExecutor.PipeReader` 把 stdout/stderr **全量**读进内存，一条 `cat` 大文件
      就能把宿主撑爆。这是 2026-10-01 修 A2（超时/收尸）时露出的口子，当时刻意不夹带 ——
      加 cap 是**契约口径**变更，两条路都合法且代价不同：**超限报错**让合法的大输出直接失败，
      **截断**有损但可用。故先拍板再动手。
    - **拍板**：**截断**。「静默」指的是**调用方不因此失败**（不抛 `ERR_*`、`code` 仍是子进程真实
      退出码、`isSuccess` 语义不变），**不是瞒着** —— 三处同时留痕，缺一处就是悄悄丢字节：
      ① `ShellResult.truncated`（**程序**读的那个，纯增量字段、缺省 false）；
      ② 可注入 `LogSink` 的 Warning（**运维**读的那个，真机进 logcat，带命令本身与丢弃字节数）；
      ③ 被截那条流末尾追加 `TRUNCATION_MARK` 说明行（**人**读的那个 —— 否则半截输出看起来
      就是一条正常结束的输出）。
    - **上限 = 每条流 1 MiB**（`ShellCaptureLimit.MAX_CAPTURE_BYTES`），不是随手取的整数，三条约束：
      ① 正常 shell 输出是 KB 级，1 MiB 对合法用途"够不着"；② 1 MiB 文本 JSON 编码后仍 ≈1 MiB，
      远在单帧上限 8 MiB（§7.5）之下；③ **最坏情况**：`DomainJson.appendQuoted` 把 `c.code < 0x20`
      转义成 `\uXXXX`（**6 倍**膨胀，非估算 —— `ShellCaptureLimitTest` 拿 `DomainJson` 真编一遍钉住），
      1 MiB × 6 ≈ 6 MiB 仍不触顶。**这条余量是必须的**：单帧超限在桥上是 `FrameTooLargeException`
      → 读循环 `break` → **关连接**，比截断重得多，所以上限必须在实现侧先兜住。
    - **两条流各自计数**（stdout 截了不挤掉 stderr 的额度）：错误信息短、且最该留住，
      共用一份额度会让啰嗦的 stdout 把它挤掉。
    - **到顶后仍然读到 EOF**（关键，写错就退化成死锁）：只是不再往缓冲里放字节。停下来不读的话，
      子进程会阻塞在写满的管道上 —— 那正是 §9.6「双流必须并发读干」要防的死锁，截断反而把它请回来。
      `AndroidShellExecutorTest` 用记账读端（`CountingStream`）钉住"全部字节都被读走"。
    - **日志不走 `android.util.Log`**：`:platform:system` 的 JVM 单测没有 `isReturnDefaultValues`，
      直接调会抛 "not mocked"，一条"输出超限"的告警不该把测试判红。故走可注入的 `LogSink`
      （缺省 `java.util.logging`，其 `ConsoleHandler` 写 `System.err`，Android 把 `System.err`
      重定向进 logcat —— 落得到，但 tag 不叫包名；要精确 tag/优先级就注入自己的 `LogSink`）。
    - **落地面**：`ShellContracts.kt`（`truncated` 字段）/ `ShellCaptureLimit.kt`（上限 + 日志缝）/
      `AndroidShellExecutor.kt`（cap + 播报）/ `ShellNamespaceHandler.kt`（四字段载荷）/
      `bridge/js/src/extras.ts`（`ShellResult.truncated`，**双侧逐字对齐**）；契约正文见 §9.6，
      示例见 §12.3。

2026-10-02 拍板（backlog C6；文档架构，非 §18 编号项）：

22. **`design-status.md` 按「当前状态页 + 按日期的流水切片 + 实现注记」三分，§ 锚与只追加纪律不动**：
    - **背景**：该文件 175 KB —— 头部与接口期表 ~5 KB，流水段 108 KB（35 条，全挤在一节里），
      实现注记段 59 KB（自各分卷外迁的逐字叙事）。外审建议拆「当前状态页 + 按日期的日志文件」。
    - **口径**：① `design-status.md` 只留**接口期表 + 流水目录**（按日期的表，逐行链到切片）；
      ② 流水按日期切片到 `docs/log/<YYYY-MM-DD>.md`（本次四片：09-29 / 09-30 / 10-01 / 10-02，
      共 35 条），索引 `docs/log/README.md`；③ 实现注记（含「各分卷实现注记的搬迁状态」两表）
      搬到 `docs/implementation-notes.md`。
    - **纪律不变**：**只追加** —— 新条目加在**当天那个切片**顶部（文件不存在就新建），
      并把 `design-status.md` 目录表里那一行的条目计数 +1；被推翻的记账不删除，原地标作废。
    - **§ 锚不动（本次最要紧的一条）**：`09-capabilities.md` / `08-execution.md` 等卷正文里
      9 处 `design-status.md#实现注记自各分卷外迁逐字保留` 指向的是**那个标题**，不是内容 ——
      故在 `design-status.md` **逐字保留同名一节**做占位 + 指针，那些链接一条都没改。
      § 号仍是唯一权威锚，不随文件位置变化；分卷正文里的 `§X.Y` 引用不受影响。
    - **搬迁是逐字的**：四片切片拼接 == 原 45–1016 行、实现注记正文 == 原 1022–1372 行
      （按字节比对通过）；切片之间原有的空行原样保留，只在搬运处改相对链接深度
      （`log/` 比 `docs/` 深一层：`design-decisions.md` → `../design-decisions.md`）。
    - **为什么不是 `CHANGELOG`**：本项与本文件第 21 项（外审 C5「不设 CHANGELOG」）不冲突 ——
      拆的是**同一个台账文件**，不是新开第二个事实来源；切片仍是「流水」，只是按日期分了文件。
    - **留下的锚代价**：`design-status.md` 里「实现注记」一节是**空壳**（只指向
      `implementation-notes.md`），为的就是不动那 9 条分卷链接 —— 若将来愿意改那 9 条链接，
      这个空壳可以删。

2026-10-02 拍板（backlog C9；文档架构，非 §18 编号项）：

23. **`docs/README.md` 作为 `docs/` 的总索引；「文档边界」表**搬**过去、原地留指针，不并列两份**：
    - **要解决的是什么**：外审 C6 建议里的第三件（拆 C6 时未做，登记为 C9）。当时的顾虑是
      **同一事实写两遍** —— `framework-design.md` 的「文档边界」表 + 根 `README.md` 的仓库地图
      已经覆盖了「哪份文档回答什么」，再加一份并列的表就是第二个事实来源（判据同本文件第 15 项）。
    - **口径**：**搬家不是并列**。`docs/README.md` 收下这张表并**扩成完整地图**（12 卷契约 /
      入口 / 决策 / 状态页 / 流水切片 / 实现注记 / 待办池 / 归档，逐行带**纪律**一列）；
      `framework-design.md` 与 `design/18-19-ledger.md` 那两处「文档边界」段**原地换成指针**。
      于是这张表在仓库里**只有一份**，位置从「契约入口的附带一节」升成「`docs/` 自己的门面」——
      导航本就该在这里（读 `docs/` 的人第一眼要看见的就是它），而不是藏在某一卷的末尾。
    - **为什么不是「再加一份并列的表」**：并列两份的下场是可预期的漂移（改一份忘一份），
      而这类漂移**没有门能抓**（`ModuleGraphTest` 只派生模块数 / 测试任务数，抓不到散文表）。
      本仓对这件事的一贯处置是「同一事实只写一份，其余地方留指针」（见第 15 项与第 21/22 项）。
    - **没动的**：`§ 号仍是唯一权威锚`、**只追加**纪律、`docs/log/README.md`（那是**流水切片的索引**，
      按日期逐条列条目，与本文档地图**不是同一份**，两者关系在 `docs/README.md` 末尾点明）；
      `CONTRIBUTING.md` 的四份文档分工表**保留**（它讲的是「写东西时的纪律」，地图由 `docs/README.md` 承担，
      两处已在文字里各自点明分工，不再互为副本）。
    - **代价（明写）**：多一个文件要跟着改；换来的是「哪份文档回答什么」有唯一落点，
      且 `docs/` 目录自己有了入口（此前从根 `README.md` 或 `framework-design.md` 绕进来）。

2026-10-02 拍板（backlog E2；图像算子预算，非 §18 编号项）：

24. **`matchTemplate` 拆 std/相位双门 + 负结果回精确兜底（E2 裁「修」，不是改口径）**：
    - **前提先纠（E2 起草数字已过期）**：E2 引的「A4 933.6ms、A2 计算段 1912.8ms 均 ❌」
      是 2026-09-30 首测数；2026-10-01 的相位平均 + FastPath 12a + 场景缓存（§7.7 五次
      实测）已把 A4 370×80 打到 **19.82ms ✅**、A2 **88.86ms ✅**。E2 的实际残余只有
      **48×48 全帧形态**：真机模板 std 6.29<12（std 门）+ `phase_worst` 0.6742<带宽
      0.75（相位门）双拦 → 恒精确路径 855~948ms（判据 <40ms）。
    - **拍板：修**。拆掉 std 门与相位门（尺寸门 `min_templ_side=48` /
      `kMinCoarseSide=12` 保留），`match_pyramid` 负结果加**回全图精确路径的兜底**
      （`best.conf >= thr ? best : match_exact(h, n, thr)`）。相位探针 `phase_probe` /
      `NeedlePrep.phase_worst` 随门退役（只服务那道门，留着是死代码）；
      `build_phase_avg` 相位平均粗模板**保留** —— 它治的是粗筛召回，不是门。
    - **为什么是兜底而不是调门**：两门的唯一职责是防假漏检，它们拿「挡回精确路径」
      当挡错时的代价 —— 白付一次全图 matchTemplate（473~1500ms）还没解决问题。兜底
      把风险面反过来：**假漏检结构性消除**（强制精确 vs 金字塔的答案差从语料统计问题
      变成构造性质），挡错的代价封顶为「多付一次粗筛」（host 实测噪声负例 +1%）。
      门挡对时省的那一次粗筛（≈0.1× 全图成本）买不起它挡错时的赔率。**「调门」路线
      同批否决**：host 复量证明 0.5× 档能过相位门但 std 门仍拦、`kMinCoarseSide`
      12→13 不改变任何一档 —— 调参是在坏机制上挪数，不动机制救不了（§7.7 第七次块）。
    - **实测（host x86_64，同一 OpenCV pin，1080×2400 真机截图，thr=0.9）**：48×48
      全帧 532→**33.0ms**（16×，两路径均命中 conf=1.0000，报位差属重复副本 argmax
      口径）；370×80 回归 508→32.9ms；噪声负例 45.3→45.8ms（兜底代价 = 粗筛本身）。
      host 语义门九个二进制 **422 检查 0 失败**（case6c 高频反例的守护对象从「频率门」
      换成「兜底」，断言一字未改）。数字与机制勘误全文见 §7.7 第七次块。
    - **诚实边界（明写）**：判据状态以**真机**为准 —— 2026-10-02 同日补跑（同机同
      `scr.raw` 同 bench ×100 中位，同 so `FORCE_EXACT` A/B，全文见 §7.7 第七次块真机
      表）：48×48 全帧 855~948ms → **64.43ms**（13.7×，同轮强制精确 882.25ms），**仍
      差 1.6× 未过 <40ms 判据** —— 判据行该形态继续如实挂 ❌，改善记档不冒充达标；
      370×80 19.67ms、A2 88.20ms 无回归，region/A3 全绿。残余立项 backlog E5。原
      「四层压住假漏检」中 std/相位两门的那两层由兜底取代；§7.7 三次
      实测块「零候选 → 必假漏检」的机制描述同批**勘误为「名额挤兑」**（真值排 327，
      K=32 被假峰吃满；只追加勘误，原文不动）。判据 <40ms **原文不改**（与第 13 项
      ③ 同一纪律：改的是数字，不是口径）。
    - **没选「改口径」**：把 48×48 挂到「出路 = region 16.9ms 推荐姿势」了事也能消 ❌，
      但那是把没解决的问题改成文字问题；region 仍推荐，全帧判据继续按原文验。

2026-10-02 拍板（backlog E5；小模板形态判据放宽，非 §18 编号项）：

25. **`matchTemplate` 48×48 小模板全帧形态判据放宽为 <100ms（实测 64.43ms 接受转绿）；E5 关闭、不投优化**：
    - **拍板 = 降低要求、接受现状**：48×48 小模板全帧形态不再套 `<40ms`（第七次真机实测
      中位 64.43ms / run 内 max 72.47ms），该形态判据放宽为 **<100ms** —— 1.5× 实测余量
      盖住 run 噪声与机型差，仍比同轮强制精确路径（882.25ms）严 8.8×、比首测（862.4ms）
      严 8.6×。**常规形态 `<40ms` 原文不动**（370×80 真机 19.67ms 支撑；第 13 项 ③ 与
      第 24 项「原文不改」对常规形态继续成立 —— 本项只给小模板形态开口子，不是把
      40ms 改成 100ms）。
    - **E5 关闭 = 不投优化**：K=32 候选面（NMS 在 270×600 结果面扫 32 轮 + 32 个精配窗）
      不收紧、懒惰 NMS / 带宽收窄都不做 —— 继续压的收益是 64→<40ms，被裁定不值当。
      region 17ms 姿势照旧是低延迟推荐，但不再是「全帧 ❌ 的出路」（该形态已转绿）。
    - **落点（同批）**：§7.7 判据行目标列注分形态、第七次块真机表 48×48 行标
      `~~❌ 差 1.6×~~ → ✅（放宽后）`（原数字保留，只追加标注）、§15 预算表
      （`13-roadmap backlog` 行同注）、design-status 接口期行、backlog E5 划掉。
    - **代价（明写）**：放宽后的 100ms 红线仍能抓「退化回精确路径级」的巨幅回归
      （882ms 的 1/9），但抓不到 100~120ms 的小劣化 —— 换掉的是这类灵敏度，如实认账。

2026-10-02 拍板并落地（backlog A6 + E4 合批；node-runtime-build 素材线，非 §18 编号项）：

26. **vendored npm 素材来源换 registry 发布态 tarball（`npm@12.2.0`）；E4 随换源天然达成**：
    - **A6 要解决的**：素材取自 Node 源码树 `deps/npm` = npm 11.19.0 ≠ §10.1 脊梁的
      「npm 12.x 系」；「等 Node 线携带」已实测否掉（868 条官方发布无一携带 12.x）。
      **先核实后动手**（backlog 标的两个「未核实」当天查完）：① npm 12 **存在** ——
      registry 索引 latest = **12.2.0**（sha1 `9b58e3ad…`，2026-10-02 实抓）；
      ② `allowScripts` 默认语义 —— **半对**：host node 22.23.1 直跑解包产物实测，
      npm 12.2.0 **依赖** lifecycle 默认拒绝（`allow-scripts` 白名单缺省为空，拦时带
      `npm warn install-scripts` 播报 + `install-scripts approve` 放行通道 —— 禁静默
      达标），`allow-git=none`、`allow-remote=none` 逐字吻合；**项目自身** lifecycle
      仍执行（历代如此）。契约 §10.1「拒绝全部 lifecycle」措辞同批精化为「依赖
      lifecycle」，旧「现状是 11.19.0」注标作废。
    - **口径**：换源 —— `VERSIONS.env` 钉 `NPM_CLI_VERSION=12.2.0` + `NPM_CLI_SHA1`，
      `fetch-and-build.sh` §9 下载（落 `$DL`，随工作流下载层缓存）+ sha1 校验 + 解包，
      原有剪裁 / 点条目清零 / 三锚在场 / 版本断言 / 基表**全部照旧**。原「不另下
      registry tarball」的理由（版本必须与 libnode 同一条「钉死 + 全链回归」纪律）
      **没有丢**：改这两个值即命中 node-slice 的 paths 触发面 → 全链回归，§9 还有
      sha1 + 版本双闸。
    - **E4 随换源天然达成**：registry 发布态没有 `test/`（1.9MB 表观）与
      `tap-snapshots/`（816K）—— 不是剪掉的，是**发布形态本来就不带**；§9 干跑实测
      素材 1846 文件/11MiB 表观 → **1674 文件/9MiB**、占盘 18M → 16M（净差 −172 文件 /
      −2MiB 表观，与 E4 登记的 2.7MB 两目录毛估同量级，净差略小属正常 —— registry 形态
      与源码树互有增减）。剪裁清单 docs/man 保留（发布态仍带
      176+89 个文件）。
    - **验证**：§9 段落本地干跑（假 `$DL`/`$SRC`/`$OUT`）两轮通过 —— 下载/校验/解包/
      剪裁/点条目清零/三锚在场/版本断言/基表全绿；**node-slice 全链回归在 CI 跑**
      （本机禁编 Node，CLAUDE.md 口径），出库数字与旧值对比记进当天流水。
    - **代价（明写）**：素材与 Node 源码树**版本解耦** —— 以后升 Node 不再自动带
      npm 升级（各自钉、各自回归）；换来的是脊梁可兑现。~~§10.12 残余不变：
      child_process 拦截 shim 仍未落（P0 未排）~~ **该残余已于 2026-10-09 批 80 消解**
      （见本文件第 50 项）；硬编码 `--ignore-scripts` 主控不撤（它挡 lifecycle、
      shim 挡非脚本 spawn，两层各管一段）。

2026-10-02 拍板并落地（backlog C7 + B4 合批；facade 文档面 + 依赖供应链，非 §18 编号项）：

27. **脚本 API 参考用 typedoc 生成、生成物入库并设零 diff 门（C7）；依赖供应链面补 `engines` + audit/SBOM（B4）**（原标题「~~依赖供应链面开 dependency-review 并补 `engines`~~」**作废** —— 2026-10-02 当日勘误，见 B4 条）：
    - **先核实后动手**（backlog C7 的「未评估 typedoc 覆盖度」当天实测）：typedoc
      0.28.20 + `typedoc-plugin-markdown` 4.13.1 跑 `bridge/js/src/index.ts`，**0 error**
      （35 warning 全是「某类型被引用但不在文档里」，非错误）。**但单入口只出 15 页** ——
      `auto` 是匿名对象字面量，typedoc 把它渲染成一串 `__type`，用户向参考没有可链接的
      入口。**修法（本项落地的实质）**：把 `auto` 的形状提成具名 `AutoNamespace` interface
      （字段类型全是 `typeof <现成的 namespace 对象>` —— 与实现同源，**不引入第二份事实**，
      手写签名才会漂移），`export const auto: AutoNamespace = …` 标注之。
    - **形态裁决（生成物入库 + 零 diff 门）**：`docs/api/**` 进 git，CI 的 `js-tests` job
      加一步 `npm run docs:api && git diff --exit-code` —— 与既有的 wire 生成物门、
      `THIRD_PARTY_NOTICES.md` 门**同一条纪律**（生成物入库、漂移即红、手改无意义）。
      选 markdown 而非 HTML：markdown 能被既有的**文档链接门**扫到（相对链接逐条验存在），
      HTML 不能；且 markdown 在 PR 里 diff 得出来。入口面 = `index.ts` 的 export 面
      （作者划的边界），内部件（`runtime`/`bridge` 实现细节、各 namespace 的 `pump*`）
      刻意不进 entryPoints —— 免得把内部件抬成「文档上的 API」。
    - **同批顺手修的两处 KDoc 缺陷**（都是生成物暴露的，不是顺手改代码）：① `sensors.register`
      的 `@param delay` / `@param ignoresUnsupported` 写在**方法** KDoc 里、而参数在 `opts`
      对象里，typedoc 报 "not used" 且用户向文档**看不到这两个选项** → 改成 `opts` 的行内
      KDoc（生成物里逐字可见）；② `console.QueueErrorListener` 是私有类型却出现在公开签名
      `consoleSink.onQueueError` 上 → 提成 `export type`。两处都**不动行为**（`tsc` + 194
      条 facade 单测逐字不变）。
    - **B4（依赖供应链面）**：**2026-10-02 当日勘误（本条原先记的是「开
      `dependency-review` job」，实测红后撤掉，原口径不删，见下方第二段）**：① `package.json`
      补 `engines.node` = **npm 12.2.0 自己的
      engines 逐字**（`^22.22.2 || ^24.15.0 || >=26.0.0`，解包产物实读）—— 不自己发明
      范围，免得与「vendored npm 的宿主要求」两份口径；② 漏洞扫描与 SBOM 进 CI
      （`npm audit --audit-level=low` + `npm sbom --sbom-format cyclonedx` → artifact）。
      **`actions/dependency-review-action` 试过并撤掉（PR #26 实测红）**：报
      「Dependency review is not supported on this repository. Please ensure that
      Dependency graph is enabled」—— 本仓**依赖图未开**（`/dependency-graph/sbom` 404、
      `/dependabot/alerts` 403，两条 API 实测）。开它是**维护者侧的仓库设置开关**
      （Code security and analysis 页，或 `PATCH /repos/{owner}/{repo}` 的
      `security_and_analysis`，两者都需 admin 权限 —— 本仓令牌 403），与 C4 那次
      「只剩维护者动作」同型 → 撤 action、改走**不依赖依赖图**的两条，并把开关登记进
      backlog B4 行等拍板。
      **诚实边界（明写）**：`npm audit` + `npm sbom` 覆盖的是 **npm 面**（facade 的
      devDependencies）；**Gradle 面（`:domain` 之外的 Android 依赖）当前没有漏洞扫描**
      —— 它要么靠依赖图 + `gradle/actions/dependency-submission`，要么自建（独立一件事，
      未排期）。npm 面实测 `npm audit` = **0 vulnerabilities**（2026-10-02，官方 registry）。
    - **代价（明写）**：① 生成物入库 = 改 facade 的公开注释/签名要记得重跑生成器（漏跑 CI 红，
      不是静默）；② `docs/api/**` 现在也在文档链接门的扫描面里（341 条链接），typedoc
      改了链接形状会连带红 —— 这是想要的耦合，不是意外；③ 供应链面当前只覆盖 npm 面
      （见上，dependency-review 已撤）；④ 补 `engines` 不动 CI 的 Node 版本（CI 仍 pin node 24 且 `engine-strict`
      未开，engines 是声明不是门禁）。

2026-10-02 追加（第二轮外审：优化 / 文档 / 结构三部分静态复核；**已逐条自己验过再登记**）：

28. **第二轮外审的处置口径：能证伪的先证伪、能立刻改的立刻改，其余登记 backlog 不塞进本批**：
    - **证伪 / 收窄两条**（避免按外审原文直接动手）：① 外审 O1 说「Node 进程上不了网，脚本 HTTP
      会挂」—— 实测**收窄**为「卡的只是 npm 取包」：脚本侧**没有** HTTP 命名空间（§12.1 export 面
      里没有 http），桥走 abstract unix socket 不占网络权限；INTERNET 缺失本身**属实**（合并 manifest
      逐条列过）。② 外审 O6 说「依赖图关着导致 Gradle CVE 不可见」—— 依赖图只是**其中一条**通路，
      `gradle/verification-metadata.xml` 与 wrapper 校验和不依赖它，两件事不互为前提（B4 行已按此改口径）。
    - **立刻改一条**：`README.md`「运行」节补一条**如实警告** —— 自带引擎的 APK 现在也跑不了
      `npm install`（INTERNET 未声明，一行可修但**未排期**），免得读者按 README 走到"装了、起了、
      装包失败"再回来查。
    - **登记 backlog 九条**（都带 2026-10-02 实测证据，不采信外审原文的未经核实陈述）：
      **A7**（28 处吞异常，含 3 处 suspend 上下文里吞 `CancellationException` 的实证）、
      **B7**（INTERNET 缺失，含影响面收窄）、**B8**（无 detekt/ktlint/editorconfig，落点涉及冻结文件
      → 建议与 B6 合批）、**C10**（§11.3 第 8 条仍写 npm 11.19.0，且 `SECURITY.md` 的优先级规则让
      **错的那份赢**）、**D9**（`HostNpm.kt` 两份已分叉，值得并；`HandlerRequests.kt` 两份是有意分家，
      **建议维持** —— 别当同一件事）、**D10**（`bridge/` 三种构建体系无 README）、
      **D11**（根 `autojspro-docs.txt` 未归位，需拍板搬或删）、**D12**（无 CODEOWNERS，且「协调者」
      在人类向文档里从未指名）、**D13**（文档目录按受众重排 —— 风险最高、收益最晚，**排最后**）。
    - **有意不采信的一条**：外审 D8 建议「把代码注释里的日期叙事搬去提交信息或流水」—— 本仓
      **不采纳**：那些叙事是**契约的载体**（如 `app/build.gradle.kts` 里"为什么不引第二个版本来源"、
      `ci.yml` 里"为什么这步在这"），搬走会让下一个改代码的人丢掉判据。注释密度是本仓的既有口径，
      不是待清理的债务。

29. **文档面改进批（同日第二件）：契约侧过期叙述订正 + 状态页可扫性 + 未归位参考件入档（C10 / D2 / D7-②）**：
    - **C10（必须改，因为它是个"错的那份赢"的结构）**：`11-security.md` 的「素材版本落差」段仍写
      vendored = npm 11.19.0、落差短期消不掉 —— 批 9 早已换 `npm@12.2.0`。真正的病灶不是过期，
      而是 `SECURITY.md` 自己写着「两处若有出入，**以设计文档为准**」→ **错的那份赢**。修法按
      本仓「原口径不删」：原地 `~~作废（日期 + 原因）~~` + 追加实测口径。**教训记在这里**：
      换源那批改了 `SECURITY.md` / §10.1 / §10.12 / backlog，**唯独漏了 §11** —— 漏的原因是
      当时按「§11.3 第 8 条」这个**错的条号**去找（那段其实在紧挨第 8 条**之上**的同一子弹列表里）。
      **规则**：交叉引用写条号时，落笔前先在目标文件里定位一次，别凭记忆写条号。
    - **D2（做「读法」不做「搬家」）**：状态页的「接口期（未落地，写了就是撒谎）」表里混着已落地行，
      标题与内容打架。**裁决：不搬**（12 行的表搬去 log 只增加查找成本），改为在表前加一段读法说明
      —— 标题只对 `未落` 开头的行成立、本页只做索引（>200 字叙事一律下沉 log/decisions）。
      这与 D13「按受众重排目录」是**两件事**：D2 是**可扫性**（就地加读法，已做），D13 是**物理布局**
      （仍未做，排最后）。
    - **D7-②（选「搬」不选「删」）**：根 `autojspro-docs.txt`（对标产品 AutojsPro 官方文档的链接索引，
      182 条指向 `pro.autojs.run`）`git mv` 进 `docs/reference/`，补 `reference/README.md` 写清
      **来源与性质**（**不是本项目文档**、不代表本项目已实现其中任何能力），`docs/README.md` 地图加一行。
      **为什么留不删**：它是「对标时查过什么」的唯一记录，删了就再没有第二处；留在根目录则是**未归类**，
      会被读者当成本项目文档。顺带把 D13 目标形态里的 `reference/` 那一格先落掉。
    - **顺带改口径**：`CLAUDE.md` 的「（私有仓，SSH 可推）」→「（**公开仓**，2026-10-02 实测
      `gh api repos/… --jq .visibility` = `public`）」，同段「该私仓权限」一并改掉。**外审 D6 报的
      「仓库可见性口径不明」由此关闭**（实测证伪了「私有仓」这句）。

30. **结构面批（同日第三件）：测试助手去重只并该并的那份 + `bridge/` 目录索引 + 冻结面指到人（D9 / D10 / D12）**：
    - **D9 只做一半，另一半明确不做**：`HostNpm`（宿主 npm CLI 发现，真 npm E2E 的环境前置）
      两份**并成一份** —— 源码住 `build-logic/testkit-shared/`（与 `build-logic/arch-shared` 同为
      **注源**手法，不进模块图），由 `:app-service:npm` 注进它的 `testFixtures` 源集，`:app` 用
      `testImplementation(testFixtures(project(":app-service:npm")))` 消费。**判据是「两份是否已经
      分叉」**：`HostNpm` 两份已分叉（`:app` 那份缺 `root`/`hasNode`，只剩 KDoc 里「改一处必须改
      另一处」这句口头约定撑着）→ 该并；`platform/capabilities` 的 `HandlerRequests.kt` 两份
      **维持**（只有包名/KDoc/工厂名不同，是 D5「测试树逐包镜像 main」的产物，合并会把两个
      namespace 的测试绑在一起，**与 D5 口径相反**）。**这条是本批唯一的口径**：同名 ≠ 该并，
      要看「分叉了没有」。
      - **注源刻意写在模块 build 脚本、不写进 `autoscript.jvm` 约定**：约定一改就把它塞给全部
        13 个测试模块，而实际消费方只有两个。
      - **代价如实记**：`:app` 的测试从此**编译期依赖** `:app-service:npm` 的 testFixtures
        配置。`ModuleGraphTest` 看不见这条边（它的正则只认 `project(":…")`，不认
        `testFixtures(project(…))` 形状）；但方向合法（`:app → :app-service:npm` 本在 §6 允许集内），
        故不违规 —— **是「门看不见」而不是「门放行」**，记在这里免得下次误以为已量化。
        **→ 2026-10-02 同日证伪（第 31 项）**：原文保留 —— 「看不见」错了，正则是子串扫描、
        该形状照样命中（伪造非法边实测变红）；「方向合法」与「编译期依赖」两句仍成立。
    - **D10 只写索引，不改结构**：新增 `bridge/README.md` 说清 `bridge/` 底下**五种东西**
      （三个 Gradle 模块 + 一个 npm 包 + 一个生成物源）各是什么、怎么构建/测。同批写明两处
      **惯例位之外**的事实：C++ 宿主机语义门禁住 `bridge/image/test/cpp/`（不是 Gradle 的
      `src/test`，因为该模块没有 JVM 代码）；**CI 不编 addon** —— `bridge_native.node`/`noden`
      只由 `engine/node-process/scripts/build-native.sh` 在本机按需编，CI 的两条 C++ workflow
      都只编 `libopencv.so`。**没有动任何目录布局**（那是 D13 的事，排最后）。
    - **D12 把规则指到人**：新增 `.github/CODEOWNERS`（默认 `* @Ventus-Pluviam` + 冻结面/契约面/
      安全面逐条），并在 `CONTRIBUTING.md` 与 `CLAUDE.md` 的协作纪律里把「协调者」**指名**为
      维护者 `@Ventus-Pluviam`。**此前的问题不是没规则，是规则指不到人** —— 两处都写「提给协调者」，
      而「协调者是谁」在人类向文档里从未定义。维护者身份取自仓库事实（`gh api …/collaborators`
      里唯一 `admin:true` 即仓库 owner），非杜撰。**口径**：CODEOWNERS 是机器可读的那份，
      文档里的指名人读镜像，不一致时以 CODEOWNERS 为准。
      - **本批没做的**：D12 与 D5（「人类/agent 规则混写」）在 backlog 里标注「同批做」，本批只落
        了 CODEOWNERS 这一半；D5 那半（把 `CLAUDE.md` 的 agent 规则与 `CONTRIBUTING.md` 的
        人类规则切开）未动，仍留在池里。
        **→ 同日批 12 已补做（第 31 项）**：快速开始 + AI 署名约定迁 `CLAUDE.md`。

31. **批 12（同日第三批）：INTERNET 权限真机 A/B + 贡献门槛拆分 + 「门看不见」类登记必须先跑实验（B7 / D5 / D14）**：
    - **B7 落地（一行权限 + A/B 冒烟把「静默必败」证成实测）**：`:app` manifest 补
      `android.permission.INTERNET`（普通权限、安装即授、不入 §9.5 三态门禁 —— 权限分层不变，
      只是把**前置条件**写进契约：§10.2 新增「出网前提」条目）。**为什么值得一条真机 A/B**：
      本条的病灶是「**静默**必败」（`npm ping` 不报错、只是永远 PONG 不回来），只验合并产物里
      有这行权限**证不了行为**。对照实测（CloudPhone / API 33，`run-as` 以 app uid 跑 vendored
      npm 12.2.0 CLI，素材取自 node-slice artifact）：
      **有权限** → `npm ping` PONG 1459ms / 1931ms、`npm view axios version` → 1.20.0（真出网拉 packument）；
      **无权限**（临时去行重打 APK、`install -r` 数据保留）→ 裸 `net.connect` 回 **`ERR EPERM`**、
      `npm ping` 45s 无 PONG 被 timeout 杀（rc=124）。**边界照写**：素材系手工 push（APK 内仍无
      引擎与 npm 素材 —— B5 另案未动）、冒烟系本机 adb 手工，**没进任何自动化门**（设备测试道 =
      E3/B3，仍待拍板）；生产装配路径（`AssetTreeCliSource` 落位 → `HostNodeExecutor`）因 APK 无
      素材而未在真机走通。冒烟是一次性取证，不是回归门。
    - **D5 落地（外审第二轮 D5 四条建议全齐）**：`CONTRIBUTING.md` 顶部加**「快速开始（最小路径）」**
      五步（进场不再要求先读薄索引 + `CLAUDE.md` + 604 行分卷，三份必读降级为「改深了再按级别补读」）；
      **AI 署名约定（提交 trailer + PR 描述署名行）迁入 `CLAUDE.md` 协作纪律** —— 单一事实来源归 agent 侧，
      `CONTRIBUTING.md` 与 PR 模板只留人读指针。此前的病是**同一规则写两份且分属两个人群文档**
      （本仓「同一事实只写一份」纪律在文档分工上的应用）。四条建议：quickstart ✓（本批）、指名维护者 ✓
      （批 11 D12）、CODEOWNERS ✓（批 11 D12）、agent 规则挪走 ✓（本批）。
    - **D14 证伪 + 一条比「先核实」更硬的子纪律**：批 11 收口时登记「`ModuleGraphTest` 看不见
      `testFixtures(project(…))` 边」—— **登记时只逐字读了正则、没跑实验**，而正则是
      `findAll(project\("…"\))` **子串扫描**，嵌套形状照样命中。本批判伪方式直接是**跑**：
      往 `:ui`（允许集只有 `:domain`）伪造一条非法 testFixtures 边 → `每条依赖边都在 §6 允许集内()`
      当场红。**新子纪律**：登记**反驳型条目**（「门看不见 / 门不查 / 守卫漏了」）必须先跑实验 ——
      这类断言读代码只能得到「看起来」，而「看起来看不见」恰恰是最容易读错的一类（子串正则、
      通配扫描、隐式 include 都会骗人）。五处错记同批勘误：backlog D14 行（出池·证伪）、D9 行、
      批 11 行、本文件第 30 项（追加「→ 同日证伪」箭头，原文保留）、`docs/log/2026-10-02.md`
      批 11 条目（原地标掉）。「顺带建议的标边类型区分 main/test 面」同步裁定**不做**
      （全仓仅 1 条此类边，投机性一般化）。


32. **许可证改 GPL-2.0-or-later（2026-10-02，本仓首次换许可）**：
    - **拍板**：仓库本体（Kotlin / C++ / TypeScript 源码 + `bridge/js` 这个 npm 包）由 **MIT 改
      `GPL-2.0-or-later`**。落地五处：`LICENSE` 换 GPL v2 全文（339 行，逐字取自 GPL 官方文本）、
      `README.md` 许可段、`THIRD_PARTY_NOTICES.md` 自身声明、`bridge/js/package.json` 的
      `license` 字段（`MIT` → `GPL-2.0-or-later`）、本项。
    - **为何 `or later` 而不是 `only`**：GPL v2 的 "or later" 一节（`LICENSE` 末段）让受任人可把整体
      按 GPL-3.0 再分发。选它 = 保留将来单向升级到 GPLv3 的路（Apache-2.0 时代的兼容性问题、
      GPLv3 的专利与反 Tivoization 条款将来若要接，不必再找齐全部版权人重新授权）。**代价如实记**：
      `or later` 也让受任人可以只挑 GPLv3 走，比 `only` 少一层"停在 v2"的确定性。
    - **第三方素材不受影响（不吞并、也不冲突）**：GPL 只覆盖**本项目自身源码**。Node.js / npm /
      OpenCV / libjpeg-turbo 等第三方仍按各自原许可（`node-runtime-build/licenses/` 逐字留档，
      `THIRD_PARTY_NOTICES.md` 列名）。**注意**：GPL-2.0 与部分第三方许可（如 Apache-2.0）的兼容
      问题**只在本项目源码与那些代码互相链接成单一作品时**才成立；本仓与第三方是**分发聚合**
      （各自独立文件、各自许可），不构成衍生。这条判断的前提是当前事实（无第三方源码被改写进本项目源码），
      **将来若真有源码级合入，须重新评估**。
    - **动机**：本仓近期开始参考 Telegram Android（GPL-2.0）的实现（前端质感重构，见
      [`log/2026-10-02.md`](log/2026-10-02.md) 批 13）。GPL 与 GPL 参考件同向，避免"参考了 GPL 代码
      却以 MIT 分发"的许可不自洽。
    - **未做**：未加 `COPYING`/`LICENSE` 双份、未在源文件头加 SPDX 注释块（全仓现状是零文件头声明）、
      未改 `SECURITY.md`（其内容与许可无关）。这些留作将来的可选项，本次不铺开。

33. **对外许可由 `GPL-2.0-or-later` 收窄为 `GPL-2.0-only` + 源码面署名（2026-10-06，第三轮外审 L1）**：
    - **拍板**：本仓对外分发许可改 **`GPL-2.0-only`**（GNU GPL 第 2 版，**不含**「或任何更新版本」）。
      落地六处：`README.md` 许可段、`bridge/js/package.json` 的 `license` 字段、`bridge/js/package-lock.json`
      的根包条目、`THIRD_PARTY_NOTICES.md` 第 3 节（生成物，改的是 `gen-notices.mjs` 后重跑）、新增
      `NOTICE`（源码面署名，手写）、本项。
    - **为什么收窄（这是第 32 项 `or later` 口径的推翻，原口径不删，见上）**：本仓 `ui/` 含**衍生自
      Telegram Android** 的部分（`MenuPopup.kt` 的 `cascade()` 自称「逐字移植」、`TabBarMeasure.kt`
      「逐句对着写」、`Particles.kt`/`DeletionParticles.kt` 的 `ThanosEffect` 形态参考、`Theme.kt` 的
      色值常量与键名），而 DrKLO/Telegram 是 **GPL-2.0-only**（无 "or later"）。GPL-2.0-only 与 GPL-3.0
      **不兼容**，两者合成的整体无法合法按 GPL-3.0 再分发 ⇒ 第 32 项选 `or later` 的理由（"保留将来
      单向升 GPLv3 的路"）**在事实上不可行使**，继续对外宣称它等于给下游一个走不通的授权路径。
      `only` 是**如实陈述**，不是许可收紧 —— 下游本来就没有那条路。
    - **第 32 项里那句「未抄任何 TG 源码」同日证伪**：它写在第 32 项落地当天，而同一批次的
      `MenuPopup.kt`（批 38，2026-10-04）自称逐字移植 —— 两处口径互斥。**成因**：第 32 项当时的
      依据只有批 13 那批（`Theme.kt` 确实只取色值），后来的批次把参考面推到了函数体。教训与
      backlog D14 那条同型：**「未抄/看不见/不查」这类断言，写下时只覆盖了当时看到的面**。
    - **新增 `NOTICE`（为什么不是往 `THIRD_PARTY_NOTICES.md` 加一行）**：后者是 `gen-notices.mjs`
      从 `VERSIONS.env` 生成的**随包二进制清单**，第 3/4 节明确不含开发期参考件，手写条目会被
      `--check` 同步门判漂移。故源码面署名另立 `NOTICE`（手写，不分发清单）：上游 URL 与许可、
      逐文件来源与关系（逐字移植 / 逐句对着写 / 形态参考 / 取色值）、修改说明（GPL-2.0 第 2(a) 节
      要求的"显著通知"）。`gen-notices.mjs` 第 3 节加一句指针指过来（生成物因此仍与事实同源）。
    - **未做（如实登记）**：① **上游 commit 未钉死** —— 移植发生在 2026-10-02 起的批次，当时没记
      commit，`NOTICE` 里如实写「待补」；② `NOTICE` 表里「逐字/逐句」是本仓 KDoc 自己的说法，
      **未与上游逐行比对**，补 commit 时一并核；③ `LICENSE` 文件本身未动（GPL v2 全文两种口径共用，
      不区分 only/or-later，区分靠声明）；④ 未在源文件头加 SPDX 块（全仓零文件头声明的现状不变）。
      ① 已登记 [`backlog.md`](backlog.md)。
    - **性质声明**：本项与 `NOTICE` 的文本是**工程侧的事实陈述与署名**，不是法律意见；分发前若需
      法律判断（衍生认定、GPL-2.0-only 与 GPL-3.0 的兼容性边界），应由维护者请人复核。

34. **16KB 页设备测试取消（2026-10-06，用户裁定；backlog B3/E3 的这一面据此结项）**：
    - **拍板**：**不做 16KB 页设备/模拟器镜像的真机红测**，也不为此立设备道。相关契约（§16 风险表、
      §17 兼容矩阵、§11.3 第 5 条）里「红测机里常驻一台 16KB 页设备」这类**计划性表述**按新口径改写。
    - **为什么可以取消（这是判据，不是"不做了"）**：16KB 页的风险**在构建期就被机械门禁吃掉了**，
      真机红测从来只是**追加**一层证明，不是唯一防线。`node-runtime-build/scripts/check-alignment.sh`
      断言每条 `LOAD` 段 `align >= 2**14` **且** `p_offset ≡ p_vaddr (mod align)` —— 后者是 16KB 基页
      映射真正要求的那条同余关系（2026-09-29 补的「门的另一半」，只查 align 是半截门禁）；
      `build-native.sh` 对 `noden` / `bridge_native.node` 跑同一套；`check-opencv-alignment.sh` 对
      `libopencv.so` 同轨。**三个产物 + `libc++_shared.so` 的 16KB 口径都在 CI 里逐件断言**，而
      `fetch-and-build.sh` 那条链路（NDK r28c 链接器默认对齐）在 2026-09-29 的真机上**已跑通过一次**
      —— 也就是说「对齐与否」这个问题在交付物里有确定答案，缺的只是「在 16KB 内核上再验一遍」。
    - **代价照单收**：**残余缺口 = 内核真的按 16KB 基页映射时的装载行为未被实测**。已知的残余面很窄
      （门禁已断言同余关系，剩下的偏差只可能来自内核/ROM 侧与 bionic 的交互）。**这条缺口从"待做"
      改成"已知不测"**，登记在 §11.3 第 5 条（该条本就叫「残余风险（诚实缺口）」，是它的正确归宿）。
      **若将来出现真机装载失败的现场证据，本项即为重新开案的依据**（届时按现场反推，而不是靠常驻
      一台设备"预防性"验）。
    - **B3/E3 并不因此整条结项**：B3 里**不依赖 16KB 的那两件仍在** —— SELinux enforcing 上下文与
      `targetSdk` 提取策略的真机验证（它们同样只在特定设备上测得到），以及 `espresso` /
      `androidx-test-junit` 目录项的处置（用起来或删）。E3 作为"要不要立设备道"的产品面问句，
      **本项只裁掉了其中 16KB 那一半**，剩下的两件仍待拍板。
    - **顺带如实标注一条不对称**：`docs/log/2026-09-29.md:39` 记的「四条负向校验均实测变红」里
      **包含 16KB 对齐那条** —— 即门禁本身被反向证伪过（改坏会红），这是它可作判据的依据。取消的是
      **真机复验**，不是门禁。

35. **输入通道三选一：`auto`/`adb`/`root` 平级、必须显式、绝不降级（2026-10-06，用户口径）**：
    - **拍板**（用户原话「点击什么的不要按降级使用，脚本内传入 auto 表示启用无障碍，adb 则是 adb，
      root 则是 root」+ 三问三答「两者都管（含节点 click）」「两者都要（按调用 + 会话级）」
      「必须显式传，无默认」）：§9.3 的输入面从「`InputProvider` 一条实现 + 口头提过的 root/adb」
      改成**三条平级通道 + 显式选路**。取值 `auto`（无障碍）/ `adb`（Shizuku）/ `root`（`su`）。
    - **不是降级链，是判据**：指定的那条不可用 → `ERR_PERMISSION_DENIED`（带引导），**绝不改用
      别的通道**。这一条与 §9.5 的 `DEGRADED` 哲学**刻意相反**（那里是「能力受限但仍可用」），
      因为三者**可观测后果不同**：无障碍注入会被前台应用看出（`FLAG_SECURE` 类对抗、与输入法/
      悬浮窗争抢），root 注入在系统层不留无障碍痕迹，adb 注入的进程身份是 shell。对「签到/抢购/
      压测」这类场景，**走了哪条通道是语义的一部分**，静默换通道等于让脚本作者以为在测 A 实际在测 B。
    - **「必须显式」落成两处**：(1) 单次调用的载荷带 `channel`；(2) 会话级 `a11y.setInputChannel`
      设本脚本的通道。**两者都没有 → `ERR_INVALID_PARAM`**（`requiredChannel`），**不预置 `auto`
      缺省** —— 本仓别处「不传即用缺省」的惯例在这里**刻意不成立**：缺省等于「什么都没说 = 走了
      无障碍」，而那正是这套机制要消灭的静默。会话值是**显式选择的一种**（`setInputChannel` 是
      脚本自己发的帧），所以它算「说过」。
      **JS 侧不预检**（与 `engines.exec` 的 `timeoutMillis` 同一条「校验不写两遍」纪律）：
      `ChannelOptions.channel` 在类型上仍是可选（设过会话值之后省略是**合法**写法，类型系统看不见
      会话状态），判据只有一处 —— 宿主 handler。
    - **会话态挂「连接」而不是「handler」**：`A11yNamespaceHandler` 是**全局单例**（一个
      `BridgeRouter` 挂一套 handler、所有脚本共用），字段级会话态会让脚本 A 设的通道漏给脚本 B
      —— 一个**静默的跨脚本串扰**。改为 `InputChannelSession`（`AbstractCoroutineContextElement`），
      `NewlineFrameServer` **每连接建一个**、作为上下文元素传给该连接的所有帧任务：隔离是
      **结构上**的（同连接共享、跨连接天然不共享），不靠人记得清。
    - **节点动作在非 auto 通道降级为坐标注入**：`click`/`longClick`/`scroll` 在 `auto` 走节点语义
      （`ACTION_CLICK`/`ACTION_LONG_CLICK`/`ACTION_SCROLL`）；`adb`/`root` **没有节点语义可用**
      （shell 面只有 `input` 命令），改为解出节点 `bounds` 再注入（点中心 / 按方向在节点内划一条，
      手指方向与内容方向相反）。语义是「点这个控件**所在的位置**」，不是「对这个控件发 action」。
      **`copy`/`paste` 不在此列**：它们是纯语义动作、没有第二条通道可选，所以**不要求** `channel`
      （实现上从 `nodeAction` 拆出 `refAction` —— 复用会让它们开始要一个没有意义的参数，
      `A11yNamespaceHandlerTest` 当场抓住过）。
    - **shell 面只有直线**：`input tap` / `input swipe` 两个原语，**没有轨迹** —— 经 `adb`/`root` 的
      `gesture` 逐笔画串行注入、每条只取首尾两点。真轨迹要 `sendevent`（按设备事件节点写，**未落地**）。
      这是 shell 面的**真实上限**，写在 §9.3 与 facade KDoc 里让调用方知道，而不是假装发了轨迹。
      `canPerformGestures` 同理：`auto` 问服务能力位，`adb`/`root` **没有对应开关**（能不能用取决于
      进程身份，而那正是该通道被接线的前提）故恒 `true`；真正的失败以**命令退出码**形式出现在动作
      调用里，**不折成 false**（false 的语义是「系统拒绝这次注入」，与「这条通道现在没了」是两回事）。
    - **引入 Shizuku 依赖（本项最重的一笔）**：`dev.rikka.shizuku:api` + `:provider` 13.1.5，
      落 `gradle/libs.versions.toml`（**冻结文件**，本项即维护者授权）与
      `platform/capabilities/build.gradle.kts`。**为什么非要它**：应用自己 fork 的 `sh` 身份仍是
      应用 uid，`input` 注不进事件；要让注入以 **shell uid** 发生，必须借一个由 adb 启动的服务进程
      —— Shizuku 就是那个服务。**为什么代码里走反射而不是 import**：JVM 单测跑在 mock android.jar 上，
      碰 `Shizuku` 静态初始化即炸；反射的失败面（类不在/签名变了）**全部折成 `ERR_PERMISSION_DENIED`**
      —— 「没装」与「版本不兼容」对用户是同一句话：去装/去更新。
      **provider 的 `<provider>` 节点必须显式声明**（AAR 只带 meta-data 与类，不带节点；
      解包 `provider-13.1.5.aar` 实测），authority = `${applicationId}.shizuku`（Shizuku 侧按类里
      硬编码的 `.shizuku` 后缀找），`exported=true` + `moe.shizuku.manager.permission.API_V23`
      （signature 级，只有 Shizuku 管理器持有）是它的**设计形态**，不是漏配 —— 这是本应用**唯一**
      exported 且非系统绑定权限的组件。**依赖停更于 2023-09**（Maven Central `lastUpdated=20230921`），
      锁最新版；升级 = 一件事一个提交。
    - **未真机验证（诚实缺口）**：设备道 2026-10-06 已裁（第 34 项 / backlog B3/E3）。JVM 侧钉住的
      是「命令怎么拼、退出码怎么判、通道怎么映射、缺席怎么拒」；**真机上装好 Shizuku 后能不能注进去、
      bounds→坐标这条映射准不准，尚无人跑过**。真机第一次使用应当拿 `adb`/`root` 与 `auto` 对一遍行为。
    - **`adb` 通道的接线是探测式的**：`PlatformWiring` 只在 `ShizukuInput.isAvailable()`（装了 **且**
      服务活着，两问都要 —— 只问「类在不在」会把「装了但没启动」报成可用）为真时登记该通道；
      `root` 恒登记（走既有 `su -c`，真无 root 时命令自己失败）；`auto` 恒登记。**登记与否只决定
      「这条通道现在有没有」，绝不改变调用方选的那条**。

36. **`ui/` 与 Telegram 的关系定性更正：不是「衍生作品」，是「前端 UI 实现与风格参考」（2026-10-06，用户裁定）**：
    - **拍板**：`ui/` 的对外定性从第 33 项写的「含**衍生自** Telegram Android 的部分」改为
      **「前端 UI 的实现与风格参考自 Telegram Android」**；许可口径**维持 `GPL-2.0-only` 不变**，
      只改**理由的写法**。第 33 项正文一字不动（只追加，见上），本项记这次更正与它的依据。
    - **为什么原来的定性是过度自称**：跟踪树里**零 vendored 上游源文件** —— `find . -name '*.java'`
      只命中 `.gradle/` 缓存、跟踪面零命中；`ui/` 全部是 Kotlin / Compose 重写（上游是 Java +
      Android View）。实际借用的是**版式尺寸、间距层次、色值与键名、缓动控制点**，外加**少数几处
      算法步骤**（底栏宽度分配、菜单错相浮现、删除粒子）。把这一组关系称「衍生作品」比事实更重：
      **它把定性问题当成已决问题写进了对外文档**，而「是否构成衍生」正是本仓反复声明**不做**的
      那个法律判断（第 33 项末句的性质声明）。
    - **`only` 的理由随之改写（结论不变，论证换了）**：原写法是「GPL-2.0-only 与 GPL-3.0 不兼容
      ⇒ `or later` 事实上不可行使」——**这句话把结论挂在了那个未经复核的法律判断上**，而它正是
      本仓不做判断的那件事。新写法：**`only` 是本仓自己的保守选择** —— 在参考面未经复核前，
      不对外附加一条可能走不通的授权路径。两处落地：`README.md` 许可段、`NOTICE` 第 1 节。
    - **上游锚定（取代第 33 项「未做①：上游 commit 未钉死」）**：锚 **12.10.6（build 7112）**，
      commit `f2908b14133bbffbf7ab04f641ecb5faf533242`（发布 2026-09-30T17:51:11Z）。依据是
      「参考发生在 2026-10-02 起的批次，当时上游最新版即 12.10.6，且它至今仍是 master 最新提交」
      —— 两端都成立，不是推测。**不用 release tag 作锚**（用户裁定）：上游 tag 命名不统一
      （`release-9.7.6_3721` 与 `release-11.4.2-5469` 两种分隔符都出现过，且没有 `release-12.*`），
      钉一条会漂的字符串不如钉 sha。第 33 项「未做①」据此结项，`backlog.md` A9 同步结项。
    - **GPL-2.0 第 1 节的两件义务补进 `NOTICE` 第 2 节**：①版权声明（`Copyright (C) DrKLO/Telegram
      项目贡献者`，来源即上游仓库 —— 上游 `LICENSE` 是 GPL-2.0 全文，**未另附**单独的版权持有人行，
      照实写明而不是自己编一个）；②担保免责声明。这两条**与「衍生与否」无关**：只要用了受版权
      保护的表达，就是分发时的硬性义务。第 2(a) 节要的「改动日期」一并补上（2026-10-02 起）。
    - **许可正文随包（纯机械缺件，同批补）**：APK 的 `assets/third-party/` 此前只有
      `THIRD_PARTY_NOTICES.md` + 七份第三方原文，**缺 `LICENSE` 与 `NOTICE`** —— 而包内那份
      `THIRD_PARTY_NOTICES.md` 第 3 节正写着「见 `LICENSE`」「见 `NOTICE`」，**这两个链接在 APK 里
      是断的**，同时「随附本许可副本」（§1(c)）也没兑现。`prepareNoticesAssets` 增加这两份的拷贝
      （仓库根同名，改名即断链），断言 8 件 → **10 件**。`prepareNoticesAssets` 自己那段 KDoc 里
      「只在仓库里放一份、装到用户手机上就没有，等于没声明」对本仓自己的两份**一字不差地成立**，
      先前只是没想到。
    - **未做（如实登记）**：① `ui/` 里 25 处「逐字/照抄/原样/复刻/移植」措辞未改（其中 10 处带
      具体上游类名）—— 按本次分级，多数应写成「按 TG 的 X 规格」，但那是纯注释改动且触及 `:ui`
      多个文件，另开一批；② 与上游**仍未逐行比对**（`NOTICE` 里的诚实登记保留）。

2026-09-30 拍板（外部审查整改步骤 7；非 §18 编号项，原口径不涉）：

13. **`images` 匹配链路提速方案**（2026-09-30 评审拍板；A2–A4 实测 ❌ 后的出路裁决）：
   **2026-10-01 追加（`findFeature` 四修；本项标题是"提速"，但 findFeature 那四条是
   正确性修复 —— 记在这里是因为同一个算子族、同一轮实测）**：
   - (a) **场景侧 ORB 配额按短边分档** —— 短边 >640（整屏截图形态）抬到
     `nfeatures=8000 / edgeThreshold=10`，小场景保持缺省 1000/31。病灶：整屏 1000 个
     点全被高对比区吃掉、模板区一个不剩 → **恒 found=0**（这就是真机 UI 上 findFeature
     恒假的原因）。37 缺省值让细条状 UI 被整个切掉，一并抬到 10。代价 29.1 → 46.0ms
     （真机 ×7 中位）—— 与「多花 17ms 换掉一个恒假的算子」相比划算。模板侧**不抬**：
     小图配额从来不是瓶颈（同 1000 档的点一个不落），且缺省值让 host 既有夹具逐字不变。
   - (b) **内点铺开度门** —— 内点在模板坐标里任一边跨度 <10% 判未匹配。假阳的形状就是
     这个：几枚误配凑出同一偏移、inl 够 4 个、conf 还漂亮（真机实测 200×150 报错
     208.7px / inl=7 / conf=0.64），但模板侧坐标只占 5%×3%。门槛取**任一边**够
     （两边都要求会把「横向铺开、纵向只有一行字」的真命中挡掉：真机 300×150 的 y 跨度
     只有 0.107）。
   - (c) **命中位置 = 模板中心**（中位偏移 + 模板半宽高），**取代原「内点在场景中的
     质心」**。质心只在"内点在模板里均匀铺开"时等于模板位置，而 ORB 的内点从来不是
     均匀的（只有文字行/图标边缘出特征）→ 真机偏 **82.4px**（540×600）/ **60.8px**
     （300×150）。改后同批样本 0.0~0.8px。契约字面（§9.2）本就是"模板中心"，
     这是把实现拉回字面，不是加精度。
   - (d) **薄条/小模板零关键点垫边重试** —— ORB 的 `runByImageBorder(kp, size, et)`
     在某边 ≤ 2·et 时清空该层**全部**关键点，与内容无关；缺省 et=31 → 短边 ≤62 的
     模板恒零关键点（1080×60 工具行、120×90 图标面板全中）。**「按短边反解 et」被
     实测否掉**（预筛过了点仍进不了匹配：粗八度 patch 被模板边框裁掉，hamming 19~64、
     ratio 恒 ≥0.75）；**反射复制 32px** 管用（短边 +64 越过门槛）。只对"缺省 ORB
     一个点都没给"的模板生效（有点的模板逐字节不变）。448 样本：对 48→78、错 2→5
     （3 个是场景真有重复区）。重试路径另挂**像素复核**（报出位置严格坐标处
     TM_CCOEFF_NORMED ≥ 0.6；探针实测真命中恒 ≥0.909、纯编 ≤0.501）。
   - **诚实边界**：370×80 纯文字行、48×48 纯色图标仍 found=0 —— 真·无特征，诚实的
     未命中；640 分档门槛按整屏截图形态调，中等裁剪（540×600）找 200×150 仍会漏。

   背景 = §7.7 实测 A4 933.6ms / A2 1912.8ms 双 ❌（判据 <40ms / <700ms）。评估中
   被**否掉**的两条：
   - **帧内缓存频谱**：bench 的 A2 是同帧**同**模板 match×2，看着第二次免费 —— 真实
     脚本多半是同帧**不同**模板，零收益；且 `cv::matchTemplate` 的内部频谱不暴露、
     不可跨调用复用，要真缓存得手写 DFT 相关 + 积分图，数值风险不值。
   - **灰度直配（P0a）**：阈值/置信度语义会漂（灰度化把色差折进亮度加权）——
     「报出去的数字与精确路径一字不动」这条守不住，放弃。
   **已拍板（2026-09-30）四件套**：
   - (1) **金字塔粗筛 + 原像素精配**：灰度 0.25×/0.5× 上只提名 ≤K 候选（thr−margin
     带宽 + NMS 压重复），逐候选回原 4 通道小窗重算 —— 坐标/置信度全来自原像素，
     语义不动；小模板（短边 <80px）/纯色/平噪走精确路径。差分双跑门（host
     `host_match_test`）对拍两条路径；`AUTOSCRIPT_MATCH_FORCE_EXACT=1` 现场回退。
   - (2) **两匹配方法加可选 `region`**（复用 findColor 的 `resolve_region` 判据；
     **region 比模板小 → `ERR_IO`**、越界 → `ERR_INVALID_PARAM`、坐标恒全帧口径）——
     小模板进不了粗筛，缩窗是它唯一的提速出路（bench 决定性行 = 48×48 @ 300×150）。
   - (3) **计算出锁**：`g_mu` 只盖帧表查找（浅拷贝两个 Mat 头即放锁）—— 帧入表后
     不可变（十算子产出新帧不改原帧），900ms 级 match 不再把 release/findColor 关在锁后。
   - (4) **调参口走环境变量**：`MIN_TEMPL_SIDE`/`MARGIN`/`MAX_CANDIDATES` 进程起始
     读入（真机扫参免重编 so），缺省与原 constexpr 逐字相同。
   **判据 <40ms 不改**：region 实测数字回来之前不谈口径（改判据是拍板动作，不混在
   实现里）。附护栏 **频率门**（模板「缩小→放大」自检 <0.8 → 精确路径）是差分门
   首跑抓到的 i.i.d. 噪声假 miss 的修法 —— 方向保守：挡错只损失速度。
   挂起不排期：多核条带切分（独立实验）、4→3 通道、`WITH_OPENCL`（两道门 + 体积代价）。
   **同日真机复测落点（只追加）**：A2 计算段 1912.8 → **171.75ms ✅**；A4 全帧 933.6 →
   **62.98ms**（仍差 1.6×）；**region 两行 11.77 / 16.57ms ✅**；`FORCE_EXACT` A/B 验证
   回退阀 ≈ 旧行为（892.3ms）。~~**判据 <40ms 改不改 = 待拍板**（全帧 ❌ vs region ✅ 的
   口径之争，数字在 §7.7 复测块）—— 本项只记方案拍板，口径另议。~~
   **同日第三次实测后已裁（只追加，上句划线保留原貌）**：
   - **本项「小模板（短边 <80px）走精确路径」的阈值口径被同日评审 patch 改写**：
     `MIN_TEMPL_SIDE` 80→48、`kMinCoarseSide` 24→12、0.25× 候选带宽 +0.05
     （commit `852fb45`，外部 `vision-optimized.patch` 完整验证轮后采纳）——
     370×80 因此从 0.5× 升 0.25× 粗筛，全帧 62.98 → **24.47ms**。
   - **判据行裁决：`matchTemplate 1080p <40ms` 按原全帧口径转绿（形态注明）** ——
     依据 370×80 形态 24.47ms，**判据原文一字未改**；48×48 形态不进绿字（真机
     内容双门拦截恒精确 948ms，如实 ❌，region 16.9ms 推荐）。数字见 §7.7 三次实测块。
   - needle prep 缓存（`g_match_cache_mu` 独立锁）随 patch 一并采纳：模板端准备
     <1ms/次、真机不可测，账在代码评审面（锁序 `g_mu→cache_mu` 已核无反转）。
   - **同日第四次实测后追加（相位探针门，commit `0ec6ef4`，PR #12）**：附护栏
     「频率门 0.8 静态值」被**相位探针门**替换 —— 按粗筛栅格相位反射填充互打取
     真位置 ±2 窗最差分，每调用 `floor = min(thr − 粗带宽 + headroom, 0.97)` 绑
     带宽（静态 0.8 与 thr 脱钩，高阈值下会放行够不着候选带宽的模板 = 假漏；
     子像素相位错位是静态对齐自检测不到的塌陷源）。方向仍保守（拦错只损失速度）。
     另加自适应 K（精配预算定容 8×96×398，窗小 K 升 32）+ 新 knob `HEADROOM`
     （0.05），候选带宽/地板单源 `coarse_margin_of(sc)`。真机 A/B 归因干净
     （A4 25.37ms / A2 95.43ms，B ≈ 三次 B），**判据转绿口径与 48×48 ❌ 形态均
     不变**。数字见 §7.7 第四次实测块。

   - **2026-10-01 追加（FastPath 12a + 场景端粗筛缓存，commits `a22fbfd`/`8d20500`，PR #13）**：
     上面第四次块的「相位探针门」基线之上再叠两件，**不动本项已拍的任何口径**：
     - **FastPath(12a)：提名唯一 + 主峰高置信 → 精配窗收窄一档**。触发 = NMS 后
       `cands.size()==1`（含「第二峰过不了带宽」，探针 370×80：peak1=0.979、
       NMS 后 peak2=0.728 < 带宽 0.75）且主峰 ≥ `thr+0.05`；动作 = pad 由常态
       `ceil(1/sc)·kPadk+2`（0.25× 档 = 14）→ `ceil(1/sc)·1+2`（= 6）。
       **不变式仍是本项 (1) 条那句「坐标/置信度全部回原 4 通道小窗重算」** ——
       12a 只改「窗多大」不改「报什么」；收窄使 CCOEFF 归一化分母随窗缩微变，
       饱和区实测 conf 仍 1.0000 同位（漂移 <1e-4）。差分门 case6d/6d2 逐字段钉住。
       与 12b（提名唯一即整个跳过精配、直接采信粗筛坐标/置信度）**划清界限**：
       12b 会把「报出的数字来自原像素」这条根挖掉 = 语义漂移，**未采纳、不进排期**。
     - **场景端粗筛缓存 `g_scene_prep`（ref → {sc, hs}）**：全帧 `cvtColor(BGRA2GRAY)`
       + `resize(sc)` 按 (帧, 缩放档) memoize。依据与本项 (1) 条同源 ——
       **帧入表后不可变**（十算子产出新帧不改原帧），与 needle prep 缓存（第三次实测
       随 patch 采纳）逐字同一条。**只缓存全帧**：region 是浅视图，其灰度/缩小与
       「先全帧再裁」在小尺度边界有舍入差，缓存键得带 region 才等价 —— region 本来
       就是低延迟出路，不走缓存（原样现算）。**纯 memoize**：结果只由 (帧, sc) 决定，
       缓存命中与否不改变任何出参。锁序 `g_mu → g_match_cache_mu` 与既有两处一致；
       release 同钩子清（帧没了图也没了）。A2「一次截图两次匹配」是它的主消费方。
     - **先算账再动手（方法留档）**：动手前把 23.73ms 拆成 场景 cvtColor ≈5.9（25%）
       + resize ≈1.6（7%）+ 粗筛 ≈11.4（48%）+ 提名/精配 ≈4.8（20%）——
       方法 = region 扫掠线性拟合（斜率 7.4ms/Mpx、截距≈0）+ 组件直测 + HIT/miss
       同面积对消；**这个拆分决定了 12a 的上限（精配段 20%）与场景缓存的账面（前两项
       32%）**，也决定了①（1/16× 粗筛）不值 —— 1/16 省的是粗筛那 48%，但提名爆量
       （`ncand=8`，假峰吃满 K）把精配段翻几倍，端到端 48.1/29.6ms 全部劣于现行
       0.25× 的 10.6ms（host `c16_full` 探针），**数据否掉、不做**。1/16 若再议，
       得先有压住提名爆量的手段（如 1/16 只做「有/没有」预判、坐标仍由 0.25× 定）。
     - **判据与形态口径完全不动**：A4 全帧 370×80 形态 25.37 → **19.82ms** ✅（判据
       <40ms 原文不改，绿字口径同第四次）；48×48 形态两处改动都不进其路径（相位门
       双拦恒精确），867.5ms† 同判 ❌，出路仍是 region 16.8ms。
     - **同会话三方 A/B/C 归因（so = 各 commit 的 CI 产物，交替顺序跑）**：
       | 项 | main | 仅 12a | 12a+场景缓存 |
       |---|---|---|---|
       | A4 370×80 全帧 | 25.76ms | 23.96ms | **20.23ms** |
       | A4-region 370×80 | 10.78ms | **8.68ms** | 9.21ms（噪声‡） |
       | A2 decode+match×2 | 96.74ms | 92.73ms | **89.26ms** |
       12a 单独 = A4 −1.80 / region −2.10 / A2 −4.01；场景缓存再叠 = A4 −3.73 /
       A2 −3.47（≈3.7ms/次重复 match，= cvtColor+resize 生产联合实测）。
       ‡region 不走场景缓存，两 so 该路径代码逐字同 → 差为噪声。数字与口径
       见 §7.7 第五次实测块 + `design-status.md` 流水 2026-10-01 条。

   - **2026-10-01 再追加（大模板形态：相位平均粗模板 + 移除 headroom，commit `34fd80e`）**：
     **本项 (1) 条那句「相位探针量真位置最差分」的口径被改写 —— 门量的对象从
     「相位 0 的 resize 产物」换成「粗筛实际用的相位平均模板」**，理由是同源：
     门若与被门控的对象不同源，门就是在回答另一个问题（本轮第一版正是如此 ——
     模板已换成平均版，门还按相位 0 的 0.7180 挡人，自己挡自己）。
     - **病灶**（真机同 OpenCV 实测）：粗筛假 miss 不是粗模板"太好"，是它**只对住了
       一个相位**。300×150 @0.25 各相位粗分 `1.0000/0.9007/0.6926/0.8943`，thr=0.9
       带宽 0.75 —— 0.6926 够不着 → 提名层空手 → 回精确路径 473~1467ms。
     - **修法 = 对 nuisance 参数做平均**（匹配滤波标准解）：16 相位（0.25×）/ 4 相位
       （0.5×）的粗图按内容原点对齐后取平均当粗模板。最差相位 0.6926 → **0.8086**
       （0.5× 档 0.8269 → 0.9514）。**粗分整体下移**（best 1.0 → 0.97）使提名略保守，
       这正是要的方向。风险与界（探针 `pavg.cpp`）：对结构起支配的模板（UI/文字/图标）
       抬相位地板；对 i.i.d. 高频噪声模板会把结构抹平 —— 那类模板现状本就该被挡回
       （相位 0 自匹配虚高 1.0，任何相位错位都塌），平均只是把"虚高 1.0"换成"诚实的低分"。
     - **headroom 移除**：`floor = min(thr − 带宽, 0.97)`，`AUTOSCRIPT_MATCH_HEADROOM`
       调参口一并删除（**本项第四次追加里「+ 新 knob `HEADROOM`(0.05)」一句作废**，
       保留原貌不删）。依据：带宽之上再留余量是重复上保险（提名 ≠ 命中，候选还要过
       带宽 + 精配在原图重算），300×150 相位 0.8086 对带宽 0.75 只差 0.0086 却被
       floor=0.80 挡回，代价 473~1467ms、收益为 0。
     - **不变式不动**：粗筛仍只提名，坐标/置信度全部回原图 4 通道窗重算 —— 大模板
       四例（300×150/200×150/370×80/120×90）全 found=1、Δpos=(0,0)、Δconf=0.0000
       （对照精确路径 498~767ms）。实测 300×150 **1467 → 13.33ms**、200×150
       **1360 → 10.78ms**。判据行 `<40ms` 原文一字未改，形态注明口径同第四次。

   - **2026-10-01 三次追加（数字口径与可达率的复测，只追加）**：
     - **绝对 ms 不是常量**：同形态换窗口/换会话复测，300×150 得 11.7 / 11.7 / **30.9**ms
       （窗 @(60,600) / @(400,600) / @(60,1500)），200×150 得 9.3 / **21.3**ms，
       370×80 得 8.9 / 9.0ms（同会话）与 11.7ms（跨会话），120×90 9.9~12.3ms。
       差在**停止位与精配候选数**，不是测量噪声（同轮两遍逐位复现）。**判据看量级、
       结论看 A/B**（同进程同窗口的改前/改后对照）；把表里某个绝对值当常量引用
       会得到"某窗口偶发 31ms 是不是回归了"这类假问题。
     - **findColor 的 22.12 → 0.003ms 是「取首点段」**，不是整调用：全帧 `cv::inRange`
       本身就是 ≈4.3ms 地板（三档命中面整调用实测 4.33 / 4.06 / 4.02ms）。同一段在
       89.6% 面是 28.4ms（复跑 28.4~30.4ms）、0.02% 面 1.5ms、未命中面 0 —— 旧实现的
       代价随**命中数**走，这正是它错的地方。判据 <10ms 的口径是 300×150 ROI（真机 0.88ms），全帧在口径外。
     - **findFeature 的可达率不是 100%**（1080×2400 真机截图，33 个已知真位置的窗口：
       200×150 网格 24 + 文档引用窗口 9）：**命中 14、其中 13 落在真值 3px 内**。唯一
       错位置那例（@(660,1900) 报 (760,1431)）经 `matchTemplate` 复查：**真值处
       ccoeff=1.0000 且是全图 argmax** —— 是该窗口的特征链没走到真值，不是"场景里
       有更像的地方"。反向的 19 例恒 found=0 分三类（`scenekp` + `featwin2` 交叉核对）：
       **A 真值处场景侧零关键点 6 例**（配额 8000 抬到 20000/40000 后仍有 5/4 例为 0；
       真值处 ccoeff 最小 0.99993 —— 图上确实有，是特征匹配对无纹理区域的固有边界）；
       **B 模板侧缺省 ORB 零点 5 例**（垫边重试后 6~57 点仍 found=0）；
       **C 两侧都有点仍配不上 8 例**（模板侧缺省 7~124 点）。B/C 是 ORB 描述子在低
       纹理 UI 上的可重复性极限。**结论**：`findFeature` 是"能不能找到"的鲁棒定位（尺度/轻度形变容忍），
       与 `matchTemplate` 是**互补**的两条链而非替代：后者同批 33 窗口命中 33/33，
       但位置唯一只有 25/33 —— 其余 8 例经 `dupchk` 复查是**像素级重复副本**
       （报出窗与真值窗逐像素最大差 0~1，屏幕里真有两块一样的地方，argmax 挑另一份），
       与三次实测里 48×48 报 (57,751) 那条同源。两条都写进 §9.2 与 §7.7，
       别让读者以为四修之后 findFeature 变成了万能定位器。

12. **wire 面单一事实来源 = `bridge/schema/wire.schema.json`；与 §12.4 的 d.ts 分工**：
    - **schema 管 wire 面**（每 ns 的方法表 + aliases + dynamicSinks + facade 归属），`generate.mjs` 双发射 `bridge/js/src/generated/wire-types.ts` 与 `:domain` `WireMethods.kt`（生成物入库、`--check` + CI `git diff --exit-code` 双门）；19 个 handler 的 `methods()` 申报单源指 `BY_NS.getValue(ns)` —— 表不手抄，杜绝「申报与 `when` 两份手抄互相漂移」。
      对账三门分工：`wire-schema.test.cjs` 四向（生成物同步 / facade→schema / register↔schema / 申报↔schema + 死分支 aliases 真伪）、`wiring-table.test.cjs` 表↔schema、`pull-wire`/`event-wire`/`err-catalog` 各管自己的拉取环与错误目录。
    - **d.ts 仍是对外 API 评审面（§12.4 口径不动）**：d.ts 描述脚本作者看得见的 TS 形状（参数/返回/重载），schema 描述桥线上跑的 wire 名 —— 对象不同，不合并：把参数形状塞进 schema，生成器就得长出第二套类型系统；把 wire 名塞进 d.ts，内部协议就变成了公共 API 承诺。两份都入库、各有一道门。
    - **正则测试处置**：`wire-reconcile`（花括号计数啃 `when` 块、全局方法名集合）删 —— 底账脆且不认 ns 归属；`wiring-table` 换表↔schema 基；`jni-names`/`docs-surface` **保留**（JNI 编译面 / 文档表面积门，不是镜像测试，偏差与理由在此记明）。
    - **`bridge/js/dist` 出库**：tsc 产物改构建产物口径 —— 每次 build 弄脏工作树的 38 文件消失，代价是 CI jvm-tests job 与本机都要先 `npm --prefix bridge/js run build`（两处报错文案已点名命令）。

2026-09-30 拍板（外部审查整改步骤 3；非 §18 编号项，原口径不涉）：

11. **JSON codec 合一 = `:domain` 手写值族 `DomainJson`；kotlinx.serialization 评估后不采纳**：
    仓内 5 个手写 codec（`A11yBridgeJson` 步骤 4 迁入、`NpmBridgeJson`、`EngineBridgeJson`、`TinyJson`、内联 `WmJson`）与 `JsonLine` 的递归下降 Parser 全部收敛到唯一 codec `DomainJson`（六值族 `Value` + `decode/decodeObject/encode/encodeParsed` + `reqStr`/`opt*` 字段读取族），调用点只换名不改形状 —— `Map<String, Value>` + 抛 IAE → `RpcNamespaceHandler` 折 `ERR_INVALID_PARAM` 的口径不变。
    不选 kotlinx.serialization 的理由：① 全仓零该依赖，选它 = catalog/插件面 churn（根 `libs.versions.toml` 冻结，审查亦要求零改动）；② 现网 60+ 调用点已是 `Map<String, Value>` + `requiredStr` 语义，迁移近似改包名，却要引入 `@Serializable` 注解 + `Json {}` 解码器配置两套心智；③ 动态 wire payload（桥载荷、jsonl 行）的目标是 `Map`/值域裁剪，不是 data class，@Serializable 的强项用不上。
    备选评估到此为止。
    边界两处不计入「多头」：`JsonLine`（scheduler persist）保留为**薄件协议垫片**（~80 行：冻结行格式四型值域裁剪 + IOException 保型），编解码本体走 `DomainJson` —— 行 framing 是协议不是 codec；`ConsoleCollector` 保留自写 `handle`（非 RPC 形的 sink 面，不经错误折叠契约）。
    兼容边（同步记 design-status 流水）：`DomainJson.parseString` 拒未转义控制字符（老 `JsonLine.quote` 会把控制符直塞裸字节），且 `decode` 查尾部多余字符（老 JsonLine/TinyJson 忽略尾随）—— 存量含裸控制符的 journal 行将被拒，落在 IOException 保型内，表现仍是「行损坏」响亮失败；方向是收紧（响亮失败不静默）。

2026-09-30 拍板（外部审查整改步骤 4；非 §18 编号项，原口径不涉）：

10. **RPC 处理器基类收口 `:domain`；`*Lite` 自定义形状废除**：
    全部桥命名空间 handler（`:platform:capabilities` 14 个、`:app-service:runtime` 的 `EnginesNamespaceHandler`、`:app-service:packager` 的 `NpmBridgeHandler`、`:app` 装配包的 `WorkManager`/`PowerManager`）统一继承 `:domain` 的 `RpcNamespaceHandler` —— `handle` 为 **final**，只折叠两类：`AutojsException`（原码透传）与 `IllegalArgumentException`（→ `ERR_INVALID_PARAM`）；
    未知异常照穿（bug 显形，不伪造参数错）。子类只剩 `dispatch`（`when (request.method)` 分发 + 业务 + 参数校验）。解码 helpers（`decodeObject`/`requiredStr`/…，原 `SystemNamespaces.kt` internal 泛化）与唯一 codec `DomainJson`（原 `A11yBridgeJson` 迁入）同批入 `:domain`；`methods()` 留位步骤 7 的 schema 方法表。
    废除的旧口径：① 每个 handler 自带一份 `try { Ok } catch (Autojs) catch (IAE)` 折叠（全仓 ~60 处 `catch (e: AutojsException)`）；② `BridgeRequestLite`/`ResponseLite`（capabilities 为绕「禁直连 `:bridge:java`」自造的桥形状）与 a11y/screen/engines 的嵌套自定义 `Request`/`Response` —— 类型本就住 `:domain`，门禁理由不成立，签名换 `BridgeRequest`/`BridgeResponse`；
    ③ 各 handler 的 `mount(): NamespaceHandler` 包装（类本身即 `NamespaceHandler`，调用点直挂，`AppShell.handleLike` 适配扩展随之删除）。收敛后残留的 `catch (e: AutojsException)` 只剩基类一处 + 与桥无关的业务面（ApkRepacker/InstallCoordinator/FloatingWindow/SensorSource/Zip）；保留自写 `handle` 的只有 `ConsoleCollector`（非 RPC 形的 sink 面，不经错误折叠契约）。

2026-09-26 拍板（原 §18 第 1–7 项；2026-09-30 自 §18 迁入，原文整段保留，§号与编号不变——迁移前这些条目写在契约 §18 里、标注「已拍板（2026-09-26）」）：

1. **引擎路线：先 Node-only，还是 P0 就并行 QuickJS 沙箱？**
   推荐「P0 只 Node；QuickJS 沙箱 P1」——沙箱牵扯独立进程、白名单、双引擎 API 对齐三件大事，混进 P0 会把最小闭环拖垮。
   **已拍板（2026-09-26）：不要沙箱**——QuickJS 整条轨撤出排期，**不只是推迟到 P1**：引擎只剩 Node 一条轨，`:engine:sandbox` 不进排期（原记「模块壳保留、模块表冻结不动」；**2026-09-30 壳已注释摘除**，模块表 15→14，ModuleGraphTest 同批），§14 的「QuickJS `:sandbox` 进程」与 §16 的两条相关风险随之作废。
   **这条改的是安全边界，不是排期**：原先「第三方/市场脚本 → `:sandbox` 白名单子集」的隔离（§11 来源分级表）不再存在，第三方脚本与自写脚本**同在 Node 进程、同权**（无障碍/截屏/点击/网络全开）。防线因此只剩两条：**安装时的用户选择**（见第 7 项）与 §11 的来源提示——「靠能力授予而非进程隔离」要写在给用户的提示里，不能让人以为装来的脚本是沙箱跑的。
2. **进程模型：P0 就用「每脚本一进程」，还是先单引擎进程后扩？**
   推荐**一步到位**：反正脚本绝不能进主进程，单引擎进程的边界与多引擎池完全同构，代价只是「池容量先写死为 1」。避免二次重构。
   **已拍板（2026-09-26）：采纳推荐，一步到位**——现状即如此（`FixedEnginePool` 池容量 1，`:nodeN` 每脚本一进程），本项只是把"将来扩到 1-3"那条路确认成默认方向，无代码改动。
3. **分发定位与 Play 态度？**
   推荐完全避开 Play Store（specialUse FGS / SCHEDULE_EXACT_ALARM / MANAGE_EXTERNAL_STORAGE 政策冲突），官网/F-Droid/APK 直下。若你仍想上 Play，需砍掉 specialUse 保活与精确闹钟，P0 范围要变。
   **已拍板（2026-09-26）：不发行**——非商业化项目、不分发，故 Play 政策冲突面（specialUse FGS / 精确闹钟 / 全盘存储）**根本不存在**，上面那组"若上 Play 要砍什么"的代价不用付，保活与 `SCHEDULE_EXACT_ALARM` 原样保留。落地形态 = 本机自装 APK；Play/F-Droid/官网分发轨不进排期。
4. **ICU 取舍：全量 ICU（完整 Unicode/时区/国际化，体积 +20MB 级）还是配 `--with-intl=none`（体积小但字符串/时区残缺，自动化和 UI 场景产物不友好）？**
   推荐**全量 ICU + 裁剪为所需 subset**（也可放 assets 按需加载），自动化 app 大量依赖正则/时区/日期格式化。
   **已拍板（2026-09-26）：只要中文 + 英文**——即 locale 面收成 `{zh, en}`，既不停在 `none`（那样连 `zh-CN` 的 `Intl.*`/`toLocaleString` 都不可用，等于还是残缺），也不背全量的 +20MB。落点是 Node 构建旗标 **`--with-intl=small-icu --with-icu-locales=zh,en`**。**旗标已改（2026-09-26，同日）**：`node-runtime-build/scripts/fetch-and-build.sh` 由 `--with-intl=none` 换成上述两行；
   数据源是仓内 canned ICU（`deps/icu-small/` 带 `README-FULL-ICU.txt` → `configure.py` 走 `canned_is_full`），**不联网下载 icu4c**，`root` 由 configure 自动并入。
   跟进（构建轨，Actions 跑，本机不编）：旗标已改 → **重编已过（run `36185853302`，success，2026-09-25T23:01Z，约 2h34m）** → **体积差已量并回填 §15**：`libnode.so` 70,725,976 → **81,950,376 B（+11,224,400 B = +10.70 MiB = +15.87%）**，取证两个 `node-slice` artifact（基线 `36153816811` intl=none / 新 `36185853302`）+ `config.gypi`（`icu_small=true`、`icu_locales=en,root,zh`、`icu_path=deps/icu-small`、`icu_ver_major=78`）。
   旧估数 "~10MB+" 是 small-icu **默认面**，`zh,en` 实测比它还略高一点（ICU 数据不是按 locale 线性摊的）。`RISKS.md` §3 同批改成「已改 + 已量」。**仍待设备**：`Intl.DateTimeFormat`/`Collator` 在 zh/en 上的运行期实测（arm64 二进制本机跑不了，归 §8b 那批真机账）。副作用要写明：`toLocaleString('ja_JP')` 之类非 zh/en locale 会回落 en —— 脚本作者该知道这不是 bug。
5. **无障碍服务与脚本进程共享与否的极限形态**：本设计定案「a11y 在 `:main`、脚本在 `:nodeN`」。若未来遇到「无障碍回调海量 + 脚本高频读树」压垮 `:main`，可演进出
   `:accessibility` 第三进程（§9.1 的接口已留好接缝）。P0 不做——保持最少进程数。
   **已拍板（2026-09-26）：采纳推荐，P0 不做**（接口缝照留；真出现压垮证据再切）。
6. **UI 宿主策略**：脚本 UI 用「`:main` 渲染原生 View」还是「脚本自带 WebView（ui_web）」为主？
   推荐**两者都留、原生优先**（原生 View 桥链短、性能好；WebView 桥用于复杂富交互）。若优先做 Web 方案更快出 demo，可调整为 Web 优先。你在意的 demo 速度可以决定这个顺序。
   **已拍板（2026-09-26）：采纳推荐，两者都留、原生优先**——两个 UI 面都还在 P1 排期里（§14），本项只是定下先后：原生 XML UI 先，`ui_web` 桥后。
7. **npm 默认镜像与脚本审批严苛度**（§10 已定案技术路线，这两项是面向用户的策略）：
   - 默认 registry：推荐 `registry.npmmirror.com`（国内实测存活）——若你的目标用户全球分布则改 `npmjs.org` + 可切换。种子缓存与「离线秒装」文案都要绑定默认镜像。
   - 脚本审批默认值：推荐出厂 **global-deny**（全部 install 脚本默认拒绝，人工逐个批准）。代价是与 AutoJsPro 既有的「默认跑脚本」用户习惯不同，新旧用户需要文档/示例适配；若你更看重无缝迁移，可出厂 allow-listed 常用安全包 + 黑名单模式。
   **已拍板（2026-09-26）**：
   - **默认 registry = 官方 `registry.npmjs.org`**——不分发、不面向陌生用户（第 3 项），镜像加速不是开箱前提；用户要快自己 `setRegistry` 切 npmmirror。`HostNodeExecutor`（实际装包用的那家）与 `NpmRegistryVerifier`（交叉校验的首选）**两处缺省同批改**，
     §10.2/§10.4 的"默认 npmmirror"同批改。**第二意见的规则同时改写**：交叉校验要的是**两个运营主体**，不是"官方那一家"——首选官方时镜像做第二意见，
     首选任意别家时官方做第二意见（`secondary` 缺省跟着 `primary` 走），否则会出现 primary 与 secondary 同站、自己跟自己比也算通过。
   - **lifecycle 脚本不做出厂卡口，安装时让用户自己选**——既不是 global-deny 也不是白名单：装包时按包如实告知 `hasInstallScript`（**禁止静默**，§10.12 那条保留），跑不跑由这次安装的使用者当场决定；`requestApprove`/审批接口保留为这条选择的落点。
   - **实现落差（明写，不当已办）**：现网 T0 是 `--ignore-scripts` 全程 + npm12 `allowScripts=none`，lifecycle 脚本**一个都没跑过**，安装回执显式发 `scripts-skipped`（禁止静默那条就是为这个静默面立的）。"用户选择跑"要先有 spawn 桥（`child_process` 真执行），那是 §14 的 **P1 项**——**口径在此定死，实现排 P1**，P1 落地时按本条写交互，不重新拍。
   - **门禁侧已落（2026-09-29，存盘于本地 `node-slice`；2026-10-01 移植进 main，见 `design-status.md` 流水）**：`runScript`/`exec` 走「宿主从盘上 manifest 重算 `(pkg, versionHash)` → ledger 命中 APPROVED 才放行 → 纯 JS bin 白名单」，
     **未获批时把请求自请入队**（approvals 流 + `drainApprovals` 拉取口）让用户在审批卡上当场选，而不是干巴巴拒回或由脚本自批 —— 这就是「安装时让用户自己选」的交互落点。
     执行体是 `ScriptOpExecutor` 接缝（不复用 `HeavyOpExecutor`：lifecycle 不做 reify）。**安装侧 `hasInstallScript` 的当场告知与选择仍缺**（那要先有 spawn 桥才兑现得了"跑了"），
     见 [`design/10-npm.md`](design/10-npm.md) §10.3 的 T1 落地追记与「仍未落」段。本条**口径未变**，只是实现进度推进。

2026-09-25 拍板。编号与 §18 原编号一致（第 8、9 项），原文整段保留：

8. **截屏帧与 images 帧的通路**（§9.2 记账的缺口，决定 §7.7 表里 `captureScreen → findImage < 1s` 这条链路什么时候能兑现）：
   `screen.capture()` 出的帧与 `images.decode` 出的帧**互不通用**（两缝各发各的号，§12.2），且 screen 面既不给 `save()` 也不给 `pixel()`（字节出不了 `:main`），脚本目前**只能自己先落盘再 decode**（§12.3 示例这么写）。三条出路，入口在同一处（换 producer 或加一个 `screen.save`），代价不同：
   - (a) **`screen` 面加 `save(path)`**：把 a11y 已产出的 JPEG 字节原样落盘。设备面已经在压 JPEG 了，最小改动；代价是**有损**——`findColor` 的分量判定会吃到压缩伪影（§9.2 的契约是按分量精确夹的），"屏幕上这个色还在吗"这类判读会变钝。
   - (b) **两缝共用一个帧表**（producer 直接把帧写进 `images` 的帧表）：收益是真正的 0 拷贝直连（§7.4 所有权边界仍是每个句柄一份 Mat，变的是**发号那一侧**归谁）；代价是"帧不通用"这条纪律取消，`screen`/`images` 两个命名空间的释放语义要重新对齐（谁 release 谁背 STALE）。
   - (c) **`images` 面加 `decodeBytes(byte[])`**：屏幕字节不落盘直进 native；代价是 bytes 要过桥，§7.7 的"屏幕帧→native 0 拷贝"这条在**两个维度上**都要重新记账，且 §7.4 的多一路径 = 多一处规格要守。
   **已拍板 (b)**（2026-09-25）：只有它同时保住了"0 拷贝"与"按分量精确判定"两条被契约明确承诺的性质，(a) 切掉的是判读精度、(c) 切掉的是性能口径。(a) 不作为过渡 —— 过渡方案一旦进示例就会被抄成正式用法，而带 JPEG 往返的链路不叫「屏幕帧→native 0 拷贝」，§7.7 的买单口径不为它改。
   落地时动的是 §7.4 所有权边界（发号侧归一），`screen`/`images` 的句柄纪律届时同批重写，"帧不通用"那条纪律取消。

9. **`images.decode` 的相对路径口径**（2026-09-25 实测记账，影响 §9.2/§12.3 的示例写法）：
   `:domain` 的 `ImageAnalyzer.decode` KDoc 写着「路径解析（相对项目根 or filesDir）由实现定」，但**四层里没有任何一层解析路径**（计算核 `std::fopen`/`cv::imread` 直取、
   装载面与 `NativeImageAnalyzer` 原样透传、handler 只挡空白串）。host 侧实测把这条钉死了：传相对路径时按**进程 CWD** 解析——同一个文件，绝对写法与「chdir 到该目录 + 相对写法」都回 `ERR_IO(3)`（说明相对写法确实命中到了文件），
   而不存在的相对路径回 `ERR_FILE_NOT_FOUND(2)`。`libopencv.so` 载在 `:main` 进程里，那个进程的 CWD 是 `/`（Android 对 zygote 后代的固定行为），于是脚本写 `images.decode('part.png')` 会在根目录找一个并不存在的文件—
   —**回的是 `ERR_FILE_NOT_FOUND`，且报的路径是对的**，所以看起来像"文件真的不在"，不像"口径没定"。
   两条出路，代价不同：
   - (a) **就在契约里写明"路径必须是绝对的"**（示例改成 `/sdcard/...` 或让脚本自己拼 `filesDir`）。零实现改动，代价是 v9 的 `fromFile('part.png')` 这种相对用法在 AutoScript 直接不成立，脚本要改写法。
   - (b) **在 handler 层加一层基准解析**（相对路径按项目根 / `filesDir` 拼绝对再往下传）。保住 v9 的写法，代价是要定"基准是谁"（项目根？脚本所在目录？filesDir？）——**三选一本身又是一个要拍板的策略**，且 §9.2 的「不做路径策略」那条边界要重画。
   **已拍板 (a)**（2026-09-25）：路径必须是绝对的 —— 把"相对路径"从契约里去掉而不是猜一个基准。(b) 不给：基准三选一本身又是一个策略，且 §9.2「不做路径策略」的边界不重画。**§12.3 已按 (a) 改写**：示例路径一律绝对（`fromFile('/sdcard/part.png')`），并写明了相对写法为什么回 `ERR_FILE_NOT_FOUND`。
   跟进动作：`:domain` `ImageAnalyzer.decode` KDoc 里「路径解析由实现定」那句要收紧为"只收绝对路径"（见下）。

---

## ~~仍待拍板（原 §18 第 1–7 项）~~ **已全部拍板（2026-09-26），见上方「已拍板」第 1–7 项**

> **2026-09-30 订正（只追加，原表不动）**：本段原写「以下七项尚未拍板」是拍板前的原貌 ——
> 七项已于 2026-09-26 全部拍板，条目已迁入上方「已拍板」（编号不变、原文整段保留）；
> 台账正文在 [`design/18-19-ledger.md` §18](design/18-19-ledger.md)。原表按下保留：

以下七项尚未拍板，原文保留在 §18（现居 `design/18-19-ledger.md`），
契约正文按「推荐默认值」编写；一旦拍板，**逐项搬到这里**并在 §18 留一行指针。

> **2026-10-06 追加（只追加，两笔）**：
>
> ① 下面这张表是**拍板前的原貌**，**保留** —— 它是「拍板前长什么样」的唯一记录。
>
> ② `design/18-19-ledger.md` §18 与本节「已拍板」第 1–9 项**逐字重合到 56 个非空行里的
> 44 行（79%）**，**刻意不合并**。理由不是「两边都有用」，而是**删哪边都会丢东西**：
> §18 是**契约卷**，它那 9 项被正文引用（§10.1「§18 第 7 项」、§11 来源分级表「§18 第 1 项」
> 这类共 120+ 处，全仓 `§18` 出现 131 次）；本节是**决策记录**，本文件自己的导语承诺「条目原文整段保留」。
> 合并 = 要么让契约卷指向另一个文件里的编号（137 处引用要改指法），要么推翻本文件的
> 导语。**代价大于收益，故记一笔不合并** —— 下次复核看到这 79% 时不必再查一遍。

| 编号 | 议题 | 推荐默认值 |
|---|---|---|
| 1 | 引擎路线：P0 只 Node vs 并行 QuickJS | P0 只 Node，QuickJS 沙箱 P1 |
| 2 | 进程模型：一步到位 vs 先单引擎进程 | 一步到位（池容量先写死 1） |
| 3 | 分发定位与 Play 态度 | 完全避开 Play（官网/F-Droid/APK 直下） |
| 4 | ICU 取舍 | 全量 ICU + 裁剪 subset（**注**：构建管线当前是 `--with-intl=none`，见 RISKS §3） |
| 5 | a11y 与脚本进程的极限形态 | P0 不做第三进程，接口已留接缝 |
| 6 | UI 宿主策略 | 两者都留、原生优先 |
| 7 | npm 默认镜像与审批严苛度 | `registry.npmmirror.com` + 出厂 global-deny |

---

## 已推翻 / 已改口径

| 原口径 | 出处 | 处置 | 日期 |
|---|---|---|---|
| 「外审第 1 条（Shizuku 阻塞调用跑在 `Dispatchers.Main`）与『首次 `input tap` 挂 30s』是**两件各自独立**的事，后者只有冷置后的第 1 条挂」 | `docs/design-status.md` 2026-10-09 流水段 + `PlatformWiring.kt` 注释（`92e2461`） | **两条都推翻**（2026-10-10 真机对照实验）：打进**应用自己的窗口**时**每一条都挂**（连续 6 条 32.4 / 32.5 / 33.8 / 32.4 / 32.4 / 32.4 s，全部 `超时 30000ms`），且机理**就是**外审第 1 条那件事 —— `input` 默认 `INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH`，事件投进哪个窗口就要等那个窗口处理完；主线程正卡在 `IRemoteProcess.waitForTimeout` 上收不了 → 陪等到 30s。目标窗口**不属于本进程**时不受影响（`input tap 5 5` 打状态栏实测 2.6 s）。证据 = `ANR … Input dispatching timed out (Waited 5005ms for KeyEvent … ENTER(66))` + `/data/anr/anr_2026-10-10-07-13-35-268` 的 main 线程栈与那条链逐帧吻合。注释已改正（`c59600f`），台账按只追加纪律原地保留原文 | 2026-10-10 |
| 「`input tap` 的延迟是 2.4–2.5 s」 | 2026-10-10 会话中报出的测量值 | **是测量工具的成本，不是 tap 的成本**：那圈循环每次跑 `uiautomator dump`，光它自己就 ~2.2 s（`adb exec-out screencap` ~1.5 s 同理）。改设备侧自带时间戳（`awk` 算 epoch 差，不经过 adb 轮询）后：打应用窗口 0.042–0.046 s、打状态栏 0.038–0.043 s、经 Shizuku 控制台跑同样一条 0.053–0.081 s；回车 → 命令真的开跑 0.08 s。**与「绝对 ms 不是常量」同一条教训的第二次现形**：带探针的测量必须先扣掉探针自己的成本 | 2026-10-10 |
| `TM_CCORR_NORMED` | §7.7 表 + `:domain` KDoc | 改 `TM_CCOEFF_NORMED`（CCORR 在「画面里没有模板」时 max 仍 +0.955，阈值 0.9 直接误判命中） | 2026-09-25 |
| APK ≤ 40MB | §15 表 | 实测推翻：jniLibs 三件套未压缩 ≈92MB（ICU `zh,en` 后；此前 ≈81MB）；OpenCV 面仅 7.0 MiB、不是超支原因。三条出路见本文件「仍待拍板」第 7 项旁的 §15 记账。**2026-10-07 重定：预算改 `≤ 150MB release`，见本文件第 41 项**（原行不删，只就地标注） | 2026-09-25 |
| 「屏幕帧→native 0 拷贝」链路可走 | §7.7 表 | 两缝帧不通用，脚本当前走不通；数字是「链路通了之后」的口径。出路已拍板 (b) 两缝共用帧表 | 2026-09-25 |
| `images.decode` 相对路径 | §12.3 示例 | 四层无一层解析路径，相对写法在 `:main`（CWD=`/`）恒 `ERR_FILE_NOT_FOUND`。已拍板 (a)：**路径必须绝对** | 2026-09-25 |
| §8.7「恒真 = 明写的待接」 | §8.7 | 作废：`AppShellApplication.screenGateOf` 传 `WakeLockLedger::isHeld`，持锁判定收口 | 2026-09 |
| §7.8 步骤② 「addon 的 `napi_*` 从 libnode 动态表解析」 | §7.8 | 真机推翻：bionic 的 linker namespace **不把先做的 `dlopen(RTLD_GLOBAL)` 符号给后做的 `dlopen`**（glibc 会）——addon 实测 `cannot locate symbol "napi_add_env_cleanup_hook"`。改为 **addon 自己 DT_NEEDED `libnode.so`**（按 SONAME 命中已加载的那份，与落位目录无关） | 2026-09-29 |
| 「bionic 上 DT_NEEDED + `$ORIGIN` 足以让链接形宿主起来」 | 本文件上一行方案 | 实测**不完整**：RUNPATH 只作用于可执行文件自己的直接 NEEDED，**不作用于被依赖库的传递依赖** → 链接形宿主三处摆放一致死在 `libc++_shared.so`（`CANNOT LINK EXECUTABLE … needed by …/libnode.so`）。宿主改为**自己 NEEDED `libc++_shared.so`**（撤 `-static-libstdc++`）、保持 dlopen 形不链 libnode；addon 反之（链 libnode、静态 STL） | 2026-09-29 |
| 宿主 `-static-libstdc++`「不把 libc++_shared 推给装载面」 | `build-native.sh` LDFLAGS 注 | 作废：正是它让 libnode 的传递依赖无人解析。宿主改用共享 libc++（NDK 默认） | 2026-09-29 |
| 绝对 rpath / `LD_LIBRARY_PATH` 注入作为兜底 | 上一轮讨论 | 绝对 rpath 在 bionic 上**不生效**（`CANNOT LINK EXECUTABLE … not found`，`$ORIGIN` 有效）；`LD_LIBRARY_PATH` 只作真机调试手段，不作为交付依赖（交付形态实测无需它） | 2026-09-29 |
| 「打包整轨」 | §14 P0 | 移入后续版本（向导 UI 与 Keystore 取密随轨走）；加密资产/loader 一并移出需求 | 2026-09-23 |
| `tools/jvm-test*` 本机旁路「保留作快速门」 | CLAUDE.md 构建段 + §6 末 | **删除**（脚本已删）：本机快速门 = CI 同源 `./gradlew` 单模块任务（本机 SDK 已配置、12 任务同源可直跑，不留第二口径）；原 jvm-test-all 的「aborted ≠ 绿」纪律由约定插件 `autoscript.test-guard`（build-logic）承接 —— 测试出现 skipped/aborted 即红，环境门禁类在 `TestGuard.ENV_GATED`，其余 `-PallowSkipped=<类名>` 显式放行 | 2026-09-30 |
| `:engine:sandbox` 模块壳保留、settings 模块表按协调者冻结不动 | §6 行 + §18 第 1 项（2026-09-26 拍板附注） | **空壳从 settings 注释摘除**（QuickJS 裁撤口径不变，审查步骤 1）：`include` 注释 + ModuleGraphTest `include` 正则改锚行首（注释行不计）+ 允许集删行 + 模块表 15→14；目录留盘，复活 = 注释回 + 登记 | 2026-09-30 |
| §12.2「语义层（handler）住 `:platform:capabilities`」+「为什么 handler 不住 `:platform:system`」两层理由 | §12.2「分两层，别混」段（原两句原文见本节末引用块） | **口径反转（审查步骤 6，零新模块方案）**：handler 归位实现模块 —— 系统面十一件住 `:platform:system` 的 `SystemNamespaces.kt`（与 `SystemSpis`/契约同模块，2026-09-30 步骤 6a/6b/6d 分批迁入），`dialogs` 留 `:platform:capabilities`（`DialogHost` 实现按约定在同模块），`workManager` 归 `:app-service:scheduler`、`power_manager` 归 `:platform:system`；原理由 (1) 共担门禁组 → 校验仍单点住在 `SystemNamespaces` 工厂束（同模块一处，不各写一份），(2) 装配层双模块直连 → `PlatformWiring` 本就经 §6 包级例外二同时可见两模块、根包经工厂缝零 platform 类型（`ArchitectureTest` 依赖级门禁验证）；与 `EnginesNamespaceHandler` 住 `:app-service:runtime` 同形态（handler 归位实现模块）。§6 两行、§12.2 表 handler 列与两段散文同批改 | 2026-09-30 |

| `engines.exec` 的 `timeoutMillis` 可选（缺省交给引擎/看门狗自行兜底） | §8.6 原「仍待覆盖」段 + `bridge/js` `EngineRunRequest` | **改必填**：桥路径无人 await 终结，缺席即 `ERR_INVALID_PARAM`；到点由看门狗期限线落 `KillCause.TIMEOUT`（§8.6 期限线，判据 = 发起方等不等） | 2026-10-01 |
| `:engine:sandbox`「**目录留盘**」（2026-09-30 摘壳时的附带口径） | 本表上一则 `:engine:sandbox` 行 | **目录删除**（2026-10-01 拍板，backlog D2）：裁撤口径本身不变（§18 第 1 项「不要沙箱」），只是把空壳目录从盘上清掉 —— 目录里只有 1 个 `build.gradle.kts`（QuickJS 白名单/独立进程那条轨的残骸，P1 再实装的口子），模块早在 2026-09-30 就不在 `settings.gradle.kts` 的模块表里。**复活 = 重建模块目录 + 注释回 include + ModuleGraphTest 允许集登记**（比原口径多「重建目录」一步）；`build.gradle.kts` 可从 git 历史取回（`git show <摘除前的 sha>:engine/sandbox/build.gradle.kts`）。`settings.gradle.kts` 里那行 `// include(":engine:sandbox")` 是协调者冻结文件，本次不动 | 2026-10-01 |
| `settings.gradle.kts` 里 `// include(":engine:sandbox")` 那行注释「留作复活提示」 | 本表上一则 `:engine:sandbox` 行末句 | **注释行删除**（2026-10-01 同日拍板，承上一则）：目录既然已不在盘上，留一行指向不存在目录的注释只剩误导 —— 它引用的两个事实（空壳摘除、复活办法）在文档侧都有登记，而**冻结文件**里留死引用正是 C3 那批刚清掉的形态。复活口径随之定为「重建模块目录 + include 行加回 + ModuleGraphTest 允许集登记」（不再有「取消注释」这一步）；ModuleGraphTest 的 `include` 正则锚行首，删注释行不影响派生计数（`:domain:test` 已重跑验证） | 2026-10-01 |
| 外审 C5：「无 `CHANGELOG`」 | `docs/backlog.md` C5 | **不设 `CHANGELOG`**（2026-10-01 拍板）：变更流水已经在 `docs/design-status.md` 的「流水」段（只追加、按日期、带 § 锚），再开一份 `CHANGELOG` 必然漂成第二个事实来源 —— 本仓对「同一事实写两遍」的代价有明确判据（`ModuleGraphTest` 的派生计数就是为此立的门）。外审同一行的另外两项**已采纳**：`CONTRIBUTING.md` 与 PR / issue 模板已建（批 3）。发版流程真立起来那天再谈（口径随 §18 第 3 项「不发行」） | 2026-10-01 |
| 外审 C5：`versionName` 硬编码 `"0.1.0"` | `app/build.gradle.kts` | **保留硬编码占位**（2026-10-01 拍板）：本仓不发行正式版（§18 第 3 项），没有发版流程 —— 此刻把版本号接到 `gradle.properties` / 版本目录只会**多出一个会漂的事实来源**，换不来任何东西。改为在该行上方写明：这是占位、为什么不引第二个来源、真要发版时该怎么改（两数同改、`versionCode` 单调递增、同步 §13/§14 交付轨） | 2026-10-01 |
| `LockSigner.KeyProvider.keyBytes(): ByteArray`（应用密钥接缝的原始形状） | `LockSigner.kt`（`:app-service:npm`） | **改 `secretKey(): SecretKey`**（2026-10-01 拍板，backlog A1c）：Keystore 里的密钥材料**不出库**，`getEncoded()` 拿不到字节 ⇒ 原形状**接不上** Keystore，而 Keystore 正是设计口径的密钥存放处。给句柄则两边都成立（Keystore HMAC 密钥与测试用 `SecretKeySpec` 都能喂 `Mac.init(SecretKey)`），语义一字不变（仍 HMAC-SHA256、落盘仍 `v1 <hex>`）。**同时拍板实现落点：Keystore 版住 `:app` 装配层**（Composition Root 已依赖 `:app-service:npm`，零契约变更；`:app-service:npm` 保持零 `android.*`）。**注意：这是接缝形状，不是接线** —— 生产装配的 `lockKey` 仍是 `null`（`SECURITY.md` / §11.3 第 8 条口径不变） | 2026-10-01 |
| **「vendored npm CLI 素材从哪来」**（backlog A1 的卡点：CI 产 / 入库 / 取本机 npm 目录三选一，2026-10-01 之前未定） | `docs/backlog.md` A1、§10.1 脊梁 | **取 Node 源码树自带的 `deps/npm`，随 libnode 同批出库**（第四选项）：`fetch-and-build.sh` §9 收敛到 `OUT/npm` → `node-slice.yml` artifact → gradle `prepareNpmCliAssets` 随包 `assets/npm/` → 启动期幂等落位 `files/npm/`。**选它的理由**：素材与 `NODE_VERSION` 同一把锁（换 Node 版本时 npm 跟着走，`NPM_CLI_VERSION` 与素材 `package.json` 逐字比对，漂移当场红 —— 逼一次显式决策），不引入第二条下载源与第二套校验，且与 libnode 同批出库（同一个 artifact、同一次构建、同一份基表纪律）。**代价（明写，不当已办）**：Node 24.21.0 携带的是 **npm 11.19.0**，**低于 §10.1 脊梁写的「npm 12.x 系」** —— npm 12 的「拒绝全部 lifecycle + allow-git=none + allow-remote=none」这层**官方默认语义当前不在位**。护栏并没有因此静默消失，但**只剩一层**：`HostNodeExecutor` 对每条命令硬编码 `--ignore-scripts`（§11.1 T1 的零 spawn 主路径），它与 npm 版本无关；而**非脚本** spawn 路径的第二层兜底（§10.12 末行的 child_process 拦截 shim）本就未落。升级到 12.x 是**独立一件事**，已登记 backlog；**同日追加实测**：`nodejs.org/dist/index.json` 的 868 条官方发布里**没有任何一条携带 npm 12.x**（最新 v26.10.0 / 2026-09-21 带的是 npm 11.19.1）→「等 Node 线携带」这条升级路径**原理上不成立**，要 12.x 只能另找素材来源；改 `NPM_CLI_VERSION` 即触发全链回归。**→ 2026-10-02 改口径（第 26 项）**：素材来源换 **registry 发布态 tarball `npm@12.2.0`** —— 「与 `NODE_VERSION` 同一把锁」解除，版本纪律改由 `VERSIONS.env` 的 `NPM_CLI_VERSION` + `NPM_CLI_SHA1` 承担（改钉仍触发全链回归，§9 sha1+版本双闸）；上文原结论保留 | 2026-10-01（→ 2026-10-02 改） |
| 能力中心**明示安装体积**（决策 20「接受 + 明示」的 UI 披露面：设置页单列一段「安装体积 xxx」，2026-10-02 拍板并落地） | 决策 20（本文件第 20 项）+ §15 表注 + `backlog.md` E1 | **设置页撤下这段渲染**（批 47 用户口径「安装体积xxx那个文字去掉」）：撤的只是 UI —— 「接受超支」的预算记账、§15 证据链、`InstallSizeRead` 实测链、`InstallSizeState` 字段与 `text()` 换算**全部保留**（`CapabilityCenterStateTest` 仍钉着文案），「明示」这一半按用户口径作废；将来要再披露从 `text()` 同源取 | 2026-10-05 |
| vendored npm CLI 的**幂等锚 = 单入口 `bin/npm-cli.js` 的 sha256，命中即整目录跳过（开机路径零 IO）**（§10.2 存储布局 `files/npm/` 原文口径 + `NpmCliDeployer` KDoc） | §10.2；`docs/design-status.md` §11.2 T2/§10.2 行 | **改为「全树清单身份 + 落盘逐件复核」**（2026-10-06，backlog B9）：构建期 `prepareNpmCliAssets` 随包出 `assets/npm-manifest.json`（count/bytes/files[path,sha256]），部署侧以**规范化全树摘要**（`DirSizer.sha256` 覆盖排序后的 path+sha256 列表）为幂等身份，且**命中前先复核落盘文件集合与每件摘要**。理由：旧锚只看一个入口文件，「入口没变、依赖树被剪裁或写坏」正是 B9 要防的形态（aapt2 `<dir>_*` 那次就是整个目录消失而入口完好）。**代价明写**：开机路径不再是零 IO —— 每次启动读一遍 ~1.6k 件 / ~10MB 做摘要复核（换来的是「APK 被剪裁」在部署期就响亮失败，而不是设备上 `require` 时才炸）。清单缺失/不可读/摘要不符一律拒绝部署并保留旧树，**不退回旧校验、不降级放行** | 2026-10-06 |
| **覆盖率门设不设阈值**（backlog B6 待批项） | `docs/backlog.md` B6；`build-logic/` | **只出报告，不设阈值**（2026-10-06 批 52）：存量覆盖率未知（全仓此前零 jacoco），设门必红、红在一个没人打算立刻补的数字上，只会训练出「加豁免」的习惯。本批的交付是**让盲区看得见**（15 个模块的报告 + CI artifact）；要设阈值是**另一件事**，得先有基线数字与「哪几个模块纳入」的口径，届时按本表另起一行 | 2026-10-06 |
| 对外许可 `GPL-2.0-or-later`（本文件第 32 项，2026-10-02 拍板） | `README.md` / `THIRD_PARTY_NOTICES.md` / `bridge/js/package.json` | **收窄为 `GPL-2.0-only`**（2026-10-06，第三轮外审 L1）：`ui/` 含衍生自 Telegram Android（**GPL-2.0-only**）的部分，GPL-2.0-only 与 GPL-3.0 不兼容 ⇒ 合成的整体无法合法按 GPL-3.0 再分发，"or later" 这个选项**事实上不可行使**。原口径保留在上方第 32 项（含它当时那句「未抄任何 TG 源码」—— 该句同日证伪，批 38 的 `MenuPopup.kt` 自称逐字移植）。口径全文见本表下方第 33 项 | 2026-10-06 |
| **静态分析用什么工具**（backlog B8） | `build-logic/`；`bridge/js` | **detekt（Kotlin）+ ESLint（facade），不引 ktlint**（2026-10-06 批 52）：detekt 的 `formatting` ruleset 能覆盖 ktlint 的同一面，同时引两个只多一处版本与 baseline 口径；Kotlin 侧走 **baseline + 新增即红**（存量豁免、新违规当场红，实测证伪过），JS 侧走**规则集首日即绿**（等价语义，不引 baseline 机制）。**全仓格式化不在本批范围内**（不做存量重排）—— 要格式化另起一批，走 detekt `formatting` 或 ktlint | 2026-10-06 |
| **崩溃摘要怎么跨模块传**（backlog B11 待批项：`RunSummary` 整体折进 `RunOutcome`，还是摊平成裸字段） | `docs/backlog.md` B11；`app-service/scheduler/src/main/kotlin/.../core/IntentLog.kt`（`RunOutcome`） | **摊平成 `exitCode` + `crashSummary` 两个裸字段**（2026-10-06 批 53）：`RunOutcome` 住 `:app-service:scheduler`，而该模块的 `ArchitectureTest` 明令禁止依赖 `com.autoscript.domain.engine..`（scheduler 只许碰 `domain.scripts` 的脚本模型）—— 折 `RunSummary` 整体会**当场红门**。摊平放在 `:app` 的 `ControllerRunDispatcher`（那里本来就同时看得见两侧类型），消费端 `RunRecord.exitCode`/`crashSummary` 要的恰好就是这两个字段，折一次是纯转手、零信息增益。**若将来 scheduler 真要引擎侧类型**：改的应是那条架构门（连同它的量化说明），不是在这里塞一个「反正能编译」的字段 | 2026-10-06 |
| **APK 的 ABI 声明面**（backlog B10 待裁定：装不装得上） | `docs/backlog.md` B10；`app/build.gradle.kts`；`docs/design/03-technology.md`（§3 SDK 基线）、`docs/design/13-roadmap-budget.md`（§17 兼容矩阵） | **只留 `arm64-v8a`**（2026-10-06 拍板）：加 `ndk { abiFilters += "arm64-v8a" }`。不加这条时 APK 声明四个 ABI，而后三个（`armeabi-v7a`/`x86`/`x86_64`）**不是引擎带来的** —— 是 `libandroidx.graphics.path.so` 贡献的，引擎四件（libnoden/libnode/libc++_shared/libopencv）只在 `lib/arm64-v8a/`。声明面比交付面宽三个 ABI = 对 32 位与 x86 设备**承诺了跑不了的东西**。取「只留 arm64」而不是「维持四个」的理由：§3/§13 已把代价写明（「放弃 32 位旧机」），而「装得上、能看界面、一跑脚本才以 `ERR_FILE_NOT_FOUND` 告终」不是更友好的降级 —— 它把一次安装期就能给的答复推迟到用户配好任务之后。代价照单全收：32 位设备与 x86_64 模拟器**装不上**（Play 也按此过滤）。**P1 补 x86_64（§17 兼容矩阵）时把该 ABI 加回那一行即可**，届时引擎产物与 `prepareEngineNativeLibs` 的 ABI 子目录要同步多一份 | 2026-10-06 |
| **SDK 基线的两处漂移**（backlog 未列，本批顺带裁定：文档写 compile/target 36 而 catalog 写 35；两份设计卷写 minSdk 24 而 catalog/CLAUDE.md/`VERSIONS.env` 写 26） | `gradle/libs.versions.toml`；`docs/design/03-technology.md:16`；`docs/design/13-roadmap-budget.md:144`；`README.md` 前置条件表 | **以文档为准，改 catalog**（2026-10-06 拍板）：`compileSdk`/`targetSdk` 35 → **36**（§3/§17 的「compile&target 36（Android 16）」是更早拍下的口径，catalog 落后于它）；`minSdk` 反向 —— **26 为准**，两份设计卷里的「minSdk 24」是过期字面（`node-runtime-build/VERSIONS.env` 的 `ANDROID_API=26` 与 `CLAUDE.md`「API 26 = minSdk 冻结值」同口径，且 24 与 26 之间没有任何一项设计依赖 API 24/25）。连带：`ci.yml`（×2）与 `e2e-nightly.yml`（×1）的 SDK 组件装 `platforms;android-36` + `build-tools;36.0.0`；README 前置条件表同步。**APK 侧实证**：`aapt2 dump badging` 出 `compileSdkVersion='36'` / `minSdkVersion:'26'` / `targetSdkVersion:'36'` | 2026-10-06 |
| **CI 出的 APK 不含引擎二进制**（backlog B5：怎么把「真形态 APK」做成可复现的门） | `docs/backlog.md` B5；`.github/workflows/ci.yml`（android-build job）；`engine/node-process/scripts/build-native.sh`；`build-logic/src/main/kotlin/autoscript.engine-natives.gradle.kts` | **新开独立 workflow `engine-native.yml`，`ci.yml` 一字不动**（2026-10-06 拍板）。B5 的真堵点不是「取不到产物」而是「**没有任何 workflow 产出 `noden` / `bridge_native.node`**」：node-slice（libnode + npm）与 image-native（libopencv）的 artifact 都在且未过期，可跨 workflow 取；只有这两个引擎件从来没有生产者。所以新 workflow 自己跑 `build-native.sh`（NDK r28c + Node 头文件包），再取上述两条 artifact 喂 `LIBNODE`/`NPM_CLI_ROOT`/`LIBOPENCV` 起 `assembleDebug`，最后断言 `lib/arm64-v8a/` 四件齐 + npm 素材件数 + `aapt2 dump badging` 的 ABI 只有 arm64。**不进 `ci.yml` 的理由是分钟预算**：Node 头文件包 + NDK 722MB 下载与 C++ 交叉编译是分钟级，塞进 PR 门会把「秒级红绿」变成「十分钟才知道」—— PR 门仍由 `ci.yml` 的 assembleDebug（无引擎件、装配期只 warn）守着，真形态 APK 是独立可点的门。**诚实边界**：该 workflow 取的是 node-slice/image-native 的**最新成功 artifact**，与本次 commit 的源码未必同源 —— 它的断言对象是「装配链能不能把四件摆对、ABI 面收没收敛」，不是「引擎二进制的可复现构建」（后者归 node-slice/image-native 自己的门） | 2026-10-06 |
| **`ui/` 是「衍生自 Telegram Android 的部分」**（本文件第 33 项的定性，2026-10-06 上午） | `README.md` 许可段；`NOTICE` 第 2 节；本表上方第 33 项那行 | **改称「前端 UI 的实现与风格参考自 Telegram Android」**（2026-10-06，用户裁定，口径全文见本文件**第 36 项**）：跟踪树里**零 vendored 上游源文件**（`find . -name '*.java'` 跟踪面零命中），`ui/` 是 Kotlin/Compose 重写，借用的只是版式尺寸 / 色值键名 / 缓动控制点 + 少数几处算法步骤。原定性把**本仓自己声明不做**的那个法律判断（「是否构成衍生」）当成已决写进了对外文档。**许可口径不变**（仍 `GPL-2.0-only`），只把 `only` 的**理由**从「上游逼的（不兼容 GPL-3.0）」改成「本仓自己的保守选择」。第 33 项正文一字不动 | 2026-10-06 |
| backlog **A9**「`NOTICE` 表里『逐字 / 逐句』的说法未与上游核实」（2026-10-06 登记） | `docs/backlog.md` A9（已移除）；本文件第 36 项 | **不做了，整行移除**（2026-10-07，用户裁定，口径全文见本文件**第 37 项**）：那是**署名措辞的精度**问题，不是许可义务 —— 第 1 节要的版权声明与担保免责、第 2(a) 节的修改说明与日期已于第 36 项补齐并随 APK 出。核实做了一半就停：抽查的几处（`cascade()` / `onMeasure` / 缓动常量 / `SIZE = 48` / 缩放时长）**都站得住**，但抽查出两处**上游不存在的类名**（`TopicsLayoutSwitcher`、`ReverseOrder`）也一并「不追」（同属注释举例，非署名义务），如实记在第 37 项 | 2026-10-07 |
| `ADB_INPUT` 三态**恒 `DEGRADED`**（「未就绪时输入走无障碍手势」这条降级路径真实存在） | `AndroidSystemStateReader` 的映射表；`PermissionCenter.guideText(ADB_INPUT)` 的文案 | **改口径**（2026-10-07，本文件**第 38 项**）：批 61（§9.3 三通道三选一）**已经废掉那条降级路径** —— 指定 `adb` 而不可用就是 `ERR_PERMISSION_DENIED`，绝不改用别的通道。故改成真探测：`ShizukuInput.isAvailable()` 就绪 → `GRANTED`，不就绪 → `DENIED`（与无障碍/root/使用情况访问同档）；文案里那句降级承诺同批删掉 | 2026-10-07 |
| backlog **D7**「大文件余量：`AppShellApplication` 与 `Scheduler` 还有没有值得付的刀口」（2026-10-02 登记） | `docs/backlog.md` D7；`app-service/scheduler/.../core/Scheduler.kt`；`app/src/main/kotlin/com/autoscript/AppShellApplication.kt` | **结项**（2026-10-07，口径全文见本文件**第 39 项**）：`Scheduler` 类体是单一内聚状态机，**无值得付的接缝**（拆大方法要把八九个构造参数摊成 `internal`，是拿封装换行数）；只外迁三个顶层声明（`ScheduledTask`/`RecoveryRecord`/`DefaultDeadlines`）。`AppShellApplication` 的读侧零重复不变式（不搬），写侧取一个刀口 —— 四类 APK 资产的读法抽成 `shell/AssetsRead.kt`（**该文件不可单测**，已如实登记） | 2026-10-07 |
| 第 54 项第 2 条「命令范围 = 只有 npm，**不加 shell**」（用户 2026-10-09 早先裁定「不用加 sh 啊」） | `docs/design-decisions.md` 第 54 项第 2 条；`docs/design/10-npm.md` §10.9 第 3 条 | **改口径（2026-10-09，批 88）**：用户同日后续裁定「也需要让控制台能执行 shell」+「`su` 进 root、`shizuku` 进 adb、`exit` 退出」—— shell **进控制台**，但不是「裸命令一律当 shell」：**必须显式进模式**（`su`/`shizuku`），默认模式下裸首词仍是 npm bin。原裁定那半句「任意命令走脚本侧的 `auto.shell` 桥面」**仍成立**（那是脚本面，与控制台是两条面）；作废的只有「控制台不加 shell」这一句。口径见本文件**第 58 项** | 2026-10-09 |
### 附：§12.2 被反转口径原文照抄（2026-09-30 步骤 6 摘录前的原文）

> - **语义层**（handler）住 `:platform:capabilities` 的 `SystemNamespaces.kt`，纯 JVM 可测（假 SPI 注入即可跑）：参数校验（spec 守卫、必填字段、`timeout > 0`）、枚举字面量解析（`ShellMode`/`DialogMode`，拼错即报错不静默套默认）、默认值（shell 超时 30s）、错误分类**透传**（`AutojsException.error` 原码回桥）、响应形状编码（与 `extras.ts` 逐字对齐）；

> 所以「为什么 handler 不住 `:platform:system`」有两层理由：(1) 五个命名空间共享一套门禁组（OVERLAY/ROOT/ADB_INPUT），语义放一起才不会各写一份校验；(2) handler 若住 `:platform:system`，
> 装配层就得同时直连 `:platform:capabilities` 与 `:platform:system` 两个模块才凑得齐 Router —— §6 对 `:app` 非装配包明令禁止这一直连（装配包 shell 的生产装配 `PlatformWiring` 经包级例外二放行，
> 但那只是"把 SPI 拼成束"，不构成把 handler 挪去 `:platform:system` 的理由：主因仍是 (1) 的共担门禁）。

---

## 口径之外的判据注记（暂留契约侧）

§7.7 的 `TM_CCOEFF_NORMED` 长篇实测注记（2026-09-25）目前仍在契约正文里。
它**读起来像决策**（「口径改了」在末句），但主体是「为什么 CCOEFF 而不是 CCORR」
的判据论证。这一轮不动；下次迁移时按「判据留契约、变更进本文件」切开。

37. **`NOTICE` 与 `ui/` 的「逐字 / 逐句」措辞不再逐处核实（2026-10-07，用户裁定；backlog A9 据此结项）**：
    - **拍板**：**不做**「拿上游源码逐处核对 `ui/` KDoc 里那些『逐字移植 / 逐句对着写 / 逐字抄』
      的说法、并按实情降级措辞」这件事，`ui/` 里那 25 处措辞**原样保留**。`backlog.md` A9 整行移除。
    - **理由**：这是**署名措辞的精度**问题，不是许可义务问题 —— GPL-2.0 第 1 节要的两件（版权声明、
      担保免责）与第 2(a) 节的「修改说明 + 日期」已于**第 36 项**补齐并随 APK 出（`assets/third-party/`）；
      剩下的只是「本仓自述的口径比事实重不重」。花一批工时逐处比对去调这个，收益不抵成本。
    - **本项落地前已做的核实（留档，免得将来有人以为没核过）**：把上游 12.10.6
      （`f2908b14133bbffbf7ab04f641ecb5faf533242`）部分克隆到本机后抽查了几处 ——
      `cascade()` 与上游 `AndroidUtilities.java:5215` **逐字一致**（连行号都对得上）、
      `MainTabsLayout.onMeasure` 的三趟试排 / `maxTabTextWidthIfEq` / 两端夹逼与 `TabBarMeasure.kt`
      **一一对应**、`EASE_OUT_QUINT = (.23, 1, .32, 1)` 与 `FragmentFloatingButton.SIZE = 48` 一致、
      `ScaleStateListAnimator.apply(view, .1f, 1.5f)` 的 80ms 线性 / 350ms `OvershootInterpolator(1.5)`
      一致。**同时抽查出两处上游不存在的类名**：`TopicsLayoutSwitcher`（`Motion.kt` 的 KDoc 引用）
      与 `ReverseOrder`（`ProjectScreen.kt:416` 的 KDoc 引用）在上游 12.10.6 全树**零命中**。
    - **那两处不存在的类名怎么处置**：**本项一并明确「不追」**（同上理由 —— 那是注释里的举例，
      不是署名义务）。如实记在这里，不散在 `ui/` 的 KDoc 里改来改去。
    - **性质声明**（与第 33/36 项同）：本项是**工程侧的成本裁定**，不是法律意见；若将来因分发形态
      变化需要更精确的署名，届时按本表另起一行。

38. **能力引导文案改成「与三态无关」，`ADB_INPUT` 的三态读数随批 61 修正（2026-10-07；backlog A8）**：
    - **背景**（A8 的现场）：真机上「精确闹钟」那一行状态标 **可用**，紧接着那行引导文案却是
      「精确闹钟未允许：请前往…」——同一条目同时说可用与未允许。根因不是版式：`guideText`
      八条里**六条按拒绝态起句**，而契约（`CapabilityRow.guide` 的 KDoc）写的是「GRANTED 时也有，
      呈现层不按三态去猜该不该显示它」。意图与数据对不上。
    - **裁定：改数据这一头（选项①），显示逻辑不动**。八条逐条重写成「这项能力是干什么的 +
      怎么让它可用」，于是三态下都成立。**不**推翻「永远显示」那条口径 —— 撤下显示是
      **批 47 已经做过的事**（用户口径「去除各个权限的描述」，设置页不再渲染 `guide`），
      本项管的是**将来再渲染时**文案必须自洽。
      - 代价如实记：文案变长（每条多一句用途），且**不再随三态变**——用户读到的是固定说明，
        当前态由同一行的 `stateLabel` 表达（两者本来就不该由同一份文案重复说）。
      - 钉子：`PermissionCenterTest` 新增两条 —— 「不按拒绝态起句」（`startsWith("未"/"没"/"不")`
        即红 + 必须以「能力名：」起头），以及「`ADB_INPUT` 文案不承诺降级」。
    - **顺带修正一处过期口径（本项真正的行为变更）**：`AndroidSystemStateReader` 里
      `ADB_INPUT` 此前是**恒 `DEGRADED` 常量**，理由写的是「Shizuku 尚未集成，但引导文案承诺的
      降级路径（未就绪时输入走无障碍手势）真实存在」。**批 61（§9.3 三通道三选一）把那条路
      废掉了** —— 指定 `adb` 而该通道不可用就是 `ERR_PERMISSION_DENIED`，绝不改用别的通道。
      即：那条「降级路径」已经不存在，继续报 `DEGRADED` 是拿旧口径骗人，而文案里那句承诺
      更是对用户的假承诺。
      - 改法：探针加 `adbInputAvailable()`（`ShizukuInput.isAvailable()` 一句转问 —— 唯一的
        Shizuku 接触点仍在平台模块，`:app` 不碰 `rikka.shizuku.*`），映射改成
        `就绪 → GRANTED / 不就绪 → DENIED`，与 `ACCESSIBILITY`/`ROOT`/`USAGE_ACCESS` 同档
        （都是「没有替代路径」的能力）。
      - **出厂态 DENIED 集合随之从四种变五种**（`AndroidSystemStateReaderTest` 那条钉死的断言
        同批更新，并写明为什么）——这正是那条断言存在的意义：它逼着这次变更被显式确认一次。
      - 边界如实登记：Shizuku 在跑但**尚未授权本应用**时，`isAvailable()` 的 binder 问询拿不到
        服务 → 也判 `DENIED`；文案因此把「授权本应用」与「装/启动 Shizuku」并列写。
        真机上「装好之后到底能不能注进去」仍未验（backlog **B14**，与本项无关）。
    - **同批（D7 大文件余量）两个刀口**：见下条。
    - **性质**：工程口径变更，无法律含义。

39. **大文件余量（D7）收口：余下两个文件里只有一个值得付刀口（2026-10-07）**：
    - **背景**：外审按 2026-10-01 前的快照点名四个大文件，批 6 已拆过一轮；剩下没拆的是
      `AppShellApplication`（Android 生命周期本体）与 `Scheduler`（登记为「两个 DTO + 一个类，
      **无干净接缝**」）。D7 的问题是「这两个里还有没有值得付的刀口」，不是「能不能拆」——
      任何文件都能拆，问题是拆完**读起来是否更好**。
    - **先纠一个度量口径**：两个文件都是**注释密集**的（KDoc 占了近半行数），裸行数高估了问题。
      逐类数过（总行 / 空行 / 注释行 / 代码行）之后才动刀 —— 结论是「拆的收益在**口径集中**，
      不在行数下降」。
    - **`Scheduler`：类体内无值得付的接缝，只外迁了三个顶层声明**。类本体是一台约四百多行的
      **单一内聚状态机**（触发入口、恢复、提交三件事共享同一份可变状态），两个大方法
      （`onTrigger`/`recoverUncommitted`）若要外迁，得把八九个构造参数摊成 `internal` 暴露 ——
      那是**把封装换成行数**，不划算。真正的接缝是**三个不属于这台状态机的顶层声明**：
      `ScheduledTask`（登记载荷的形状）、`RecoveryRecord`（恢复的返回值形状）、
      `DefaultDeadlines`（一个零状态的纯函数常量）—— 它们与「怎么调度」是两件事，各自成文件，
      语义逐字未改（只有 KDoc 补了出处段）。
    - **`AppShellApplication`：读口那一半无重复不变式，写口那一半有一个真刀口**。先把「读侧」
      （`HostSummary` 的几个实现）查了一遍：它们只是把 `AssembledShell` 的字段转手，**零重复
      不变式**，抽出去只是搬家 —— 不做。真刀口在 `installWithFiles` 那张九十行的具名参数表里：
      四类 APK 资产（内置脚本 / facade dist / addon / npm CLI 素材）的读法各带一条**降级口径**
      （枚举失败算不算「没货」、读失败与缺件同不同形），四段 `try/catch` 混在参数表里，
      读者看得到「怎么吞」看不到「为什么这条这么吞」。抽成 `shell/AssetsRead.kt` 之后，
      `installWithFiles` 每类资产只剩一行。
    - **诚实边界（写进了 `AssetsRead` 的 KDoc）**：那个文件**不可单测**（参数是 `AssetManager`，
      JVM 上造不出真实例）—— 与 `CapabilityCenterRead` 不同，后者吃 `:domain` 的接口、能注入
      假实现。抽出来的收益是「口径集中且可读」，**不是「可测」**；如实记下来，免得将来有人
      以为 `shell/` 下的文件都自带单测缝。
    - **结论**：D7 结项 —— 余下两个文件里，`Scheduler` 的类体**没有值得付的接缝**（这是复核
      结论，不是没看），`AppShellApplication` 只取上面那一个刀口。**行数不写进文档**
      （会漂，见 `CONTRIBUTING.md` 的「别写会漂的数字」）；要现值就 `wc -l` 现读。

40. **日志管理 = 系统日志 + 任务日志；项目页不放执行历史；系统日志先取「控制台的引擎外行」（2026-10-07，用户拍板）**：
    - **拍板**：① 执行历史**不放项目页**，放进管理面板的「日志管理」；② 日志管理两个列表 —— **系统日志**、
      **任务日志**（= 原先的项目历史，但改成**全部项目**）；③ ~~**控制台不动**（用户口径：现在不知道干啥用，不管它）。~~ **已改口径（2026-10-09，批 84）**：控制台改做**命令面**（跑 npm 命令），原控制台的日志内容整体搬去日志管理 —— 见本文件第 54 项。原口径不删，就地划掉。
    - **系统日志的数据源有三案**：（A）控制台里 `runId == 0` 的行；（B）新建宿主日志采集（把 `Log.i/w/e` 那二十来处
      宿主事件镜像进有界缓冲 + 新读口）；（C）先 A，再给桥补 `side.runId` 打戳让脚本输出归到各次执行。
      **选 A**：零新增采集、页面结构立刻可用。B/C 都要动采集面或桥协议，工作量明显更大，且不阻塞「两个列表」这件事本身。
    - **A 的代价（必须随口径一起写下，免得被读成「系统日志 = 宿主事件」）**：桥 `console.log` 的 handler 恒写 `runId = 0`
      （`BridgeRequest` 没有 `side`/`runId` 字段），所以**脚本输出今天也在系统日志里**；宿主事件走 `android.util.Log`、
      **不在**这个列表里。页面顶部如实说明这两点；补齐路径登记为 backlog **A10**（与 A5 同一次协议变更）。
    - **读口口径**：`HostSummary.projectHistory(projectId)` 改为无参 `taskLog()`，**不保留**按项目的口 ——
      它从未进过 `main`，没有调用方，留着是第二套入口。`RunArchive` SPI 因此补 `records()`（全量）。
    - **不改的**：控制台页与它的停止操作面；任务中心的「未结算」账（`taskCenter()`）—— 任务日志只收**终态**，
      两者仍是两本账，不混。

41. **APK 体积预算由 `≤ 40MB release` 改为 `≤ 150MB release`（2026-10-07，用户裁定；§15 表据此改数）**：
    - **拍板**：§15 的 APK 体积预算改成 **`≤ 150MB release`**。用户口径「修改条件，改到 150mb」——
      这是**用户对预算数字的直接裁定**，不是工程侧的推算；本项只负责把新数字落到契约、把旧账留全。
    - **为什么这个数站得住（实测锚）**：真形态 debug APK（四件引擎 so + tree-sitter 三件 + npm 素材）
      **51,838,708 B ≈ 49.4 MiB** —— 取证 = `engine-native.yml` 的 artifact `android-debug-true-form`
      （run `37581330329`，2026-10-07，`main`）。**未压缩 jniLibs 三件套 ≈92MB ≈ 87.7 MiB 是上界**
      （§15 实测块，ICU `zh,en` 后），即便按未压缩口径读也在 150MB 之内。
      **release 形态（含引擎）本仓还没出过包** —— `engine-native.yml` 出的是 debug；已有的 release
      实测（2026-10-06，1,560,977 B）是**不含引擎**的性能包，不是同一个东西。故本行按 debug 真形态锚定。
    - **与第 20 项的关系**：第 20 项的**处置**（`InstallSizeRead` 实测链、`InstallSizeState` 换算、
      量不到回 null、引擎那段单列）**全部保留**，它本来就是"量真实字节数"，与预算是哪个数无关；
      第 20 项里「接受超支」的那半**随本项失效**（按新预算不超支了），
      「在能力中心明示」的那半**已于 2026-10-05 批 47 撤下渲染**（用户口径，见该条第 6 个子弹）。
    - **不删旧账**：§15 表下方那段实测记账（≈92MB 取证链、OpenCV 占比、(a)/(b)/(c) 三条出路）
      **一字未删**，只在行首补一句「随后按实测重定」；「已推翻 / 已改口径」表里 `APK ≤ 40MB`
      那一行也**保留原样并就地标注**新数字。推翻一个预算要留账，重定它同样要留账。
    - **本项不改的边界**：① 本仓**没有任何 APK 体积门禁**（`.github/workflows/` 里无体积断言），
      150MB 是预算不是闸，超了不会红 —— 要不要立闸是另一件事，未排期；
      ② §14 P1 的 (b)「按需分发」仍**未排期**（`libnode.so` 有 exec 硬需求，动它先要解决 §19 落位链）；
      ③ 32 位 ABI 已裁（backlog B10，只留 `arm64-v8a`），本项不改变它。
