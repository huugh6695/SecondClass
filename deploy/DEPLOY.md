# 上线清单 —— 第二课堂学时答疑工单系统

> 目标：**照敲就能上线**。每一步都写清"敲什么、敲完看到什么才算对"。
> 部署方式：**裸机 + systemd**（不用 Docker）。服务器系统：Ubuntu 22.04 LTS。

---

## 目录

0. [全貌：一共要装几样东西](#0-全貌一共要装几样东西)
0.5 [本机预演（拿到服务器之前就能做）](#05-本机预演拿到服务器之前就能做-2026-10-14-已跑通)
1. [先把这 5 个值定下来](#1-先把这-5-个值定下来)
2. [买服务器 & 开安全组](#2-买服务器--开安全组) —— 🔴 阿里云下单详见 `买服务器手册-阿里云.md`
3. [域名与备案（中国大陆服务器的硬门槛）](#3-域名与备案中国大陆服务器的硬门槛)
4. [服务器初始化](#4-服务器初始化)
5. [装 JDK 17](#5-装-jdk-17)
6. [装 MySQL 8（建库 + 建专用账号 + 灌数据）](#6-装-mysql-8建库--建专用账号--灌数据)
7. [装 Redis（就是你昨天在 Windows 上做的那件事）](#7-装-redis就是你昨天在-windows-上做的那件事)
8. [打包并上传](#8-打包并上传)
9. [用 systemd 托管（开机自启 + 崩了自动重启）](#9-用-systemd-托管开机自启--崩了自动重启)
10. [Nginx 反向代理](#10-nginx-反向代理)
11. [HTTPS（免费证书）](#11-https免费证书)
12. [备份（数据库 + 附件，缺一不可）](#12-备份数据库--附件缺一不可)
13. [上线自检清单（逐条打勾）](#13-上线自检清单逐条打勾)
14. [日常运维](#14-日常运维)
15. [故障对照表](#15-故障对照表)
16. [安全收尾与已知欠账](#16-安全收尾与已知欠账)

---

## 0. 全貌：一共要装几样东西

> ### ⚠️ 2026-10-14 更新：本项目**已经有前端了**
>
> ⑩ 阶段加了纯静态前端（`src/main/resources/static/` 下 4 个文件：
> `index.html` + `css/app.css` + `js/api.js` + `js/app.js`，共约 276KB，**零构建**）。
> Spring Boot 会**自动伺服** `static/` 目录，所以**打开域名就是能用的网页**，
> 不用额外装 Nginx 静态站点、也不用单独部署前端。
>
> **本文档早期版本（9/29）说「本项目是纯后端、没有网页」——那句已过时，不要照它做。**
> 当时的验收方式"一律用 curl"仍然**可以用**，但现在多了一条更直观的路：
> 浏览器打开 `https://域名/` 直接就能看到登录页。
>
> 🔴 但**接口级验收仍推荐用 `curl` / `requests.http`** —— 比点网页更能定位问题在哪一层。

```
                用户浏览器
                    │  https://你的域名
                    ▼
        ┌───────────────────────────┐
        │  Nginx（80 / 443）        │  ← 顺便处理 HTTPS、上传体积上限、
        └───────────┬───────────────┘     以及把"真实客户端 IP"传给后端
                    │  反向代理到 127.0.0.1:8080
                    ▼
        ┌───────────────────────────┐
        │  Java 应用（systemd 托管）│  ← second-classroom-0.0.1-SNAPSHOT.jar
        │  监听 8080，只对本机开放   │     内含前端静态文件 + 51 个 JSON 接口
        └──────┬─────────────┬──────┘
               │             │
               ▼             ▼
        ┌───────────┐  ┌───────────┐
        │  MySQL 8  │  │  Redis    │
        │ 建库+账号 │  │ 单号/幂等 │
        └───────────┘  └───────────┘
```

| 组件 | 干什么用的 | 装在哪一步 |
|---|---|---|
| JDK 17 | 跑 jar | §5 |
| MySQL 8 | 13 张业务表 | §6 |
| Redis | **单号生成**、幂等标记、登出黑名单、敏感词缓存 | §7 |
| systemd | 让 Java 开机自启、崩了自动重启 | §9 |
| Nginx | HTTPS + 上传体积上限 + 真实 IP | §10 |
| certbot | 免费 HTTPS 证书 | §11 |

> 🔴 **Redis 不是"可选"的**。单号（工单号 WO / 消息号 MSG / 附件号 ATT …）全部靠 Redis `INCR` 发号，
> 代码里**故意不做降级**（见 `DailySeqId` 的类注释：降级后的方案并发不安全，而且会发出重复号）。
> Redis 挂了 → 提交工单会返回 500。所以 `§13 自检` 里必须确认 Redis 是 UP。

---

## 0.5 本机预演（拿到服务器之前就能做，✅ 2026-10-14 已跑通）

**为什么先做这一步**：生产配置**不读** `application.yml` 里的写死值，而是从**环境变量**要东西。
这些变量在开发时从没配过，第一次跑**几乎必然报错**。在自己电脑上把坑趟完，
比拿到服务器上再踩强得多 —— 而且这一步**零成本、不需要服务器**。

**做法**（在 Windows 上，用 Git Bash 或 cmd 都行；关键是把三个变量喂进去）：

```bash
# ① 生成一个新的 JWT 密钥（64 字节 → base64，共 88 字符）
#    有 openssl 就：openssl rand -base64 64
#    Windows 上更稳的是用 Python 或 Node：
python -c "import base64,os;print(base64.b64encode(os.urandom(64)).decode())"

# ② 打包
mvn -DskipTests package

# ③ 建两个目录（模拟服务器上的 uploads / logs）
mkdir -p target/prod-demo/uploads target/prod-demo/logs

# ④ 用生产配置启动（🔴 换个端口，别占用 IDE 的 8080）
DB_PASSWORD="你的数据库密码" \
JWT_SECRET="①生成的88字符串" \
UPLOAD_DIR="D:/basic codes/second-classroom/target/prod-demo/uploads" \
LOG_DIR="D:/basic codes/second-classroom/target/prod-demo/logs" \
SERVER_PORT=8120 \
java -jar target/second-classroom-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod

# ⑤ 另开一个终端探活（等 10~30 秒让 Spring 起完）
curl -s http://127.0.0.1:8120/health
```

**✅ 验收标准（2026-10-14 实测值）**：

```json
{"code":0,"message":"ok","data":{"status":"UP","db":"UP","redis":"UP","timestamp":"..."}}
```
- 日志里有 `The following 1 profile is active: "prod"` ← 生产配置确实生效
- 日志里有 `Started SecondClassroomApplication in 10.115 seconds` ← 启动约 10 秒
- `target/prod-demo/logs/second-classroom.log` **有文件产出** ← 生产配置特有（落文件不落控制台）

> 💡 `timestamp` 是 **UTC**（比北京时间早 8 小时），这是故意的 —— JSON 里用标准 UTC，
> 前端负责转本地时间。**不代表时区配置错了**。

**🔴 顺手把三个必填变量各"故意做错"一次**，记住报错长什么样（实测都已验证）：

| 故意做错 | 启动结果 | 报错（`Could not resolve placeholder` 就是"变量没给"） |
|---|---|---|
| 不设 `JWT_SECRET` | **退出码 1，起不来** | `Could not resolve placeholder 'JWT_SECRET'` |
| 不设 `UPLOAD_DIR` | **退出码 1，起不来** | `Could not resolve placeholder 'UPLOAD_DIR'` |
| 数据库密码写错 | **退出码 1，起不来** | `Access denied for user 'root'@'localhost' (using password: YES)` |

> 这三条正是 §15 故障对照表里的前几行 —— 在服务器上看到它们时，你就知道该改哪儿了。
> "退出码 1" 很重要：systemd 靠它判断"要不要重启"，带病运行才是最糟的。

---

## 1. 先把这 5 个值定下来

后面所有命令里的这些**全大写**代号，都换成你自己的值。建议先写在一张纸上：

| 代号 | 是什么 | 怎么定 | 示例 |
|---|---|---|---|
| `SERVER_IP` | 服务器公网 IP | 买完服务器在控制台能看到 | `123.45.67.89` |
| `DOMAIN` | 域名（没有就先留着） | 域名商买一个 | `sc.example.edu.cn` |
| `DB_PASSWORD` | 数据库密码（**给应用专用账号**的） | 自己定，12 位以上，含大小写数字符号 | `Sc#2026_Db!x9` |
| `REDIS_PASSWORD` | Redis 密码（**可以不设**） | 只监听本机时留空即可 | （留空） |
| `JWT_SECRET` | Token 签名密钥 | 服务器上跑 `openssl rand -base64 64` 复制输出 | `k7Qz...（88 字符）` |

> ⚠️ **`JWT_SECRET` 一定要新生成**。`application.yml` 里那个是**开发值**，已经进过代码仓库，
> 等于公开。拿它签生产 Token，任何人都能自己伪造一个管理员 Token 登进后台。
> 长度 ≥32 字节；64 字节时算法是 HS512（开发环境就是 64 字节）。

---

## 2. 买服务器 & 开安全组

**配置建议（这是个校园内部系统，几十到几百人用）：**

| 项 | 建议 | 说明 |
|---|---|---|
| CPU / 内存 | **2 核 2G**（预算够就 2 核 4G） | Java 进程占 300–500MB，MySQL 占 300MB 左右 |
| 磁盘 | 40G 起步 | 附件（收据、证明截图）是唯一会长大的东西 |
| 系统 | **Ubuntu 22.04 LTS** | 本文所有命令按它写 |
| 带宽 | 3–5 Mbps 够用 | 主要传文本和几 MB 的图片 |

### 🔴 阿里云具体买哪一款（2026-10-15 查证）

> 详细下单步骤见 **`deploy/买服务器手册-阿里云.md`**。这里只说"为什么是它"。

**推荐：ECS 经济型 e 实例 —— 99 元/年**（官方"99 计划"）

| 配置 | 值 |
|---|---|
| CPU / 内存 | 2 核 2G |
| 带宽 | 3M **固定**带宽，不限流量 |
| 系统盘 | 40G ESSD Entry |
| 价格 | **99 元/年**，**续费同价、一口价锁到 2029-03-31**（日常价 1507.56/年）|
| 备案 | 支持备案 **5 个**网站/APP |
| 加赠 | 20 次漏洞修复 + 1000 次风险检查 + 2核主机病毒防护 + **100GiB 文件备份** |
| 资格 | **新老用户同享**（不用抢、不用学生认证）|

**🔴 为什么不买"轻量应用服务器"（虽然它首年只要 38–68 元）：**

| 对比项 | 轻量应用服务器 | **ECS 经济型 e（选它）** |
|---|---|---|
| 续费 | 首年便宜，**续费恢复原价** | **续费同价**，锁 5 年 |
| 备案域名数 | **仅 1 个** | **5 个** |
| 带宽 | 200M **峰值**（晚高峰可能被挤到几 M）| **3M 固定**，不跟别人抢 |
| 升级 | ❌ **不能转 ECS**，买错只能推倒重建 | ✅ 可升配（加内存/CPU 不用搬家）|
| 跑数据库 | 可以，但共用主机资源、无独立连接地址 | 标准 ECS，**跑 MySQL 更稳** |

> **决定性的两条**：① 你的项目要在一台机器上跑 **Nginx + Java + MySQL + Redis 四件套**，
> 轻量的"峰值带宽"在晚高峰会拖慢；② **轻量买错了不能转 ECS** —— 以后想升级得重新搭建整个环境。
> 多花的 30~60 元/年，买的是"不用重来"。

**安全组（云控制台的"防火墙"）——只开 3 个口：**

| 端口 | 协议 | 来源 | 为什么 |
|---|---|---|---|
| 22 | TCP | 你的常用 IP（能限定就限定） | SSH 登录 |
| 80 | TCP | 0.0.0.0/0 | HTTP（certbot 验证 + 跳转 HTTPS） |
| 443 | TCP | 0.0.0.0/0 | HTTPS |

🔴 **绝对不要开的三个端口：**

- `3306`（MySQL）—— 开了等于把数据库交出去。应用的连接是"本机到本机"，不走公网。
- `6379`（Redis）—— Redis 默认无密码，暴露公网 = 任何人都能读写你的数据。**这是被扫得最狠的端口之一。**
- `8080`（Java）—— 对外只该经过 Nginx。除非下面 §3 说的"没备案临时联调"，否则不要开。

---

## 3. 域名与备案（中国大陆服务器的硬门槛）

- 服务器在**中国大陆** ⇒ 用 80/443 提供网站服务，**域名必须先完成 ICP 备案**。没备案时云厂商通常会拦掉 80/443。
- 备案一般要 3–20 个工作日，走的是服务器提供商的备案系统（腾讯云/阿里云各自的入口）。
- 如果是**校园内网**用、或者服务器在境外，则不需要备案 —— 但境外服务器访问慢，且数据出境要另外评估。

**所以有两种上线节奏：**

| 情况 | 怎么做 |
|---|---|
| **域名 + 备案都好了** | 从 §4 一路做到 §13，完整流程。 |
| **还没备案** | 先做到 §9（后端在本机跑通），然后**临时**在安全组放行 8080，用 `http://SERVER_IP:8080` 直接连后端验证功能；等备案下来再补 §10 / §11，并把 8080 关掉。 |

---

## 4. 服务器初始化

用 SSH 登录（下面所有命令都在服务器上敲）：

```bash
ssh root@SERVER_IP
```

### 4.1 时区 —— 🔴 这是硬要求，不是"顺便设一下"

```bash
timedatectl set-timezone Asia/Shanghai
timedatectl
#   看到 Time zone: Asia/Shanghai (CST, +0800) 才对
```

**为什么必须设：** 数据库里 `create_time` 这些列用的是 `DEFAULT CURRENT_TIMESTAMP`，
取的是 **MySQL 进程所在机器的时区**。而 JDBC 连接串里写着 `serverTimezone=Asia/Shanghai`
（`application-prod.yml` 第 26 行）。两边不一致，所有时间就会**整体差 8 小时** ——
而且不会报任何错，你只会觉得"工单时间怎么不对劲"。

### 4.2 建一个专用运行账号（不用 root 跑 Java）

```bash
adduser --system --group --no-create-home --home /srv/second-classroom scapp

mkdir -p /srv/second-classroom/uploads /var/log/second-classroom /etc/second-classroom
chown -R scapp:scapp /srv/second-classroom /var/log/second-classroom
chmod 750 /srv/second-classroom /var/log/second-classroom
```

- `scapp` 是"服务账号"：没有密码、不能交互登录，只用来跑我们的 Java 进程。
- 万一应用被攻破，攻击者拿到的是 `scapp` 这个几乎没有权限的账号，而不是 root。

### 4.3 防火墙

```bash
apt update && apt install -y ufw
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable
ufw status verbose
```

> 云控制台的**安全组**和服务器上的 **ufw** 是两道独立的门，两边都要开对。
> 安全组开太多、ufw 关着，等于白设 —— 所以两道都做。

---

## 5. 装 JDK 17

```bash
apt install -y openjdk-17-jdk-headless
java -version
#   应显示 openjdk version "17.0.x"
```

> 为什么是 17：`pom.xml` 里写着 `<java.version>17</java.version>`，`Spring Boot 4.1.1` 也要求 17+。
> 装 21 也能跑，但**别装 8 / 11**，会直接起不来。

---

## 6. 装 MySQL 8（建库 + 建专用账号 + 灌数据）

### 6.1 安装

```bash
apt install -y mysql-server
systemctl enable --now mysql
mysql_secure_installation     # 一路按提示：设 root 密码、删匿名用户、禁 root 远程登录
```

### 6.2 建库 + 建应用专用账号

🔴 **不要用 root 跑应用。** 建一个只能碰 `second_classroom` 这一个库的账号：

```bash
mysql -uroot -p
```

```sql
-- 库的字符集必须是 utf8mb4：本项目有中文 FAQ、中文姓名、中文分类 ENUM，
-- 用 latin1/utf8（3字节）会存不下 emoji 也可能截断。
CREATE DATABASE second_classroom
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

-- 建两个同名账号：MySQL 把 127.0.0.1 解析成 'localhost' 还是 '127.0.0.1' 取决于配置，
-- 两个都建上就不用猜。
CREATE USER 'sc_app'@'127.0.0.1' IDENTIFIED BY 'DB_PASSWORD';
CREATE USER 'sc_app'@'localhost' IDENTIFIED BY 'DB_PASSWORD';

GRANT ALL PRIVILEGES ON second_classroom.* TO 'sc_app'@'127.0.0.1';
GRANT ALL PRIVILEGES ON second_classroom.* TO 'sc_app'@'localhost';
FLUSH PRIVILEGES;
EXIT;
```

> 💡 密码用 `caching_sha2_password`（MySQL 8 默认）就行。
> 我们的 JDBC 连接串里带了 `allowPublicKeyRetrieval=true`，所以**不开 SSL 也能连**，
> 不用去改认证插件。

### 6.3 灌初始数据（三步，顺序不能换）

先在**你的 Windows 电脑**上把名单转成 SQL（详见 `deploy/README-import.md`）：

```
双击 deploy\gen-import-sql.bat，把名单文件拖上去
→ 生成 deploy\import-users.sql
```

看到这段输出就说明成功了（工具本身只输出英文，对照着看）：

```
OK: 3 account(s) written to ...\import-users.sql
    STUDENT x 2
    STAFF x 1
    initial passwords (id -> password):
      2023001001  001001          ← 初始密码 = 学号后 6 位，记下来发给学生
```

然后把 4 个文件传到服务器：

```bash
# 在你的 Windows 电脑上（Git Bash / PowerShell 里都可以）：
scp schema.sql seed.sql import-users.sql root@SERVER_IP:/srv/second-classroom/
```

```bash
# 回到服务器上执行 —— 三步顺序不能换：
cd /srv/second-classroom

mysql -u sc_app -p --default-character-set=utf8mb4 second_classroom < schema.sql
mysql -u sc_app -p --default-character-set=utf8mb4 second_classroom < seed.sql
mysql -u sc_app -p --default-character-set=utf8mb4 second_classroom < import-users.sql
```

三个坑，每个都会静默出错：

| 坑 | 后果 | 说明 |
|---|---|---|
| 少写 `--default-character-set=utf8mb4` | **中文姓名 / FAQ 全变问号** | 客户端和服务器协商成 latin1 就完了。这条**不能省**。 |
| 先 `seed.sql` 后 `schema.sql` | 报"表不存在" | 顺序是 建表 → 管理员 → 名单。 |
| `schema.sql` 里没有 `CREATE DATABASE` | 报"未选择数据库" | 这是**故意的**（脚本只负责建表）。所以必须像上面那样先用 §6.2 建库、命令行用 `second_classroom` 指定库名。 |

### 6.4 自查（这几条必须全对）

```bash
mysql -u sc_app -p --default-character-set=utf8mb4 second_classroom
```

```sql
-- ① 表数 = 13
SELECT COUNT(*) FROM information_schema.TABLES WHERE table_schema='second_classroom';

-- ② 账号：至少 1 个管理员（2023003001 / 123456），中文姓名不能是问号
SELECT user_id, user_name, user_type, status FROM t_user ORDER BY user_id;

-- ③ 服务器设置核对
SELECT VERSION(), @@character_set_server, @@collation_server, @@sql_mode, @@time_zone;
--   VERSION()                → 8.0.x
--   character_set_server     → utf8mb4
--   collation_server         → utf8mb4_0900_ai_ci
--   sql_mode                 → ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,
--                              NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION
--   time_zone                → SYSTEM（SYSTEM 展开后就是 +08:00，前提是 §4.1 设了 Asia/Shanghai）
```

> ✅ 已帮你核对过：以上默认值与开发机（MySQL 8.0.46）**完全一致**，不需要额外调参。
> `lower_case_table_names` 在 Windows 上是 1、Linux 上是 0 —— 已确认**本项目所有表名都是小写**，
> 所以这个差异对我们没有影响。

---

## 7. 装 Redis（就是你昨天在 Windows 上做的那件事）

```bash
apt install -y redis-server
systemctl enable --now redis-server
redis-cli ping
#   → PONG
```

`systemctl enable` 在这里的角色，**精确等于**你昨天在 Windows 上右键 `install-redis-service.bat`：
装上之后开机自己起，不用人工干预。

### 7.1 确认它只对"本机"开放（重要）

```bash
ss -lntp | grep 6379
#   必须是 127.0.0.1:6379     ← 只有本机
#   如果看到 0.0.0.0:6379 或 *:6379 → 立刻去改 /etc/redis/redis.conf 的 bind
```

Ubuntu 装出来的默认配置就是 `bind 127.0.0.1 -::1` + `protected-mode yes`，一般不用改。
但**必须验证**，不能假设。

### 7.2 加两行配置（防的是"未来的自己"）

```bash
nano /etc/redis/redis.conf
```

```conf
maxmemory 256mb
maxmemory-policy noeviction
```

```bash
systemctl restart redis-server
```

🔴 **`maxmemory-policy` 千万别改成 `allkeys-lru` 之类的淘汰策略。**
Redis 里存的不只是缓存：还有**单号计数器**、幂等标记、登出黑名单。
被淘汰掉 → 幂等失效（同一请求重复执行）、**登出过的 Token 又能用了**。
`noeviction` 是默认值，"写不进去"会直接报错给应用，比"悄悄丢数据"安全得多。

### 7.3 关于持久化：**故意不开**

```conf
appendonly no
save ""
```

这不是偷懒，是设计好的：`DailySeqId` 里的计数器 key 万一丢了（Redis 重启），
下次用到时会**自动按数据库里的最大序号重新"播种"**，所以不会重复发号。
（这也是为什么本地 Redis 一直配的是"不落盘"。）

### 7.4 要不要设密码？

| 情况 | 做法 |
|---|---|
| Redis 只监听 127.0.0.1 + 防火墙封了 6379（**推荐**） | 不用设密码。`env` 文件里 `REDIS_PASSWORD=` 留空即可。 |
| 非要设密码 | 两步都要做：① `redis.conf` 里去掉 `# requirepass 你的密码` 的注释；② `env` 文件里填 `REDIS_PASSWORD=同一个密码`。**漏了第②步，应用连不上 Redis，所有需要单号的功能全 500。** |

---

## 8. 打包并上传

### 8.1 在 Windows 上打 jar

**最稳的方式（避开 `mvnw` 的老毛病）：** 在 IDEA 右侧 **Maven** 面板 → `Lifecycle` → 双击 **`package`**。

命令行方式是（二选一）：

```bat
:: 项目根目录 D:\basic codes\second-classroom
mvnw.cmd clean package -DskipTests
```

产物：`target\second-classroom-0.0.1-SNAPSHOT.jar`（约 30–40MB，已经把 Tomcat 一起塞进去了，
所以它能 `java -jar` 直接跑）。

### 8.2 上传

```bash
# 在你的 Windows 电脑上执行
cd "D:/basic codes/second-classroom"

scp target/second-classroom-0.0.1-SNAPSHOT.jar root@SERVER_IP:/srv/second-classroom/
scp -r deploy root@SERVER_IP:/root/
```

```bash
# 回到服务器
cd /srv/second-classroom
chown scapp:scapp second-classroom-0.0.1-SNAPSHOT.jar
```

---

## 9. 用 systemd 托管（开机自启 + 崩了自动重启）

仓库里已经备好了 `deploy/second-classroom.service` 和 `deploy/env.example`，
直接拿来用，不用自己写。

### 9.1 先填环境变量文件

```bash
mkdir -p /etc/second-classroom
cp /root/deploy/env.example /etc/second-classroom/env

# 生成一个 JWT_SECRET（复制输出，粘到下面去）
openssl rand -base64 64

nano /etc/second-classroom/env
```

把这三个改成真实值（其余保持默认）：

```conf
DB_PASSWORD=你§6.2设的那个密码
JWT_SECRET=刚 openssl 生成的那串
UPLOAD_DIR=/srv/second-classroom/uploads
```

```bash
chown root:root /etc/second-classroom/env
chmod 600 /etc/second-classroom/env      # 🔴 里面有密码，别让别的账号看
```

> 语法规矩（systemd 的 EnvironmentFile **不是 shell**）：
> 一行一个 `KEY=VALUE`、等号两边别加空格、**值不要加引号**、不要写 `export`。

### 9.2 装服务

```bash
cp /root/deploy/second-classroom.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now second-classroom
```

### 9.3 验收

```bash
systemctl status second-classroom --no-pager
#   Active: active (running)

journalctl -u second-classroom -n 60 --no-pager
#   应能看到：The following 1 profile is active: "prod"
#            Tomcat started on port 8080
```

```bash
curl -s http://127.0.0.1:8080/health
```

**期望返回这样（`redis` 必须是 `UP`）：**

```json
{"code":0,"message":"ok","data":{"status":"UP","db":"UP","redis":"UP","timestamp":"..."}}
```

**如果返回 HTTP 503 且 `redis":"DOWN"`** → 去 §7 检查 Redis 有没有起来、`bind` 和密码对不对。
这个接口是**如实反映**，不是 bug：任何关键组件不可用它就是 503。

### 9.4 演示一下"崩了会自己起来"

```bash
systemctl restart second-classroom     # 手动重启
systemctl stop second-classroom        # 停掉
systemctl status second-classroom --no-pager   # inactive (dead)
systemctl start second-classroom       # 再起来
```

> 真的崩（进程被 kill、内存不足被杀）时，`Restart=always` 会在 5 秒后自动拉起来。
> 但 5 分钟内失败超过 10 次就会停下不再重启（`StartLimitBurst`）—— 这是故意的，
> 免得配置写错时无限重启把日志刷爆。这时 `systemctl status` 会显示 `failed`，需要人看一眼。

---

## 10. Nginx 反向代理

```bash
apt install -y nginx
cp /root/deploy/nginx-second-classroom.conf /etc/nginx/sites-available/second-classroom

# 把文件里的 YOUR_DOMAIN 换成你的域名（还没域名就先填服务器公网 IP）
nano /etc/nginx/sites-available/second-classroom

ln -sf /etc/nginx/sites-available/second-classroom /etc/nginx/sites-enabled/
rm -f /etc/nginx/sites-enabled/default      # 去掉 Nginx 自带的欢迎页，否则它会抢 80 端口

nginx -t                                    # 必须 syntax is ok / test is successful
systemctl reload nginx
```

### 🔴 这个配置里有两行是"不写就出怪问题"的

**第一行：`client_max_body_size 10m;`**

Nginx 默认只允许 **1MB** 的请求体。而本应用允许**单文件 5MB、整个请求 6MB**
（`application-prod.yml` 的 `spring.servlet.multipart`）。

不写这行会怎样：用户传一张 3MB 的收据照片 → **Nginx 直接返回 413，请求压根到不了 Java** →
应用日志里**一片空白** → 你会以为是"上传接口写错了"，查半天查不出来。

**第二行：`proxy_set_header X-Forwarded-For $remote_addr;`**

审计日志（`t_operation_log.ip_address`）记的"客户端 IP"，来源是 `util/WebUtil.getClientIp()`，
它**优先读 `X-Forwarded-For` 的第一个值**。

- 不设这两个头 → 审计里所有人的 IP 都是 `127.0.0.1`，追责追不到人；
- 用常见的 `$proxy_add_x_forwarded_for`（追加）→ 客户端可以自己伪造一个 `X-Forwarded-For` 发过来，
  伪造值会被留在最前面，被当成真实 IP 记下来 → **审计可以被人故意写歪**；
- 用 `$remote_addr`（**覆盖**）→ 记下的永远是"真正连上 Nginx 的那个地址"。这就是配置里选它的原因。

### 验收

```bash
curl -s http://127.0.0.1/health -H "Host: 你的域名"
#   返回和 §9.3 一样

# 从你 Windows 电脑的浏览器打开
#   http://你的域名/health
```

---

## 11. HTTPS（免费证书）

**前提：域名已经解析到 `SERVER_IP`，且备案已通过。** 先确认解析生效：

```bash
ping 你的域名        # 或 dig +short 你的域名
#   应返回 SERVER_IP
```

```bash
apt install -y certbot python3-certbot-nginx
certbot --nginx -d 你的域名
#   按提示填邮箱、同意条款、选 "2: Redirect"（把 http 自动跳到 https）
```

certbot 会**自动改写** `/etc/nginx/sites-available/second-classroom`
（加 `listen 443 ssl` 和一处 80 → 443 跳转），并装好自动续期定时器。

```bash
certbot renew --dry-run     # 演练一次续期，应该成功
systemctl list-timers | grep certbot   # 确认续期定时器在
```

改完之后记得**再看一眼配置没被改坏**：

```bash
nginx -t && grep -n "client_max_body_size" /etc/nginx/sites-available/second-classroom
#   client_max_body_size 那行必须还在（certbot 是新增，不会删它，但值得确认）
```

---

## 12. 备份（数据库 + 附件，缺一不可）

### 🔴 为什么这两样必须一起备

`t_attachment` 表里存的是文件的**路径**，文件本体在 `UPLOAD_DIR` 那块磁盘上。

- 只备份数据库 → 恢复后**附件全变成打不开的死链**；
- 只备份 uploads → 恢复后**没有任何工单引用这些文件**，等于一堆孤儿。

它们是"一份备份"的两个半边。

### 安装

```bash
# ① 让 mysqldump 不用在命令行写密码
nano /root/.my.cnf
```
```ini
[client]
user=root
password=你的数据库root密码
```
```bash
chmod 600 /root/.my.cnf

# ② 放脚本
cp /root/deploy/backup.sh /usr/local/bin/second-classroom-backup.sh
chmod +x /usr/local/bin/second-classroom-backup.sh

# ③ 手动跑一次，确认能成功
/usr/local/bin/second-classroom-backup.sh

# ④ 定时：每天凌晨 3:30
crontab -e
#   加一行：
#   30 3 * * * /usr/local/bin/second-classroom-backup.sh >> /var/log/sc-backup.log 2>&1
```

脚本会做：`mysqldump | gzip` → 校验压缩流 + 检查末尾有 `Dump completed`（**备份"跑完了没报错"≠备份可用**）
→ 打 `uploads` 包 → 删掉 14 天前的。

### 🔴 光有备份不够，还要演练恢复

**至少做一次**，否则你永远不知道备份能不能用：

```bash
# 拿最新的一份，恢复到一张临时表所在的临时库，确认能读
LATEST=$(ls -1d /var/backups/second-classroom/*/ | tail -1)
mysql -uroot -p -e "CREATE DATABASE sc_restore_check DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
gzip -dc "$LATEST/db-second_classroom.sql.gz" | mysql -uroot -p --default-character-set=utf8mb4 sc_restore_check
mysql -uroot -p -e "SELECT COUNT(*) FROM sc_restore_check.t_work_order; SELECT user_id,user_name FROM sc_restore_check.t_user LIMIT 3;"
#   ↑ 数字对得上、中文姓名不是问号 → 恢复可用
mysql -uroot -p -e "DROP DATABASE sc_restore_check;"
```

> 💡 备份放在**同一台机器**上只防"手滑删库"，不防"磁盘/服务器整个没了"。
> 有条件的话把 `/var/backups/second-classroom` 同步到对象存储或另一台机器
> （例如每天 `rclone sync` 或 `scp` 到另一台）。

---

## 13. 上线自检清单（逐条打勾）

**基础**

- [ ] `timedatectl` 显示 `Asia/Shanghai (CST, +0800)`
- [ ] `java -version` 是 17.x
- [ ] `systemctl is-enabled mysql` == `enabled`
- [ ] `systemctl is-enabled redis-server` == `enabled` ← **Linux 版的"装成服务"**
- [ ] `systemctl is-enabled second-classroom` == `enabled` ← 开机自启
- [ ] `ss -lntp | grep -E ':(6379|3306|8080)'` —— 三个都只监听 `127.0.0.1`
- [ ] `ufw status` 只开了 22 / 80 / 443

**接口功能**（每条都真的点一遍；curl 是最能定位问题的点法）

```bash
# ① 登录拿 Token（把返回 JSON 里的 data.token 复制出来，下面要用）
curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"userId":"2023003001","password":"123456"}'
#   期望：{"code":0,...,"data":{"token":"eyJ...","role":"ADMIN","needChangePassword":true,...}}
#   ⚠️ needChangePassword=true 就是在提醒你"这还是初始密码，赶紧改"

# 把 Token 存进变量，后面的命令都直接用它
TOKEN='粘贴刚才那个 eyJ...'

# ② 看我自己的信息（验 Token 能认）
curl -s http://127.0.0.1:8080/api/auth/me -H "Authorization: Bearer $TOKEN"
```

- [ ] 管理员 `2023003001 / 123456` 登录成功，`role` 是 `ADMIN`
- [ ] 学生账号（名单里的）用**学号后 6 位**能登录，返回的 `userName` 是**中文姓名、不是问号**
- [ ] 提交一个工单 → 工单号形如 `WO202609290001`
- [ ] 传一张 3MB 左右的图片 → **能成功**（这一步就是在验 §10 的 `client_max_body_size`）

```bash
# ③ 上传附件（准备一张 3MB 左右的图，路径换成你自己的）
curl -s -X POST http://127.0.0.1:8080/api/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@C:/Users/你/Desktop/test.jpg"
#   期望：code=0，data 里有 attachmentId
#   ❌ 如果返回 413 → §10 的 client_max_body_size 没写或没 reload
#   ❌ 如果返回 400 文件类型不支持 → 后缀不在白名单
```

```bash
# ④ 下载刚上传的附件（把 {id} 换成上一步的 attachmentId）
#   ⚠️ 这个接口是**第一个"成功时不返回 Result 包装"**的接口，直接吐文件字节
curl -s -o /tmp/got.jpg -D - http://127.0.0.1:8080/api/files/粘贴attachmentId \
  -H "Authorization: Bearer $TOKEN"
#   期望：HTTP 200 + Content-Type 是图片类型；/tmp/got.jpg 能打开、大小和原图一致
```

- [ ] 下载的附件能打开、大小和原图一致
- [ ] 导出 Excel（#47）→ 能下下来、且能打开
- [ ] 管理端新建一条 FAQ（**问题里带 3 个以上汉字**），前台 `GET /api/faqs?keyword=...` → **能搜到**
      （⚠️ 关键词少于 2 个字搜不到，这是 ngram 分词器的固有行为，不是 bug）
- [ ] 记一条操作 → 去管理端看审计日志 → **`ipAddress` 是你的真实公网 IP**，
      而**不是** `127.0.0.1`（这一步在验 §10 的 XFF 配置）

> 💡 前面 §10 的 `curl -s http://127.0.0.1/health -H "Host: 你的域名"` 是在验"Nginx → 后端"这条链路；
> 这里用 `127.0.0.1:8080` 是绕过 Nginx 验后端本身。**两条都要走通**，
> 这样出问题时你能立刻判断是"后端的问题"还是"Nginx 的问题"。

**时间与时区**

- [ ] 刚提交的工单，`create_time` 和你的手机时间**一致**（不是差 8 小时）

**重启演练（最重要的一条，很多系统就是栽在这）**

```bash
reboot
# 等 1 分钟，重新 SSH 上去
systemctl status mysql redis-server second-classroom --no-pager
curl -s http://127.0.0.1:8080/health
```

- [ ] 重启后三个服务**都自己起来了**，`/health` 返回 200 + `redis":"UP"` ——
      **全程没有手动启动任何东西**

---

## 14. 日常运维

### 发新版（迭代流程）

```bash
# ① 先备份（万一新版本有问题，这是后悔药）
/usr/local/bin/second-classroom-backup.sh

# ② Windows 上重新 package，然后上传新 jar
scp target/second-classroom-0.0.1-SNAPSHOT.jar root@SERVER_IP:/srv/second-classroom/

# ③ 服务器上重启
chown scapp:scapp /srv/second-classroom/second-classroom-0.0.1-SNAPSHOT.jar
systemctl restart second-classroom
journalctl -u second-classroom -f      # 盯着看有没有异常，Ctrl+C 退出
curl -s http://127.0.0.1:8080/health
```

> ⚠️ **数据库结构有变化时**（改了 `schema.sql`），要在 §③ 之前先对**线上库**执行对应的
> `ALTER TABLE`。直接 replace jar 不会自动改表。

### 常用命令

| 想干什么 | 命令 |
|---|---|
| 看应用日志（实时） | `journalctl -u second-classroom -f` |
| 看应用日志（最近 200 行） | `journalctl -u second-classroom -n 200 --no-pager` |
| 看应用自己写的日志文件 | `tail -f /var/log/second-classroom/second-classroom.log` |
| 重启后端 | `systemctl restart second-classroom` |
| 重启 Nginx | `systemctl reload nginx` |
| 看 Nginx 访问日志 | `tail -f /var/log/nginx/second-classroom.access.log` |
| 进 MySQL | `mysql -u sc_app -p --default-character-set=utf8mb4 second_classroom` |
| 进 Redis | `redis-cli`（有密码时 `redis-cli -a 密码`） |
| 看磁盘占用 | `du -sh /srv/second-classroom/uploads /var/backups/second-classroom` |
| 看端口占用 | `ss -lntp` |

---

## 15. 故障对照表

| 症状 | 最可能的原因 | 怎么办 |
|---|---|---|
| `systemctl status` 里是 `failed`，日志写 `Could not resolve placeholder 'XXX'` | `/etc/second-classroom/env` 缺了必填变量，或写了引号/空格 | 按 §9.1 补上；**值不要加引号** |
| 同上，日志写 `Access denied for user 'sc_app'@'localhost'` | `DB_PASSWORD` 和 §6.2 设的不一致 | 改 `env` 或 `ALTER USER 'sc_app'@'localhost' IDENTIFIED BY '新密码';` |
| 同上，日志写 `Communications link failure` | MySQL 没起来 | `systemctl start mysql` |
| 起得来，但 `/health` 返回 **503** 且 `redis":"DOWN"` | Redis 没起 / `bind` 或密码不对 | `systemctl start redis-server`；`redis-cli ping`；核对 `REDIS_PASSWORD` |
| 提交工单报 **500**，消息里有 `单号生成失败：Redis 不可用` | Redis 挂了。**这条是设计好的响亮失败**，不是 bug | 起 Redis。`DailySeqId` 故意不做降级，因为降级会发出重复单号 |
| 登录正常，但点任何需要权限的接口都 401 | 你手上是登出过的旧 Token（登出黑名单生效了） | 重新登录 |
| 浏览器打开域名是 **502 Bad Gateway** | Nginx 到了，但后端 8080 没在跑 | `systemctl status second-classroom`；`curl 127.0.0.1:8080/health` |
| 上传 3MB 图片返回 **413** | §10 的 `client_max_body_size` 没写或没 reload | 加上并 `nginx -t && systemctl reload nginx` |
| 上传图片返回 `400 文件类型不支持` | 后缀不在白名单里 | 白名单：jpg/jpeg/png/gif/bmp/webp/pdf/doc/docx/xls/xlsx/ppt/pptx/txt/zip/rar |
| 审计日志里的 IP 全是 `127.0.0.1` | §10 的两个 `proxy_set_header` 没设 | 补上并 reload |
| 工单时间差 8 小时 | 服务器时区不是 Asia/Shanghai | §4.1 `timedatectl set-timezone Asia/Shanghai`，然后 `systemctl restart mysql second-classroom` |
| FAQ 关键词搜不到 | 关键词**少于 2 个字** → ngram 分词器搜不到 | 用 2 个字以上的关键词；这是固有行为 |
| 学生登录报"账号不存在" | 名单没导入成功 | 查 `SELECT COUNT(*) FROM t_user;`，必要时重跑 §6.3 第 3 步 |
| 学生姓名显示成问号 | 导入时漏了 `--default-character-set=utf8mb4` | 删掉那些行重新导入（中文一旦存成问号，改不回来） |
| 磁盘满了 | `uploads` 涨太快 / 备份没清 | `du -sh` 定位；检查 cron 里的备份脚本有没有在跑 |
| 应用隔几天被 OOM 杀掉 | 2G 内存不太够 | 调小 `-Xmx`（`second-classroom.service` 里），或加 swap，或升配置 |

---

## 16. 安全收尾与已知欠账

### 上线当天必须做的 4 件事

1. **改掉管理员初始密码。**
   初始是 `2023003001 / 123456`（`seed.sql` 里写死的）。
   登录 → `PUT /api/auth/password` → `{"oldPassword":"123456","newPassword":"你的新密码"}`
   （新密码要求 6–20 位，且**同时含字母和数字**）。
2. **确认 `JWT_SECRET` 是新生成的**（不是 `application.yml` 里那个开发值）。
   改了 `JWT_SECRET` 之后所有人需要重新登录一次。
3. **确认 3306 / 6379 / 8080 在安全组里是关的。**
4. **确认 `/etc/second-classroom/env` 是 `600 root:root`。**

### 顺带提醒

- **别把 `env` / `import-users.sql` 提交进 git** —— 里面有密码和所有人的初始密码。
- 附件目录 `uploads` 会一直长大，几个月看一眼磁盘。
- 日志 `logging.logback` 配的是 20MB × 14 天自动滚动，一般不用管。

### 已知欠账（本次没做，将来要补）

> ⚠️ **2026-10-14 更新**：下表原来列的 **jieba 重复检测 / XSS 转义 / 回复追问敏感词检测**
> **三条都已经做完了**，已删。详见 `target/doc.txt` 统一修订项第 16~21 条。

| 项 | 影响 | 什么时候必须做 |
|---|---|---|
| 重复检测的「同义不同词」漏报 | `热水机` vs `热水器` 交集 0，同一件事判不出重复 | **已拍板暂不修**（属漏报，不误拦）。治本要换字符二元组/近义词表 |
| 405 方法不匹配时绕过了拦截器 | 只暴露"路径存在"，不是鉴权漏洞 | 要修得把鉴权从拦截器挪到 Filter |
| 中文分类 ENUM 在 4 处重复定义 | 改分类要改 4 个地方，容易漏 | 有空时收敛成一处 |
| 工单分类是**中文** ENUM（`t_work_order.category`） | 与"数据库枚举一律英文"的约定不一致 | 历史遗留，改动面大，先不动 |

---

## 附：本次上线用到的文件都在哪

| 文件 | 作用 |
|---|---|
| **`deploy/买服务器手册-阿里云.md`** | **📌 起点**：阿里云下单对照手册（选哪款、每项怎么填、域名备案入口）|
| **`deploy/预演报告-本机.md`** | 2026-10-14 本机预演 prod 配置的记录（实测结果 + 三个反例）|
| `schema.sql`（项目根目录） | 建 13 张表。**不含 `CREATE DATABASE`、不含任何 INSERT** |
| `deploy/seed.sql` | 初始数据：只有 1 个管理员。**只能对空库跑一次** |
| `deploy/GenImportSql.java` | 名单 → INSERT 语句（bcrypt 哈希；SQL 里算不出来，必须用代码） |
| `deploy/gen-import-sql.bat` | 上面那个工具的包装（中文提示，双击/拖文件即可） |
| `deploy/users-example.csv` | 名单格式示例（**故意不含 2023003001**，否则和 seed.sql 主键冲突） |
| `deploy/README-import.md` | 批量建号的中文说明 + 坑表 |
| `deploy/second-classroom.service` | systemd 托管单元 |
| `deploy/env.example` | 环境变量模板（复制成 `/etc/second-classroom/env`） |
| `deploy/nginx-second-classroom.conf` | Nginx 站点配置 |
| `deploy/backup.sh` | 每日备份脚本 |
| `src/main/resources/application-prod.yml` | 生产配置档（`--spring.profiles.active=prod` 时生效） |
