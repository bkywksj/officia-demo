---
name: officia-deploy
description: |
  把 officia 测试台/应用容器化并部署：Docker 镜像、docker-compose 编排、
  监听地址与安全边界、容器内存 → JVM 堆 → 上传上限的联动、中文字体三条入口、
  License 注入、远程部署（走 Reeve）与验收排查。

  触发场景：
  - 要把 demo/测试台打成 Docker 镜像、写 docker-compose 编排
  - 要部署到服务器 / 上线 / 给团队共享一个测试台
  - 容器起来了但连不上（端口映射不通）
  - 容器里 PDF 中文水印是方块 / 启动横幅说没探测到字体
  - 容器里传大文件报 413，不知道该调容器内存还是 JVM 堆
  - 容器里输出带水印，要注入 .lic
  - 多阶段构建 Docker 镜像时 Maven 拉不到 officia 依赖

  触发词：部署、上线、发布、容器、Docker、镜像、compose、编排、docker-compose、Dockerfile、K8s、服务器、运维、Reeve、端口映射、连不上、健康检查、healthcheck、mem_limit、堆、MaxRAMPercentage、生产环境
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# 容器编排与部署

## 概述

officia 运行时零第三方依赖（只要 JDK 17+），所以容器化本身很简单——**难点全在四个本项目特有的边界上**：
监听地址、内存联动、字体入口、授权注入。这四个每一个踩错，表现都是"看起来起来了，实际不能用"。

编排文件在 `deploy/`：`Dockerfile` / `docker-compose.yml` / `.env.example` / `README.md`。

---

## 🔴 部署前置流程（最高优先级，必须先走完再动手）

**默认一律 Docker 部署**（项目自带 `deploy/Dockerfile` + compose）。除非用户明确说「用原生/JAR/systemd」，否则不要改方案。

### 第 0 步 · 探测目标环境（只读，先全查一遍）

- 系统/架构、CPU、**内存 + swap**、磁盘可用空间
- 已装什么：**Docker / docker compose**、1Panel、端口 18080 是否被占
- 本地构建环境：JDK 17+、Maven、**本地仓有没有 officia**（`ls ~/.m2/repository/plus/ruoyi/officia-all/`）

### 第 1 步 · 把探测结果 + 方案讲给用户，停下等确认

一段话汇报现状 + 给出默认 Docker 方案，有取舍就给选项。**必须拿到明确「确认 / 继续」才动手**。

### 第 2 步 · 有 Reeve MCP → 优先走 Reeve

远程部署（部署到服务器）一律走 **Reeve**：凭据托管、策略 + 审批 + 审计、可回滚，别裸 SSH 手搓。

```
1. list_servers                          → 拿服务器别名（AI 只认别名，永远拿不到凭据）
2. system_info / disk_usage / port_check → 探现状（只读，任何档位放行）
   ssh_exec "docker --version; docker compose version; free -h; groups"
                                         → Docker 装没装、内存多大、当前用户在不在 docker 组
3. （把现状 + 方案报给用户，等确认）
4. ssh_exec "mkdir -p ~/apps/officia-demo/{target,deploy}"
5. sftp_upload  jar → target/、Dockerfile + docker-compose.yml → deploy/
6. ssh_exec 写 .env（见下方坑 ①）
7. ssh_exec 后台构建 + 轮询（见下方坑 ②）
8. 验收三连
```

**坑 ① `.env` 传不上去**：Reeve 的 SFTP 敏感文件名黑名单拦 `.env*`（**连 `.env.example` 也拦**，任何档位）。
这是保护机制，不要试图绕过。改用 `ssh_exec` + heredoc 现场写——本项目的 `.env` 里只有端口/内存，无任何凭据：

```bash
cd ~/apps/officia-demo/deploy && cat > .env <<'EOF'
HOST_PORT=18080
BIND_ADDR=0.0.0.0
MEM_LIMIT=8g
EOF
```

**坑 ② 构建会把 `ssh_exec` 拖超时**：拉基础镜像 + apt 装字体动辄几分钟，`ssh_exec` 默认 30s。
用后台跑 + 日志文件，再单独轮询，而不是把构建塞进一次调用：

```bash
# 启动（立即返回）
cd ~/apps/officia-demo/deploy && nohup docker compose up -d --build > /tmp/officia-build.log 2>&1 &

# 轮询（一次调用等到结束，timeout_secs 给足）
for i in $(seq 1 110); do
  docker ps --filter name=officia-demo --format '{{.Status}}' | grep -q . && break
  pgrep -f "docker compose up" > /dev/null || break
  sleep 5
done; tail -15 /tmp/officia-build.log
```

**坑 ③ 目标机拉不动 Docker Hub**：构建长时间卡在拉基础镜像（日志里同一个进度数字几分钟不动，
如 `13.63MB / 29.39MB 438s` → `463s` 纹丝不动）——**别干等**，改「本机构建 + 镜像搬运」：

```bash
# 本机（已验证过的镜像，搬过去行为完全一致）
docker save officia-demo:1.0.0 | gzip -c > officia-demo-1.0.0.tar.gz   # 426MB → 约 203MB
# → sftp_upload 到目标机
# 目标机
gunzip -c officia-demo-1.0.0.tar.gz | docker load
cd deploy && docker compose up -d        # 不带 --build：镜像已存在，compose 直接用
rm -f officia-demo-1.0.0.tar.gz          # 用完删掉，别占盘
```

> 这条路还有个额外好处：**部署的是本机已验收通过的那个镜像**，不会出现"本机好好的、服务器上构建出来不一样"。

> 没有 Reeve、或用户明确要手动时才用本文档末尾的手动流程。

### 部署方式选择（铁律）

- **默认 Docker**——裸机也是「装 Docker + compose」，**不是**改原生。
- **内存紧张 ≠ 弃 Docker**：正解 = 加 swap + `mem_limit` + 调 `MaxRAMPercentage`，仍跑 Docker。
- 任何情况下**都不要**自己默默从 Docker 切到原生。

---

## 快速上手

```bash
# 1) 宿主构建 jar（不能在容器里跑 mvn，见下方"构建策略"）
mvn -o package -DskipTests

# 2) 起容器
cd deploy && cp .env.example .env
docker compose up -d --build

# 3) 验收三连
docker compose logs officia-demo | head -30            # 启动横幅：监听地址 / 字体 / 授权 / 内存上限
curl -s http://127.0.0.1:18080/api/health               # {"ok":true,...,"maxUpload":...,"maxHeap":...}
curl -s -X POST http://127.0.0.1:18080/api/batch/run    # 全能力回归，期望 "pass":10,"fail":0
```

> 🌟 `/api/batch/run` 是本项目最强的验收手段：用 officia 自造输入跑 10 个用例（Cells/Pdf/BarCode/Imaging/Email/License），
> 一条命令就能证明"容器里的 officia 是完好的"。换环境、升版本、排查"是不是环境问题"都先点它。

---

## 🔴 一、监听地址：容器化的头号坑

`DemoServer` 默认只监听 `127.0.0.1`（测试台无鉴权，默认对外是不可接受的）。
**容器内绑回环 = 只有容器内部连得上**，`-p 18080:18080` 转发到容器 eth0，宿主永远连不上（表现为 connection reset，不是超时）。

三层地址，缺一不可：

| 层 | 配置 | 值 | 为什么 |
|---|---|---|---|
| 进程监听 | `--host` / `OFFICIA_DEMO_HOST` | `0.0.0.0` | 容器内必须监听所有网卡 |
| 容器端口 | `EXPOSE` / compose `ports` 右侧 | `18080` | 容器内端口 |
| 宿主发布 | compose `ports` 左侧（`BIND_ADDR`） | **`127.0.0.1`**（默认） | 🔴 决定谁能访问，见下 |

```yaml
ports:
  - "${BIND_ADDR:-127.0.0.1}:${HOST_PORT:-18080}:18080"
```

| `BIND_ADDR` | 谁能访问 | 什么时候用 |
|---|---|---|
| `127.0.0.1`（默认） | 只有宿主本机 | 本机自用；远程走 SSH 隧道 `ssh -L 18080:127.0.0.1:18080 user@host` |
| `0.0.0.0` | 同网段 / VPN 内任意机器 | 内网可信、要免隧道直访时；**该网络内所有人都能用它传文件、吃你的 CPU** |

> 三层里**任意一层错了都表现为"连不上"**：进程绑回环 → 容器外连不上；`BIND_ADDR` 绑回环 → 别的机器连不上。
> 先看启动横幅的「监听地址」行，再看 `docker compose ps` 的端口映射，两步就能定位是哪一层。

命令行三种等价写法（优先级：`--host` > 环境变量 > 默认回环）：

```bash
java -jar officia-demo-1.0.0.jar 18080 --no-open --host 0.0.0.0
java -jar officia-demo-1.0.0.jar 18080 --no-open --host=0.0.0.0
OFFICIA_DEMO_HOST=0.0.0.0 java -jar officia-demo-1.0.0.jar 18080 --no-open
```

> 监听非回环地址时启动横幅会打印 ⚠ 安全提示——那不是报错，是提醒你确认暴露范围是你要的。
> 容器无桌面，`--no-open` 必带。

---

## 🔴 二、安全红线

测试台**没有任何鉴权**，并且能：上传任意文件、加载 License、读服务端字体、跑重 CPU/内存的转换。

| 场景 | 做法 |
|---|---|
| 本机自用 | 默认即可（`BIND_ADDR=127.0.0.1`） |
| 从别的机器访问（推荐） | **SSH 隧道**：`ssh -L 18080:127.0.0.1:18080 user@host`，浏览器开 `http://127.0.0.1:18080` |
| 可信内网 / VPN 直访 | `BIND_ADDR=0.0.0.0`，**前提是该网络里的人都可信**——他们全都能上传文件、吃满这台机器 |
| 团队共享（人较多） | 反代 + Basic Auth + `client_max_body_size` + 限流，**且仍不放公网** |
| 直接绑公网 | ❌ 等于送人一个免费文档转换器 + 一个把机器打挂的入口 |

> 授权后风险更高：别人用的是**你的 License 额度**在跑转换。

---

## 🔴 三、内存三级联动（本项目独有）

三个值是**链式推导**的，改一个全动：

```
容器 mem_limit  →  JVM 最大堆（MaxRAMPercentage=75）  →  单次上传上限（堆 ÷ 8，夹在 [64MB, 1GB]）
                                                     →  结果仓上限（max(512MB, 堆 ÷ 4)，最多 200 条）
```

| `mem_limit` | JVM 堆 | 单次上传上限 | 结果仓上限 | 适用 |
|---|---|---|---|---|
| 1g | ~768m | 96 MB | 512 MB | ⚠️ 结果仓下限逼近堆，易 OOM，不建议 |
| 2g | ~1.5g | 192 MB | 512 MB | 常规文档够用 |
| **4g** | ~3g | 384 MB | 768 MB | **推荐默认** |
| 8g | ~6g | 768 MB | 1.5 GB | 大设计型 PPTX（常上百 MB） |

🔴 **`MaxRAMPercentage` 必须显式给**：不设时 JVM 只取容器内存的 1/4，4g 容器只有 1g 堆、上传上限被压到 128MB，
而横幅和 `/api/health` 会如实显示——发现数字不对先查这个。

核对实际值：

```bash
curl -s http://127.0.0.1:18080/api/health
# {"ok":true,"blobs":0,"bytes":0,"endpoints":32,"maxUpload":402653184,"maxHeap":3221225472}
```

---

## 四、中文字体：三条入口，各管各的

| 入口 | 管什么 | 容器里怎么配 |
|---|---|---|
| **officia 内置字体**（classpath `/fonts/WenQuanYiMicroHei.ttf`，Apache-2.0） | 转换类的兜底字形 | 零配置，空白容器也能出中文 |
| **`OFFICIA_FONTS_DIR`**（或 `-Dofficia.fonts.dir`，`:` 分隔多目录） | officia 排版引擎的运维字体目录 | `ENV OFFICIA_FONTS_DIR=/usr/share/fonts` |
| **测试台 `Fonts.java` 固定路径探测** | **PDF 水印 / 页码**的 `fontTtf` | 必须装系统字体，且**路径要在探测表里** |

### 🔴 装字体只有一条硬标准：TrueType `glyf` 轮廓

officia 的 PDF 子集器**只支持 glyf**。装错轮廓的后果分两种，症状完全不同：

| 装了什么 | 会怎样 |
|---|---|
| **CFF/OTTO 轮廓**（`fonts-noto-cjk`、思源黑体 `.otf`、macOS `PingFang.ttc`） | `FontLoader` 加载阶段直接跳过；显式当 `fontTtf` 传进去则抛 `字体无 glyf/loca` → 水印接口 **400** |
| **纯拉丁 glyf**（DejaVu、Arial） | **不报错**，中文码位落到 `.notdef` → 水印中文**静默画成空白/方框**，比报错难查得多 |

> ⚠️ **不要装 `fonts-noto-cjk`** —— Debian/Ubuntu 官方包装出来的 `NotoSansCJK-*.ttc` 是
> CFF 轮廓（sfnt 版本 `OTTO`），对 officia **零收益**：装了，中文水印照样出不来。
> 用 `fonts-wqy-zenhei` / `fonts-wqy-microhei`（glyf、Apache-2.0、体积还小一个数量级）。

换字体包前先验轮廓：

```bash
od -A n -t x1 -N 4 /path/to/font.ttf | tr -d ' '
# 00010000 → glyf，可用
# 4f54544f → 'OTTO' = CFF，officia 用不了
# 74746366 → 'ttcf' 集合体，要再看它第 0 号 face 的版本
```

`deploy/Dockerfile` 已把这条验证内建成**构建期断言**：装出来是 CFF 就直接构建失败，
不会等到上线才发现水印是空白。

探测表认的 Linux 路径（含中文字形的，按优先级 —— glyf 的排在 CFF 前面）：

```
/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc          ← glyf，可用
/usr/share/fonts/truetype/wqy/wqy-microhei.ttc        ← glyf，可用
/usr/share/fonts/truetype/arphic/uming.ttc            ← glyf，可用
/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc  ← CFF，校验会拒
/usr/share/fonts/opentype/noto/NotoSansSC-Regular.otf   ← CFF，校验会拒
```

> `Fonts.java` 不只判"文件在不在"，还会验 **glyf 轮廓 + 真有中文字形**（`usableForCjk`），
> 校验不过就跳到下一个候选。所以表里保留 Noto 路径无害——它只是永远不会被选中。
>
> DejaVu / Arial 属**拉丁兜底**，排在最后——它们没有中文字形，命中它们时横幅会明说
> 「只探测到拉丁字体（中文水印会是空白）」，别把它当成"字体没问题"。

验收：

```bash
docker compose logs officia-demo | grep 中文字体
#   中文字体： 已探测到中文 TTF（PDF 中文水印可用）     ← 要的是这行（现在这句是可信的，因为探测验过字形）
docker compose exec officia-demo ls -l /usr/share/fonts/truetype/wqy/

# 端到端验一次最稳：水印中文抽得出来 = 真画出来了（画成空白时抽不出）
curl -s -X POST "http://127.0.0.1:18080/api/pdf/watermark?id=<PDF的id>&text=%E5%86%85%E9%83%A8%E8%B5%84%E6%96%99"
```

自备字体：把目录挂到 `/opt/officia/fonts` 并把 `OFFICIA_FONTS_DIR` 指过去。注意两条约束都要满足：
**分发授权允许**（文泉驿 Apache-2.0、Noto/思源 OFL 都可以；Windows 的宋体/黑体**不可以**），
且**轮廓是 glyf**（思源黑体、Noto CJK 的官方 OTF/OTC 版授权没问题，但 officia 用不了）。

---

## 五、License 注入

**铁律：`.lic` 绝不打进镜像层、绝不入库**（`.gitignore` 已拦 `*.lic`）。只读挂载 + 环境变量：

```yaml
environment:
  OFFICIA_LICENSE: /etc/officia/officia.lic
volumes:
  - ./officia.lic:/etc/officia/officia.lic:ro
```

K8s 用 Secret 挂载同理。不配也能跑——未授权只是降级（水印 + 限页），能力全部可用。

> **容器里输出带水印是预期行为**：`DemoServer` 启动时默认 `enableEnforcement(true)`，与正式发布版一致。
> 详见 `officia-license`。

---

## 六、构建策略：宿主构建 → COPY jar（多阶段现在也可行了）

固定流程仍是**宿主构建 → COPY jar**：

```bash
mvn -o package -DskipTests      # 产出 target/officia-demo-1.0.0.jar（shade 自包含）
docker build -f deploy/Dockerfile -t officia-demo:1.0.0 .   # 上下文必须是项目根目录
```

> ⚠️ **2026-08-09 起前提变了**：`officia-all:1.0.0` 已发布到 **Maven Central**
> （`https://repo1.maven.org/maven2/plus/ruoyi/officia-all/`），本节原先"未发布到公共仓库、
> 多阶段构建必然解析失败"的理由**不再成立**——现在容器内 `mvn` 能直接拉到它，多阶段可行。
>
> 但**默认仍推荐宿主构建**，理由换成了这两条：
> ① Central 上是 **release 版**（ProGuard 混淆 + 门控恒开），而本地开发验证常用 dev 版，
>    两者行为不同，宿主构建能保证"测的就是要发的"；
> ② 多阶段每次构建都要重新拉依赖，慢且依赖网络。
>
> 🔴 若改多阶段，**只引 `officia-all` 一个坐标**：Central 上没有 `officia-license` 与其它
> `officia-*` 子模块（只发布了聚合 uber-jar），引它们会 404。

---

## 七、手动远程部署（没有 Reeve 时的兜底）

```bash
# 本地
mvn -o package -DskipTests
scp target/officia-demo-1.0.0.jar user@host:/opt/officia-demo/target/
scp -r deploy user@host:/opt/officia-demo/

# 服务器
ssh user@host
cd /opt/officia-demo/deploy && cp .env.example .env
docker compose up -d --build
curl -s http://127.0.0.1:18080/api/health
```

镜像离线搬运（服务器不能联网拉基础镜像时）：

```bash
docker save officia-demo:1.0.0 | gzip > officia-demo.tar.gz
scp officia-demo.tar.gz user@host:/tmp/
ssh user@host 'gunzip -c /tmp/officia-demo.tar.gz | docker load'
```

---

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| `curl` 报 connection reset / empty reply | 容器内绑了 `127.0.0.1` | 传 `--host 0.0.0.0` 或设 `OFFICIA_DEMO_HOST`；看横幅"监听地址"行确认 |
| 宿主连得上，别的机器连不上 | `ports` 是 `127.0.0.1:18080:18080` | **设计如此**，要远程走 SSH 隧道 |
| 容器一直 unhealthy | 镜像没装 curl / 端口不一致 | `docker compose exec officia-demo curl -v http://127.0.0.1:18080/api/health` |
| 传大文件 413 | 上限 = 堆 ÷ 8 | 调大 `.env` 的 `MEM_LIMIT`；确认 `MaxRAMPercentage` 生效（查 `/api/health` 的 `maxHeap`） |
| `maxHeap` 只有容器内存的 1/4 | 没设 `MaxRAMPercentage` | 补 `JAVA_OPTS=-XX:MaxRAMPercentage=75` |
| 容器 OOMKilled（exit 137） | 堆比例过高或 `mem_limit` 太小 | 堆比例降到 70、`mem_limit` 提到 4g 以上 |
| 横幅说"未探测到"字体 | 字体包没装，或路径不在探测表 | 看第四节；`ls /usr/share/fonts/truetype/wqy/` 核对 |
| 横幅说"只探测到拉丁字体" | 只有 DejaVu/Arial（有 glyf 但无中文字形） | 装 `fonts-wqy-zenhei`（**不是** `fonts-noto-cjk`，那是 CFF 轮廓、officia 用不了） |
| 水印中文是**空白/方框**，正文中文却正常 | 拉丁字体排在中文字体前面，抢到了全局回退 | 装 glyf 中文字体；officia ≥ 该修复版已改为按中文码位选水印字体 |
| 水印接口报 400「字体无 glyf/loca」 | 传进去的是 CFF 轮廓字体（`.otf` / Noto CJK `.ttc`） | 换 glyf 字体；`Fonts.java` 的探测已会自动拒绝 CFF |
| 输出仍有水印 | 门控默认开 + 未加载 lic | 挂载 `.lic` + 放开 `OFFICIA_LICENSE`；见 `officia-license` |
| `docker build` 报找不到 jar | 构建上下文不是项目根目录 | `docker build -f deploy/Dockerfile … .`（末尾的 `.` 是根目录） |
| 多阶段构建拉不到 officia | 本地仓依赖 | 改宿主构建 + COPY jar，见第六节 |
| 重启后上传的文件全没了 | 只存内存，设计如此 | 无需挂卷；要留存请下载结果 |
| `docker logs` 中文乱码 | 少见（无 console 时程序按 UTF-8 输出） | 确认终端按 UTF-8 读；`docker compose logs` 不要经 GBK 管道转存 |

---

## 上线前检查清单

- [ ] `ports` 左侧带 `127.0.0.1:`（除非确认要更大暴露范围，且已加鉴权）
- [ ] `MEM_LIMIT` ≥ 2g，`JAVA_OPTS` 含 `-XX:MaxRAMPercentage=75`
- [ ] `/api/health` 的 `maxHeap` / `maxUpload` 与预期一致
- [ ] `docker compose logs` 里字体那行是「已探测到中文 TTF」
- [ ] `.lic` 走挂载而非 `COPY`，`git status` 里看不到它
- [ ] `POST /api/batch/run` 返回 `"fail":0`
- [ ] `docker compose ps` 状态是 `healthy`
- [ ] 宿主 `deploy/.env` 未入库（`.gitignore` 已含）

---

## 相关技能

| 接下来 | 用 |
|---|---|
| 依赖坐标 / 本地仓 / 离线装 officia | `officia-setup` |
| 授权加载与水印排查 | `officia-license` |
| 字体与 CID 嵌入的完整原理 | `officia-chinese-font` |
| 堆/并发/超时的调优依据 | `officia-performance` |
| 面板与端点清单 | `officia-testbench` |
| 集成进自己的 Spring Boot 应用 | `officia-spring-integration` |
