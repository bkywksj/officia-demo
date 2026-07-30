---
name: testbench
description: |
  /testbench - 启动可视化测试台并指向对应面板

  触发场景：
  - 想实测某能力的真实效果
  - 要把 demo 跑起来看浏览器界面
  - 要做一次全能力环境自检

  触发词：/testbench、测试台、跑demo、启动、可视化、面板、实测、批量回归
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---
# /testbench - 启动可视化测试台

把测试台跑起来，并告诉用户哪个面板能测他关心的能力。

## 执行步骤

1. **确认本地仓有 officia**：

```bash
ls ~/.m2/repository/plus/ruoyi/officia-all/
```

   没有 → 提示先在 `../officia` 执行 `mvn install -DskipTests`（见 `officia-setup`）。

2. **构建并启动**（在 officia-demo 目录）：

```bash
mvn -o package
java -jar target/officia-demo-1.0.0.jar
```

   - 默认自动开浏览器；`--no-open` 可关闭
   - 可传端口：`java -jar target/officia-demo-1.0.0.jar 9090`
   - 绑定 `127.0.0.1`（仅本机）；端口被占会自动往后找，**以控制台打印的地址为准**
   - 启动日志会显示**是否探测到中文字体**

3. **按用户关心的能力指向面板**：

   | 用户想测 | 面板 |
   |---|---|
   | Word 转 PDF | Words · DOC/DOCX |
   | 模板填充 / 邮件合并 | 模板填充 · 邮件合并 |
   | Excel / CSV / 公式 | Cells · XLSX/CSV |
   | PPT 转 PDF | Slides · PPTX |
   | PDF 各种操作 | PDF 工具箱 |
   | 图片处理 | Imaging · 图像 |
   | 条码二维码 | BarCode · 条码 |
   | 邮件 | Email · EML |
   | 水印/授权效果 | 授权门控与对比 |
   | 整体环境自检 | 批量回归 |

4. **激活 `officia-testbench` 技能**获取端点清单与排查表。

5. 若用户目的是"验证环境正常"，直接引导跑批量回归：

```bash
curl -X POST http://127.0.0.1:8080/api/batch/run
```

## 提示

- 上传文件**只存内存、重启即清、不落盘**
- demo **不携带任何 `.lic`**；要看授权效果需用户自备
- 中文水印可在界面上传自己的 `.ttf`
