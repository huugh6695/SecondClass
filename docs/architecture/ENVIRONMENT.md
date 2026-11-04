# 环境与自发验证（环境相关，非业务规则）

> 这份文档回答的是「在这台机器上怎么干活」，**不是业务规则**。
> 业务规则看 `CONVENTIONS.md` / `MODULES.md`，协作纪律看 `PROCESS.md`。
>
> 分三段，换环境时可以整段跳过：
>
> - **§一 §二** —— 本机固定路径与依赖服务（换机器要整段重写）
> - **§三 §四 §五 §六 §七** —— 工程纪律（踩过的坑，**通用**，值得读）
> - 文中的 `sb.sh`、沙箱限制、`Program Blacklist` 等 —— 属于 **WorkBuddy Agent 的操作环境**，
>   换个 Agent 工具或人类来做，这些不适用，跳过即可
>
> 🔴 **本文不放任何口令。** 数据库密码在 `config/application-local.yml`（已 gitignore），
> 线上在 `/etc/second-classroom/env`（服务器上 600 root:root）。

## 一、本机固定路径
- JDK `C:\Users\joya\.jdks\jdk-17.0.14+7`；`JAVA_HOME` **空**、`mvn` **不在 PATH**。
- MySQL CLI `D:\MySQL\MySQL Server 8.0\bin\mysql.exe`，库 `second_classroom`，
  口令见 `config/application-local.yml`（**不写在这里**）。
- 项目源码 `D:\basic codes\second-classroom`（路径含空格，**引用必须加引号**）。
- Python `C:/Users/joya/.workbuddy/binaries/python/versions/3.13.12/python.exe`。

### 1.1 怎么调 bash（PowerShell 里 `bash` 不在 PATH）

**Git 装在本机的 `D:\Git`**，不是默认的 `C:\Program Files\Git`，所以 `bash` 没进 PATH。
按环境选一种，效果完全一样：

| 环境 | 命令 |
|---|---|
| Git Bash / WSL | `bash verify/status.sh` |
| PowerShell（本项目自带包装器） | `.\verify\run.ps1 status --offline` |
| PowerShell（不用包装器） | `& 'D:\Git\bin\bash.exe' verify\status.sh` |
| cmd | `"D:\Git\bin\bash.exe" verify\status.sh` |

`run.ps1` 找 bash 的顺序：`$env:BASH_EXE` → `D:\Git\bin\` → `C:\Program Files\Git\bin\` →
`C:\Program Files (x86)\Git\bin\` → `%LOCALAPPDATA%\Programs\Git\bin\` → `Get-Command bash`。

- 执行策略拦住 `.ps1` 时：`powershell -ExecutionPolicy Bypass -File .\verify\run.ps1 status`
- 换机器只需设一次：`$env:BASH_EXE = 'C:\path\to\bash.exe'`

> 🔴 **2026-10-31 写的旧结论作废**：原文说「本机 PowerShell 启动不了外部进程，
> 连 `bash -c echo` 都返回空」。这句话把**现象**当成了**原因**。

**① 无 BOM + 字符串里有中文 —— 11-01 那次「跑不通」的真因**

Windows PowerShell 5.1 读**没有 BOM** 的 `.ps1` 时按系统 ANSI 代码页解码（中文机器 = GBK）。

光这样还不致命：**注释被解成乱码仍然是注释**，脚本照跑。真正炸的是中文写进了
**字符串字面量**（`Write-Host "中文…"`）—— 某个汉字的后半个字节会把行尾的 `"` 吃掉
⇒ **整个脚本解析失败**。

最坑的是失败表现：解析失败连一行都不执行，所以 `$LASTEXITCODE` 保持原值 `0`，
调用方看到的是 **「退出码 0 + 零输出」的假成功**。

> 📌 **为什么两个 Agent 一个说「能跑」、一个说「跑不通」（2026-11-01 复盘）**
>
> **不是环境差异 —— 是测的根本不是同一份字节。**
>
> - 说「能跑」的那次测的是 commit `8cf7bea` 的版本：只有 **64 行**，中文**只出现在注释里**、
>   字符串里一个中文都没有 ⇒ 5.1 下完全正常。它报的「退出码 0 / 没有发现不一致」**是对的**。
> - 说「跑不通」的那次测的是**改过之后的工作区版本**：**116 行**，新增了中文 `Write-Host`
>   ⇒ 解析失败 ⇒ 零输出 + 0。**回归是改出来的，不是仓库里原本就有的。**
>
> 教训两条：
> 1. **报运行态结论必须同时报 commit 号**，否则两份结论没法对齐（`status.sh --json` 里有，
>    人读的报告也要带上）。
> 2. **`verify/` 下的脚本也算"要验证的代码"**：改完至少跑一次 `bash verify/selftest.sh`
>    （2 秒，查 shell 语法 / `.ps1` 有没有 BOM / 指纹 / `status.sh` 输出 / 交接单状态），
>    再跑一次 `bash verify/status.sh --offline` 和 `.\verify\run.ps1 status --offline`，
>    别只看 Git Bash 那一路。`run-regression.sh` 已把 `selftest.sh` 当「第零步」自动执行。

- ⇒ 仓库里所有 `*.ps1` **必须保存为 UTF-8 with BOM**（当前只有 `verify/run.ps1` 一个）
- 改完自检：`head -c 3 verify/run.ps1 | od -An -tx1` 必须是 `ef bb bf`
- 症状识别：脚本没有任何输出、`$LASTEXITCODE` 是 0，且当时在 Windows PowerShell 5.1 里
  = 先怀疑编码（PowerShell 7 默认按 UTF-8 读，不受影响）

**② `PATHEXT` 缺 `.EXE`（本机实测值就是 `[.CPL]`，只有一个）**

此时 PowerShell 把 `.exe` 当"文档"打开，同样表现为零输出 + 退出码 0（还是假成功）。
`run.ps1` 开头会把 `.EXE` 补回 `$env:PATHEXT`，并用 `bash -c 'echo __RUNPS_OK__'` 探活；
探活不过直接 `exit 2` 并打印当前 `PATHEXT`。

永久修法（需管理员）：系统环境变量里把 `PATHEXT` 恢复成
`.COM;.EXE;.BAT;.CMD;.VBS;.VBE;.JS;.JSE;.WSF;.WSH;.MSC`。

**③ 别给 `run.ps1` 的输出接管道**

`.\verify\run.ps1 status | Out-String` 有两个副作用：中文按 GBK 解码成乱码；
`exit N` 传不出来（`$LASTEXITCODE` 变 0）。**直接运行则两者都正常**。
`[Console]::OutputEncoding = UTF8` 实测对它**无效**（赋值成功但输出照样乱码）。

> 结论：**验证脚本优先用 Git Bash 跑**（最稳）；要在 PowerShell 里跑就走 `run.ps1`，
> 且**别接管道**。cmd 下直接调 `bash.exe` 也行。

## 二、🔴 重启后必检两样依赖
- **MySQL**：服务名 **`MySQL80`**。`net start MySQL80` 报"拒绝访问" = 服务存在、只是要管理员；`sc.exe`/`reg.exe`/`cmd.exe` 都被安全策略禁 ⇒ **我启不了服务，只能让用户开**。
- **Redis**：✅ **2026-09-29 已装成 Windows 服务**，服务名 `redis`（小写）、`StartMode=Auto`、
  以 `NT AUTHORITY\NetworkService` 身份运行、`PathName = redis-server.exe --service-run redis-service.conf --service-name Redis`、
  日志落 `C:\Users\joya\redis\redis-service.log`。⇒ **重启后应该自己起来，不用再双击任何东西**。
  手动兜底仍是双击 `start-redis.bat`（那个是**临时窗口**，重启即没；且会和服务抢 6379）。
  服务脚本（`C:\Users\joya\redis\`，**全中文 + GBK 编码**）：`install-redis-service.bat` /
  `start-redis-service.bat` / `uninstall-redis-service.bat` / `redis-service.conf` / `autostart-notes.md`。
  - **三个脚本都可重复点**（幂等）：已登记→`:alreadyinstalled` 分支；已在运行→"已经在跑了"并 `exit /b 0`。
  - 服务用**单独的 `redis-service.conf`**（只改 `logfile` → 文件，因为服务没控制台，`logfile ""` 会丢日志）。
  - 二进制**支持** `--service-install/--service-start/--service-stop/--service-uninstall`（已 grep 确认）。
  - ⚠️ **装之前最好先关掉手动启动的黑窗口**（不然 install 的自动启动那步会走 `:portbusy` 分支，
    要你再点一次 `start-redis-service.bat`）。
  - ⚠️ **`sc.exe` 被禁只针对我的沙箱**（Program Blacklist，报 `Permission denied`），
    **用户自己双击的 .bat 里用 `sc.exe` 是正常的**（不经过 WorkBuddy 沙箱）。
    但正因为如此，脚本里那两处 `sc query` 判断**我无法自测** ⇒ 已把它们写成**软提示**，
    查不到也不 `exit`，绝不挡住正常启动。
  - 万一没自启：让用户去 `services.msc` 找 `redis` 把启动类型设「自动」。
  - Linux（云服务器）= `systemctl enable redis-server|redis` + 公网必须 `requirepass`（详见 `autostart-notes.md`）。
- **症状**：IDEA 里启动直接 `退出代码 1`，栈是 `com.mysql.cj.protocol.StandardSocketFactory.connect` + `Connection refused`
  ⇒ **MySQL 没起**，不要误判成 Redis 或新代码的问题。
- 排查一行：`netstat -ano | grep LISTENING | grep -E ":(3306|6379)"`。
- ⚠️ 2026-10-11 实测：**PowerShell 工具在我这个沙箱里拿不到任何输出**（连 `Get-Date` 都 exit 0 + 空 stdout）
  ⇒ 判断"服务在不在"只能靠 Git Bash 的 `netstat -ano` 和 `ps -W | grep -i <名字>`，
  **绝不能用 `Get-Service` 的空输出去下"没有这个服务"的结论**。
- ⚠️ MySQL 的 `datadir` 在 `C:\ProgramData\MySQL\MySQL Server 8.0\Data`，**沙箱读不了（Permission denied）**
  ⇒ 别想着自己 `mysqld --defaults-file=... ` 起个临时实例（datadir 摸不到、也绕不过）。**这条路死了，直接找用户。**
- ⚠️ 2026-10-11 现状：Redis 一直在跑（PID 从 10-02 起），**MySQL 没起**（`ps -W` 里没有 mysqld、3306 无监听）
  ⇒ 症状就是启动日志里那句 `数据库连接成功` **出现过**、随后所有请求 `Communications link failure` / `Connection refused`，
  验证脚本会**跑到"登录"那一节才崩**（前 22 条静态/匿名断言照样全绿，很有迷惑性）。

## 三、起临时实例（技能 `spring-boot-local-e2e-verify`）
`~/.workbuddy/skills/spring-boot-local-e2e-verify/scripts/sb.sh`：
`compile <项目目录>` / `start <端口> <项目目录> [附加参数]` / `wait <端口> <项目目录>` / `stop <端口>` / `ps <端口>`。
- **不加 `-o`**（离线模式会拿不到依赖）；临时端口 **8090+**，避免打扰用户 IDE 的 8080。
- 🔴 `wait` 的**第二个参数（项目目录）不能省**。
- 🔴 `start` / `wait` / 验证 / `stop` 必须写在**同一次 Bash 调用**里 —— 我起的后台实例活不过一次工具调用。
  **2026-10-11 又栽一次**：把 `sb.sh start` 单独丢进 `run_in_background`，命令一结束 java 就被收走，
  而 `wait` 紧接着回了 `STARTED` —— 它 grep 的是 `run<port>.log` 里的 `Started ... Application in`，
  那个文件是**上一次**留下的旧日志（时间戳还停在 9-27），**wait 被骗了，其实根本没起来**。
  ⇒ 起完**自己再确认一眼**：`netstat -ano | grep :<端口>`（有 LISTENING 才算起来了）/ 或看日志时间戳是不是刚才。
  ⇒ 一条龙写法（已验证可用）：`bash "$SB" start <端口> "$P" ; bash "$SB" wait <端口> "$P" ; <跑验证> ; bash "$SB" stop <端口>`。
- 🔴 **全程别加管道**（SIGTERM + 输出全丢），一律重定向到文件 + `</dev/null`。
- 一轮可能 >120s ⇒ 显式给长超时 **300~540s**。
- 被用户插话会收到 SIGTERM + 空输出 ⇒ **直接原样重跑**。
- **模拟"依赖打挂"的降级档**：`sb.sh start 8099 "$P" --spring.data.redis.port=6399`（6399 无人监听）——
  **别去停用户的 Redis**。
- ⚠️ **加新依赖那轮编译是分钟级**（实测 12 分 26 秒，前台会被 SIGTERM、日志 0 字节）⇒ 用 `run_in_background`。

## 四、验证脚本的规矩

> 🔴🔴 **2026-10-28 血的教训：脚本绝不能放 `target/`。**
>
> `target/` 是 Maven 的构建目录，`mvn clean` 的职责就是**把它整个删掉从头再来**。
> 一次发布跑了 `mvn clean package` ⇒ **20 个 `verify-*.py` + `run-regression.sh` +
> 80KB 的接口契约 `doc.txt` 全部消失**；又因为 `target/` 在 `.gitignore` 里，
> **git 里没有任何备份**，全盘搜索也找不回（101MB 会话记录里只剩片段，提不出完整文件）。
>
> ⇒ **现在一律放项目根的 `verify/`**（已纳入 git；`verify/lib.py` 是公共库）。
> ⇒ **判据一句话：`git check-ignore` 认识它，就别往那儿放需要长期保存的东西。**
> ⇒ **重建进度：2026-10-28 全部完成 ✅**。20 个脚本 + `lib.py` + `run-regression.sh` 都在 `verify/`，
>    实测 **733 通过 / 3 跳过 / 0 失败**，`REGRESSION OK`。（跳过的 3 条全在 `verify-duplicate.py`：
>    本地库 3600+ 张历史单污染检测池 ⇒ 那里是 `c.skip()` 不是失败。）
>    ⚠️ 重建版比原版（约 1467 条）断言少 —— 原脚本里靠记忆补不回来的细节就**宁可删掉**，
>    不写"看起来对但其实没验到"的断言。

- **Python 纯标准库**，放 **`verify/`**；断言一律「**基线 + 增量**」，**绝不写死行数**（范本 `verify-faq.py`）。
- 存盘后、起服务前**先 `python -m py_compile` 自检**：中文 Windows 极易混入**零宽空格 U+200B**、
  **中文里嵌半角双引号**（字符串被提前截断）。**用例名内层引号统一用「」**。
- 🔴 **URL 里不许直接塞中文**（2026-10-11 栽）：`urllib` 用 ascii 编码请求行 ⇒
  `UnicodeEncodeError: 'ascii' codec can't encode characters in position ...`，
  脚本**起手就崩、连汇总都打不出来**（看着像"跑完了没输出"）。
  ⇒ 拼之前用 `urllib.parse.quote(...)` 编一下（`verify-export.py` 里的 `qs()` 自带 urlencode，所以那边没事）。
- 🔴 **判据看"断言条数"**：两次运行之间变小 = 有断言被静默跳过（`verify-flag.py` 栽过：挑不到来源单就整块跳过，27→23 不报错）。
  **断言数必须稳定**，会变就补成"自己造前置数据"。
- 🔴 **秒级时间戳的"变新了"断言必须跨秒**：发请求前 `time.sleep(1.2)`。
- 🔴 **改完前端静态文件，必须先 `mvn compile` 再跑验证**（2026-10-11 发现，会**静默骗人**）：
  `verify-frontend.py` 的静态侧断言不是读磁盘源码，而是通过 **HTTP `GET /js/app.js`** 读
  ⇒ 读的是 `target/classes/static/...` 里的**编译产物**。不编译 → 验的是**改之前**那份，
  而且它依然全绿（旧代码当然过旧的断言），看起来"验证通过"，其实一行新代码都没验到。
  ⚠️ `sb.sh start` **不负责编译**。改前端后的固定动作：
  `mvn -o -q compile` → `bash verify/run-regression.sh 8099 verify-frontend.py` → 全套。
  （本机 `mvn` 不在 PATH，沙箱里写全路径 `C:/Users/joya/maven/apache-maven-3.9.16/bin/mvn.cmd`。）
- 🔴 **文本型断言要盯"代码长什么样"，不是"你心里以为的样子"**（已踩 **6 次**：`requestId` /
  `remark` / `btn-danger` / 注释里的说明文字 / `toISOString` / **假设了引号**）。
  · 写法：查**带前缀的字面量**（`class="btn btn-danger"`、`requestId:` 带冒号），
    别光查 `btn-danger` —— 否则被自己写的**注释**命中，假红。**写注释时也要躲开断言的敏感词**。
  · **第 6 次（2026-10-11）**：断言写的是 `'"点这一条看详情"'`（**自带引号**），
    而 app.js 里那句提示其实是模板字符串的**正文**、一个引号都没有 ⇒ 假红。
    ⇒ **写断言前，回去看一眼那行代码的原文**（别凭记忆拼字符串）。
- 🔴 **`re.find` 切代码段时，区间必须收紧到"下一个顶层函数"**（2026-10-11 一天内栽 **3 次**，
  假红 5 条 / 1 条 / 2 条）：
  · **起点太晚**（3 次都是这个）：把 AppWords 那段从 `loadAdminWords()` 切起 ⇒ 落在它前面的
    `viewAdminWords()`（输入框 maxlength、两个筛选控件的事件绑定）全被切出去 ⇒ 5 条断言集体红；
    模板页从 `viewAdminTemplates()` 切起，漏掉前面的辅助函数；统计页从 `viewAdminStats()` 切起，
    漏掉前面的 `fmtDateInput` / `presetRange`。
    ⇒ **切段的起点要取"那一整块的开头"**（第一条语句/第一个常量），不是"我要验的那个函数"。
  · **终点太松**：切到 `/* 十二、路由 */` 注释 ⇒ 把**后加的页面**一起包进来 ⇒ 段内
    `needLogin` 计数从 2 变 7。
  · ⚠️ **2026-10-11 第三种翻车方式：我一个字没改断言，是"插了新代码"把旧区间撑大了。**
    `verify-frontend.py` 里好几处拿 `/* ---------- 十二、路由` 当右界，导出页一插到
    「统计」和「路由」中间，统计页那条 `count('type="date"') == 2` 就开始数导出页的日期框 ⇒ 假红
    （看着像"我把统计页改坏了"，其实统计页一个字没动）。
    ⇒ **已加通用 helper `cut(start, *next_markers)`：传若干候选锚点、取最先出现的那个**；
      敏感词 / 模板 / 统计三处已补候选。
    ⇒ 规矩：**往 app.js 里插新块时，顺手给"它前面那一块"的 `cut()` 补一个候选锚点**。
  ⇒ 规矩：起点 = 该块的**开头**；终点 = **紧邻的下一段**（下一个顶层函数 / 章节注释 / 下一个块的开头
    —— 取最先出现的那个），**不是**文件末尾。
- 🔴 **断言别写死"数量"**（`ready: true 只有 1 项`、`isAdmin() 守卫 2 处`）：每加一个后台页面
  都得多一处，写死就得回头改断言 ⇒ 断言失真比没有断言更糟。改成写**语义**：
  「标成 ready 的每一项在路由里都得真有对应 case」「每个 /admin 分支都要有守卫」。
- 🔴 **写"某数据不应出现"的断言前**，先想清它在这个维度上属于哪一边（已错两次）。
- 手工看 Redis：`python target/redis-cli.py [-h 主机] [-p 端口] <命令>...`（纯标准库手搓 RESP，本机 redis-server 没带 redis-cli）。
- 手工看库：**先探快照再操作**，别凭印象写期望值。
- 🔴 **`sql()` 偶发空输出 —— 已在源头修掉（2026-09-28）**：脚本靠"每次 spawn 一个 `mysql.exe`"查库，
  实测**约 0.2% 的调用会一次什么都不输出**（一整轮回归约 600 次查库 ⇒ 几乎必然冒一条随机红）。
  已确认**不是服务端问题**：`Aborted_connects` / `Connection_errors_max_connections` 恒为 0；
  `python target/stress-mysql.py 300` 连打 300 次 **0 失败**（每次约 195ms）。
  **症状两种，都毫无线索**：① 崩在 `int('')`（脚本连汇总都没有）；② 被 `num()` 的 `-1` 默认值
  **悄悄吃掉** → 变成一条有汇总的随机红（更阴险）。
  修法（**18 个脚本全部**改 `sql()`）：**重试 3 次**；判据用 `rc == 0 且 (有输出 or 不是 SELECT)`
  —— 非 SELECT 本来就没输出、一次收工；SELECT 空着才可疑重试（真查不到的话重试也一样是空，不改结果）。
  `rc != 0` 时把 mysql 的 stderr 打出来（以前被吞掉，排查只能靠猜）。
  另在 `run-regression.sh` 加了第二道闸：**退出码非 0 且没有汇总**（=崩溃）就重跑一次并打印 `[RETRY]`。
  ⚠️ **带汇总的 exit 1 是真失败，绝不重试** —— 否则会把真红洗白。
- 诊断工具：`python target/stress-mysql.py <次数>`（mysql 调用压测）、`python target/redis-cli.py ...`。

## 五、🔴 回归
别再一条条拼命令：`bash verify/run-regression.sh <端口> [脚本.py ...]`（不带脚本名 = 跑 verify/ 下所有已存在的脚本，**尚未重建的自动跳过、不算失败**）。
它自己 start → 逐个跑（输出落 `target/out-<脚本>.txt`、**`</dev/null`**）→ stop，末尾打印 `REGRESSION OK` / 失败清单，退出码 1 = 失败。
**整条命令里只有这一次 bash 调用。**

### 5.1 🔴 跑之前先确认两件事（2026-11-05 连踩两次）

**① 三个测试号的密码必须是 `123456`**（`verify/lib.py` 里写死的常量，不是学号后 6 位）。
但**改密接口不允许纯数字**（要求 6-20 位且含字母+数字）⇒ 一旦哪天在界面上试过「改初始密码」，
就再也改不回 `123456` 了，只能直接改库（把管理员那条的 `password` 哈希复制给另外两个号）。
改完记得还原，别把用户自己设的密码弄丢。

**② Redis 里不能残留登录失败计数**。第一次跑挂之后，每个脚本的登录都会再失败一次，
很快就叠到阈值，之后**所有**脚本都报
`code=429 登录失败次数过多，请 15 分钟后再试` —— 而脚本自己的报错文案还写着
「账号或密码是不是被改过」，极易把人带偏（我因此白跑了三轮）。
- 判据：**401 = 密码真不对；429 = 只是被锁了**，先清锁再判断密码。
- 清锁：`KEYS auth:login:fail:*` 然后逐个 `DEL`（key 是 `auth:login:fail:` + 学号的 HMAC，
  别去猜具体值；也别 `FLUSHDB` —— 会把登出黑名单和敏感词缓存一起清掉）。
- 本机没有 `redis-cli.exe`，用 Python 走 RESP 协议几行就能删。

## 六、日志与"跑旧代码"的判断
- JDK 17 中文 Windows 默认 **GBK 写日志** ⇒ `grep "中文"` 匹配不到（像"功能没跑"）。`sb.sh start` 已带 `-Dfile.encoding=UTF-8`。
- **Controller 改了却测出 Spring 默认 404**（响应含 `timestamp`/`status`/`path`）= **跑的是旧代码**（JVM 不重载类）。
  拿老接口 `/api/faqs` 对比即可判断；业务 404 形如 `{"code":404,"message":"工单不存在"}`。
- 🔴 **回归出现大跨度空档 ≠ 卡查询**：先看应用日志有没有
  `HikariPool-1 - Thread starvation or clock leap detected (housekeeper delta=...)`。
  有它就是**整台机器被挂起了**（睡眠/休眠/VM 暂停）—— 踩过一次 11 分 43 秒的空档，
  一度误以为是自己新加的重试逻辑拖慢的。判据齐了再用审计表时间线定位。

## 七、🔴 中文 `.bat` / 编码（2026-09-29 新增）

- **cmd 默认代码页 936(GBK)**，而我只写得出 UTF-8 ⇒ 中文 .bat 在窗口里必乱码。
  通用工具：`target/bat-to-gbk.py <文件.bat>...`（UTF-8 → **GBK + CRLF**；已是 GBK 则**跳过**）。
- ✅ **Write/Edit 工具会保留文件原有编码**：文件已是 GBK 时，改完再跑转换脚本会打印
  「跳过（看起来已经是 GBK）」—— **这是正常的，不是漏转**。判据是"能否用 UTF-8 解码"，能解才转。
  ⇒ **改完必须按 GBK 解出来复核中文**，别只看"跳过"就放心。
  自检脚本里回读**必须加 `newline=""`**，否则 Python 把 `\r\n` 归一化成 `\n`，
  「行尾差异」会被误报成「字符差异」（踩过：三个文件全报 `!! 回读不一致`）。
- 🔴 **括号陷阱**：`echo` 写在一对 `( )` 代码块里时，行内出现**半角** `(` / `)` 会把块截断。
  中文提示里一律用**全角** `（）`，序号写 `a/` `b/` 而不是 `a)` `b)`。
  体检办法：逐行扫 `echo` 行，看半角括号是否配对 + `goto` 的 label 是否存在。
- 图标/Emoji 之类 GBK 存不下的字符会丢，脚本里别用。

## 八、🔴 上线部署的三个硬约束（⑨，会静默出错）

> 🔴 **仓库是公开的（GitHub）**：服务器 IP、真实账号密码、私钥都不进库。
> 服务器地址写在本机 `verify/.server-host`（已 gitignore，内容就一行 `root@IP`），
> `status.sh` 会自动读它；文档里一律写 `<你的服务器IP>` 占位。
> 2026-11-01 做过一次全库脱敏（含重写历史），别再写回去。

① **Nginx 必须 `client_max_body_size 10m`**。默认只有 1MB，而应用允许单文件 5MB、
整个 multipart 6MB（`spring.servlet.multipart`）⇒ 传稍大的图会**被 Nginx 直接 413 挡掉、
请求到不了 Java**，应用日志一片空白 ⇒ 极易误判成"上传接口写错了"。

② **Nginx 的 XFF 要用 `$remote_addr` 覆盖，不要用 `$proxy_add_x_forwarded_for` 追加**。
审计 IP 来自 `util/WebUtil.getClientIp()`，它**优先读 `X-Forwarded-For` 的第一个值**；
追加写法会把客户端自己伪造的 XFF 留在最前面 → **审计 IP 可被故意写歪**。
不设这两个头则全员记成 `127.0.0.1`。同时要设 `X-Real-IP`。

③ **服务器时区必须 `Asia/Shanghai`**（`timedatectl set-timezone`）。
DB 列用 `DEFAULT CURRENT_TIMESTAMP` → 取机器本地时区；而 JDBC 里写死
`serverTimezone=Asia/Shanghai`。两边不一致 ⇒ **所有时间整体差 8 小时且不报错**。

> 另外两条"设计如此、不要乱改"：
> · Redis 的 `maxmemory-policy` **不能改成 `allkeys-lru`** —— 里面存着单号计数器、幂等标记、
>   **登出黑名单**；被淘汰 = 幂等失效 + **登出过的 Token 又能用**。保持 `noeviction`。
> · Redis **故意不持久化**是对的：`DailySeqId` 在 key 丢失时会按库里最大序号**重新播种**，
>   重启不会重复发号。详见 `MODULES.md`。

完整操作手册见项目内 `deploy/DEPLOY.md`（含 systemd 单元、Nginx 配置、env 模板、备份脚本）。
