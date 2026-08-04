# deploy/ —— 测试台容器编排

三条命令跑起来（在项目根目录）：

```bash
mvn -o package -DskipTests          # 1) 宿主构建 jar（容器内跑 mvn 会因本地仓依赖失败）
cd deploy && cp .env.example .env   # 2) 按需改端口 / 内存
docker compose up -d --build        # 3) 起容器
```

验收三连：

```bash
docker compose logs officia-demo | head -30                 # 看启动横幅：字体 / 授权 / 内存上限
curl -s http://127.0.0.1:18080/api/health                    # {"ok":true,...}
curl -s -X POST http://127.0.0.1:18080/api/batch/run         # 全能力回归，期望 pass=10 fail=0
```

| 文件 | 作用 |
|---|---|
| `Dockerfile` | 单阶段：JRE + 中文字体 + curl + 宿主构建好的 shade jar |
| `docker-compose.yml` | 单服务、无数据库、无数据卷；端口只发布到宿主 `127.0.0.1` |
| `.env.example` | 端口 / 内存 / JVM 参数 / 字体目录 / 时区 |

## 三件必须知道的事

1. **端口只发布到宿主回环**。测试台**没有任何鉴权**，改成 `"18080:18080"` 就是把它开放给整个网段。
   远程访问走 SSH 隧道：`ssh -L 18080:127.0.0.1:18080 user@host`。
2. **默认输出带水印**是预期行为（测试台默认打开强制门控，与正式发布版一致）。放入 `.lic`
   并放开 compose 里的 `OFFICIA_LICENSE` 即可；`.lic` 绝不打进镜像、绝不入库。
3. **内存上限决定能传多大文件**：容器内存 → JVM 堆 → 上传上限（堆 ÷ 8）。改 `.env` 的 `MEM_LIMIT`。

完整说明（部署前置流程、远程部署、排查表）见技能 `officia-deploy`：
`.claude/skills/officia-deploy/SKILL.md`。
