---
name: officia-spring-integration
description: |
  把 Officia 集成进 Spring Boot / Web 应用：文件上传转换、PDF 下载（含流式直写响应）、
  授权在应用启动时加载一次、字体目录配置化、并发与超时保护、异常统一处理。

  触发场景：
  - 做一个"上传 Word 返回 PDF"的接口
  - Spring Boot 里 officia 该怎么组织（Bean / 配置 / 启动加载授权）
  - MultipartFile 怎么给 officia
  - 大文件下载怎么不撑爆内存
  - OfficiaException 怎么统一处理成接口错误

  触发词：Spring、SpringBoot、Web、接口、Controller、上传、下载、MultipartFile、集成、微服务、RestController、异常处理、配置
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# 集成进 Spring Boot / Web 应用

## 概述

Officia 是**纯静态门面 + `byte[]` 进出**的库，没有需要托管的 Bean、没有初始化生命周期。集成的全部工作就三件事：

1. **启动时加载一次授权**（不是每请求）
2. **把字体目录配置化**（中文必需）
3. **把 `byte[]` 进出接到 Web 的上传/下载上**（大文件走流式）

## 一、依赖

```xml
<dependency>
  <groupId>plus.ruoyi</groupId>
  <artifactId>officia-all</artifactId>
  <version>1.0.0</version>
</dependency>
```

`officia-all` 已含 `officia-license`，不必再引。详见 `officia-setup`。

## 二、配置与启动加载授权

```yaml
# application.yml
officia:
  license: ${OFFICIA_LICENSE:}          # 留空则走 officia 自动查找（classpath / 工作目录 / 环境变量）
  font-dir: /usr/share/fonts            # 中文必需，见 officia-chinese-font
  timeout-ms: 30000
  max-upload-bytes: 52428800            # 50MB

spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB
```

```java
@Configuration
@ConfigurationProperties(prefix = "officia")
public class OfficiaProperties {
    private String license;
    private String fontDir;
    private long timeoutMs = 30_000;
    private long maxUploadBytes = 50L * 1024 * 1024;
    // getter / setter 省略
}
```

```java
import plus.ruoyi.officia.license.OfficiaLicense;

/**
 * 授权在应用启动时加载一次即可——OfficiaLicense 是进程全局状态，
 * 每请求 setLicense 既无必要也不正确。
 */
@Component
public class OfficiaLicenseInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OfficiaLicenseInitializer.class);
    private final OfficiaProperties props;

    public OfficiaLicenseInitializer(OfficiaProperties props) { this.props = props; }

    @Override
    public void run(ApplicationArguments args) {
        // 显式路径优先；留空则依赖 officia 自动查找（-D → 环境变量 → classpath → 工作目录）
        if (props.getLicense() != null && !props.getLicense().isBlank()) {
            OfficiaLicense.setLicense(new File(props.getLicense()));
        }
        if (OfficiaLicense.isLicensed()) {
            log.info("Officia 已授权：{} / {} / 模块={}",
                OfficiaLicense.getLicensee(), OfficiaLicense.getEdition(), OfficiaLicense.getModules());
        } else {
            log.warn("Officia 评估态运行——输出将带水印、限页（enforced={}）", OfficiaLicense.isEnforced());
        }
    }
}
```

> 🔴 **不要**把 `.lic` 打进 jar 或镜像层；用环境变量 / 挂载注入。见 `officia-license`。

## 三、转换服务

```java
import plus.ruoyi.officia.engine.api.ConvertOptions;
import plus.ruoyi.officia.words.OfficiaWords;

@Service
public class DocumentService {

    private final OfficiaProperties props;

    public DocumentService(OfficiaProperties props) { this.props = props; }

    /** 每次调用新建 ConvertOptions —— 它是可变对象，不能跨线程共享。 */
    private ConvertOptions options() {
        ConvertOptions opts = ConvertOptions.defaults()
            .setTimeoutMillis(props.getTimeoutMs());
        if (props.getFontDir() != null && !props.getFontDir().isBlank()) {
            opts.setFontDirectory(props.getFontDir());
        }
        return opts;
    }

    public byte[] wordToPdf(byte[] docx) {
        return OfficiaWords.toPdf(docx, options());
    }

    /** 大文件：直接写进响应流，不在内存里保留整份 PDF。返回页数。 */
    public int wordToPdf(byte[] docx, OutputStream out) {
        return OfficiaWords.toPdf(docx, options(), out);
    }
}
```

## 四、Controller：上传 → 转换 → 下载

```java
@RestController
@RequestMapping("/api/doc")
public class DocumentController {

    private final DocumentService service;
    private final OfficiaProperties props;

    public DocumentController(DocumentService service, OfficiaProperties props) {
        this.service = service;
        this.props = props;
    }

    /** 小文件：返回字节体 */
    @PostMapping("/word2pdf")
    public ResponseEntity<byte[]> wordToPdf(@RequestParam("file") MultipartFile file) throws IOException {
        checkUpload(file);
        byte[] pdf = service.wordToPdf(file.getBytes());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename(baseName(file.getOriginalFilename()) + ".pdf", StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }

    /** 大文件：流式直写响应（推荐） */
    @PostMapping("/word2pdf/stream")
    public void wordToPdfStream(@RequestParam("file") MultipartFile file,
                                HttpServletResponse response) throws IOException {
        checkUpload(file);
        response.setContentType(MediaType.APPLICATION_PDF_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(baseName(file.getOriginalFilename()) + ".pdf", StandardCharsets.UTF_8)
                .build().toString());

        try (OutputStream os = response.getOutputStream()) {
            int pages = service.wordToPdf(file.getBytes(), os);
            response.setHeader("X-Pdf-Pages", String.valueOf(pages));
        }
    }

    private void checkUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        if (file.getSize() > props.getMaxUploadBytes()) {
            throw new IllegalArgumentException("文件过大，上限 " + props.getMaxUploadBytes() + " 字节");
        }
    }

    private String baseName(String name) {
        if (name == null || name.isBlank()) return "output";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
```

> ⚠️ **流式响应的注意点**：一旦开始往 `getOutputStream()` 写，就无法再改状态码/响应头。所以**校验要放在写之前**，转换失败时的错误响应要在写出前决定。

## 五、异常统一处理

```java
import plus.ruoyi.officia.common.exception.OfficiaException;

@RestControllerAdvice
public class OfficiaExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OfficiaExceptionHandler.class);

    @ExceptionHandler(OfficiaException.class)
    public ResponseEntity<Map<String, Object>> handle(OfficiaException e) {
        // 消息前缀就是故障域路标（MS-DOC / CFB / OPC / License …），见 officia-troubleshooting
        log.warn("Officia 处理失败: {}", e.getMessage(), e);
        return ResponseEntity.badRequest().body(Map.of(
            "code", "DOCUMENT_PROCESS_FAILED",
            "message", "文档处理失败，请确认文件格式正确且未加密",
            "detail", e.getMessage()          // 生产环境按需脱敏后再返回
        ));
    }
}
```

> `OfficiaException` 是 `RuntimeException`，不需要在方法签名上声明。

## 六、并发与限流

officia 未做系统压测（见 `officia-performance`），保护要在你这侧：

```java
@Configuration
public class ConvertPoolConfig {
    /** 有界线程池 + 有界队列：限制同时进行的转换数，避免内存叠爆 */
    @Bean("convertExecutor")
    public ExecutorService convertExecutor() {
        return new ThreadPoolExecutor(
            4, 4, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(50),
            new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
```

大任务建议**异步化**：接口收文件后返回任务号，后台转完再供下载。

## 七、健康检查里暴露授权状态

```java
@Component
public class OfficiaHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        return Health.up()
            .withDetail("licensed",   OfficiaLicense.isLicensed())
            .withDetail("evaluation", OfficiaLicense.isEvaluation())
            .withDetail("enforced",   OfficiaLicense.isEnforced())
            .withDetail("licensee",   OfficiaLicense.getLicensee())
            .build();
    }
}
```

> 上线后"为什么突然有水印"，看这个端点最快。

## 八、Docker 部署清单

```dockerfile
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y fonts-noto-cjk && rm -rf /var/lib/apt/lists/*
COPY target/app.jar /app/app.jar
ENV OFFICIA_LICENSE=/etc/officia/officia.lic
ENTRYPOINT ["java","-Xmx2g","-jar","/app/app.jar"]
```

```bash
docker run -v /host/officia.lic:/etc/officia/officia.lic:ro -p 8080:8080 myapp
```

上线自检三项：① 中文字体装了吗 ② `.lic` 挂载进来了吗 ③ 堆大小够吗。

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| 线上有水印，本地没有 | 本地用的是源码构建（门控关），线上是发布版（门控开）+ 未加载授权 | 见 `officia-license` |
| 中文方块 | 容器没装字体 / 没配 `font-dir` | 见 `officia-chinese-font` |
| 下载文件名中文乱码 | 未按 RFC 5987 编码 | 用 `ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8)` |
| 大文件 OOM | 整份 PDF 在内存 | 改流式接口 |
| 并发高时 OOM | 同时转换太多 | 有界线程池限并发 |
| 转换报错但响应是 200 空 body | 流式写出后才抛异常 | 校验前置；或先转成 `byte[]` 再决定响应 |
| 每请求都 setLicense | 授权是进程全局 | 移到 `ApplicationRunner` |

## 相关技能

| 接下来 | 用 |
|---|---|
| 具体某个能力怎么调 | `officia-capability-map` |
| 内存 / 并发细节 | `officia-performance` |
| 报错定位 | `officia-troubleshooting` |
| 授权注入 | `officia-license` |
