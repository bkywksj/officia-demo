# Officia 能力测试台 —— 容器镜像
#
# 【为什么是单阶段、不在容器里跑 mvn】
# officia-all / officia-license 只存在于宿主的本地仓 ~/.m2（未发布到公共 Maven 仓库），
# 多阶段构建里的 mvn 拿不到它们，必然在依赖解析阶段失败。所以 jar 一律先在宿主构建：
#     mvn -o package -DskipTests      → target/officia-demo-1.0.0.jar（shade 自包含）
# 本文件只负责把它装进一个带中文字体的 JRE 环境里。
#
# 构建（在项目根目录执行，构建上下文必须是根目录，因为要 COPY target/）：
#     docker build -f deploy/Dockerfile -t officia-demo:1.0.0 .
FROM eclipse-temurin:17-jre

LABEL org.opencontainers.image.title="officia-demo" \
      org.opencontainers.image.description="Officia 能力可视化测试台（运行时仅 JDK + officia）"

# 【2026-08-05 起默认不装系统中文字体】——CJK 交给 officia 的内置可变字体。
#
# 内置 NotoSansSC-VF 会在字体加载期按 wght 轴烘焙出 400（正文）/ 700（粗体）两个静态面
# （engine 的 VariableFontInstancer，依 OpenType fvar/avar/gvar 自研）。装了系统中文字体反而会被
# FontRegistry.resolveFamily 优先命中，内置字体<b>根本不参与</b>——线上也就看不到内置字体的实际表现。
# 想让线上跑内置字体（也便于验证字形改动），就别装系统中文字体。
#
# 要装回系统字体：docker build --build-arg FONT_PKG="fonts-wqy-zenhei fonts-wqy-microhei" ...
#
# 🔴 若要装，中文字体包必须是 TrueType **glyf** 轮廓 —— 不能用 fonts-noto-cjk。
# officia 的 PDF 子集器只支持 glyf；Debian/Ubuntu 的 fonts-noto-cjk 装出来的
# NotoSansCJK-*.ttc 内部是 CFF/PostScript 轮廓（sfnt 版本 'OTTO'），officia 在
# 加载阶段直接跳过，显式当 fontTtf 传进去则抛「字体无 glyf/loca」。
# 换包前先确认是 .ttf/.ttc 且为 glyf 轮廓（od -A d -t x1 -N 4 看 sfnt 版本，
# 00010000 = glyf 可用，OTTO = CFF 用不了；ttcf 集合体要看它第 0 号 face）。
ARG FONT_PKG=""

# curl 是 HEALTHCHECK 必需（temurin 镜像不自带 curl/wget）——**无论装不装字体都要有**，
# 把它和字体包写在一条 RUN 里时别顺手把整条删掉，否则容器起来即 unhealthy。
# 装了字体就当场验一次 glyf：装错包在【构建期】就炸，而不是等上线后发现水印是空白。
RUN apt-get update \
    && apt-get install -y --no-install-recommends ${FONT_PKG} curl \
    && rm -rf /var/lib/apt/lists/* \
    && F=/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc \
    && if [ -f "$F" ]; then \
         echo "== 已装系统中文字体 ==" && ls -l /usr/share/fonts/truetype/wqy/; \
         SFNT="$(od -A n -t x1 -N 4 "$F" | tr -d ' \n')"; \
         echo "== $F sfnt=$SFNT =="; \
         if [ "$SFNT" = "4f54544f" ]; then \
           echo "字体是 CFF/OTTO 轮廓，officia 用不了，构建中止" >&2; exit 1; \
         fi; \
       else \
         echo "== 未装系统中文字体：CJK 走 officia 内置可变字体（wght 400/700 实例化）=="; \
       fi

WORKDIR /app

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# OFFICIA_DEMO_HOST：容器内必须监听 0.0.0.0——绑回环的话端口映射转发到 eth0 会连不上。
#   对外暴露范围由 compose 的 ports 控制（默认只发布到宿主 127.0.0.1）。
# OFFICIA_FONTS_DIR：officia 排版引擎的运维字体目录（FontLoader 读该变量，支持 : 分隔多个）。
#   保留它是为了让"文档没指定字体"时的默认字体由镜像里的系统字体（基础镜像自带的拉丁字体）承担，
#   排版更接近真实环境；目录里没有中文字体也无妨——CJK 会按字形级回退落到 officia 内置字体。
#   注意内置字体<b>不占全局回退</b>（那是文档默认字体语义，见 FontLoader.demoteBundledFallback），
#   只在回退链里优先，所以这个变量与"CJK 用内置字体"两件事并不冲突。
# JAVA_OPTS：容器感知堆比例。不设的话 JVM 默认只取容器内存的 1/4，
#   而单次上传上限 = 最大堆 ÷ 8，会被连带压得很小。
ENV OFFICIA_DEMO_HOST=0.0.0.0 \
    OFFICIA_DEMO_PORT=18080 \
    OFFICIA_FONTS_DIR=/usr/share/fonts \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

COPY target/officia-demo-1.0.0.jar /app/officia-demo.jar

EXPOSE 18080

# /api/health 返回 {"ok":true,...,"maxUpload":...,"maxHeap":...}，天然适合做存活探针
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${OFFICIA_DEMO_PORT}/api/health" || exit 1

# shell 形式以便展开 ${JAVA_OPTS}；exec 保证 java 是 PID 1，docker stop 能收到 SIGTERM
# 走优雅停机（DemoServer 注册了 shutdown hook）。
# --no-open 必须带：容器里没有桌面环境，省掉一次无谓的 xdg-open 尝试。
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/officia-demo.jar ${OFFICIA_DEMO_PORT} --no-open --host ${OFFICIA_DEMO_HOST}"]
