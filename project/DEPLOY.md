# 部署说明（Docker Compose）

这份文档现在支持两种方式：

- 全量容器：前端 + 后端 + MySQL/Redis/RabbitMQ/Kafka/Cassandra/Elasticsearch/MinIO 全部放进 Docker
- WSL 中间件模式：只把中间件放进 WSL 的 Docker，本地代码用 `wsl` profile 连这些容器

## 0. 你会得到什么

- 前端地址：`http://localhost:3000`
- 后端地址：`http://localhost:8080`
- 后端健康检查：`http://localhost:8080/api/v1/health`
- RabbitMQ 管理台：`http://localhost:15672`（账号/密码：`guest/guest`）
- MinIO 控制台：`http://localhost:9001`（账号/密码：`minioadmin/minioadmin`）
- Elasticsearch：`http://localhost:9200`
- Gorse API：`http://localhost:8087`
- Gorse 管理台：`http://localhost:8088`

## 1. 前置条件（必须）

1) 电脑装好 Docker。

- Windows + Docker Desktop：可以直接照旧用
- WSL 原生 Docker：也可以，只要你在 WSL 里能执行 `docker` 和 `docker compose`

2) Docker 能在命令行用：

```bash
docker --version
docker compose version
```

如果 `docker` 提示“不存在”，先把 Docker 装好并启动。

## 2. 启动方式

### 2.1 全量容器（原来的方式）

在项目目录里运行：

```bash
cd project
docker compose up -d --build
```

第一次启动会比较久，因为要拉镜像、编译后端、构建前端。

### 2.2 WSL 中间件模式（推荐给“代码本地跑，中间件在 Docker 里跑”）

先在 WSL 里启动中间件：

```bash
cd /mnt/c/Users/Administrator/Desktop/文档/project
bash scripts/up-wsl-middleware.sh
```

这条命令会启动：

- MySQL `3306`
- Redis `6379`
- RabbitMQ `5672` / 管理台 `15672`（已内置 delayed-message 插件）
- Zookeeper `2181`
- Cassandra `9042`
- Elasticsearch `9200`
- MinIO `9000` / 控制台 `9001`
- Gorse `8086 / 8087 / 8088 / 8089`

推荐使用脚本启动，避免遗漏中间件初始化容器和后续等待步骤。

然后本地启动后端代码时，使用 `wsl` profile：

PowerShell：

```powershell
cd C:\Users\Administrator\Desktop\文档\project\nexus
$env:SPRING_PROFILES_ACTIVE = "wsl"
mvn -pl nexus-app -am spring-boot:run
```

IDE 里运行也是同一个意思：把 active profile 改成 `wsl`。

如果你不想手敲命令，现在也可以直接用我做好的“一键脚本”：

PowerShell：

```powershell
cd C:\Users\Administrator\Desktop\文档\project
powershell -ExecutionPolicy Bypass -File scripts\start-local-all.ps1
```

双击版：

- 启动：`project/scripts/start-local-all.cmd`
- 停止：`project/scripts/stop-local-all.cmd`

停止整套本地环境：

```powershell
cd C:\Users\Administrator\Desktop\文档\project
powershell -ExecutionPolicy Bypass -File scripts\stop-local-all.ps1
```

`wsl` profile 做了三件事：

- 继续复用 `dev` 里的本地端口（`127.0.0.1` / `localhost`）
- 关闭 Nacos 配置中心（因为这套 Docker 没起 Nacos）
- 保留 DashScope 本地降级，并把 Gorse 改成直接连接 Docker 里的容器

如果你之前已经起过 RabbitMQ，或者第一次需要把 delayed-message 插件做进镜像，要重建 RabbitMQ：

```bash
cd /mnt/c/Users/Administrator/Desktop/文档/project
docker compose -f docker-compose.middleware.yml up -d --build --force-recreate rabbitmq
```

如果你确实要手工执行 Compose，可以运行：

```bash
cd /mnt/c/Users/Administrator/Desktop/文档/project
docker compose -f docker-compose.middleware.yml up -d --build
```

热 key 探测现在是 Nexus 进程内的本地能力，不再需要 dashboard、worker、etcd 或额外数据库初始化。

## 3. 怎么确认“启动成功”（按顺序检查）

### 3.1 全量容器

```bash
cd project
docker compose ps
```

2) 打开浏览器：

- `http://localhost:3000` 能打开页面
- `http://localhost:8080/api/v1/health` 能返回健康结果（不是 404/500）

3) 如果打不开，优先看日志（最常用）：

```bash
cd project
docker compose logs -f backend
```

前端日志：

```bash
cd project
docker compose logs -f frontend
```

### 3.2 WSL 中间件模式

1) 看中间件容器是否都在跑：

```bash
cd /mnt/c/Users/Administrator/Desktop/文档/project
docker compose -f docker-compose.middleware.yml ps
```

2) 快速检查几个关键端口：

- MySQL：`localhost:3306`
- Redis：`localhost:6379`
- RabbitMQ 管理台：`http://localhost:15672`
- Elasticsearch：`http://localhost:9200`
- MinIO 控制台：`http://localhost:9001`
- Gorse API：`http://localhost:8087`
- Gorse 管理台：`http://localhost:8088`

3) 再看本地后端健康检查：

- `http://localhost:8080/api/v1/health`

## 4. 数据初始化说明（你一般不需要手动做）

无论你用全量容器，还是 `docker-compose.middleware.yml`，都会自动做三件初始化：

1) MySQL：自动创建库 `nexus_social`，并执行建表脚本  
脚本来自：`project/nexus/docs/nexus_final_mysql_schema.sql`

2) Cassandra：启动后会执行 `project/nexus/docs/cassandra/schema.cql`  
会创建 keyspace：`nexus_kv`，并建两张 KV 表：`note_content`、`comment_content`

3) MinIO：会自动创建 bucket：`nexus`，并设置成 public（方便你本地验证上传）

## 5. 停止与清理

停止（不删数据）：

```bash
cd project
docker compose down
```

停止并删除所有数据（会清空 MySQL/Redis/Cassandra/ES/MinIO 的数据，慎用）：

```bash
cd project
docker compose down -v
```

如果你用的是 WSL 中间件模式，把命令里的 Compose 文件改成中间件版本：

```bash
cd /mnt/c/Users/Administrator/Desktop/文档/project
docker compose -f docker-compose.middleware.yml down
docker compose -f docker-compose.middleware.yml down -v
```

## 6. 常见问题（最短排查路径）

### 6.1 端口被占用

症状：启动时报错，提示 `port is already allocated` 或类似信息。

解决：打开 `project/docker-compose.yml`，把冲突端口的 `ports:` 左边数字改掉。
例子：把 `8080:8080` 改成 `18080:8080`，以后访问就用 `http://localhost:18080`。

### 6.2 后端一直起不来（最常见）

先看后端日志：

```bash
cd project
docker compose logs -f backend
```

常见原因：

- MySQL 还没准备好：等一会儿再看，或先看 `mysql` 日志
- 内存不够：Docker Desktop 给的内存太小（建议至少 6GB）

如果你走的是 “WSL 中间件 + 本地后端” 模式，还要确认后端启动时 profile 是 `wsl`，不是默认 `dev` 直接硬连 Nacos。

### 6.3 前端能开，但接口报错

这套部署里，前端通过 Nginx 把 `/api/*`、`/file/*`、`/id/*`、`/kv/*` 代理到后端容器。

如果你看到接口还是去 `localhost:8080` 或者跨域报错：

- 先确认你访问的是 `http://localhost:3000`（不是直接打开 `dist` 文件）
- 再看前端容器日志：`docker compose logs -f frontend`

## 7. 生产环境提醒（必须知道）

这份 Compose 是“本地/演示环境”配置，默认密码都是弱口令（例如 `root/root`、`guest/guest`）。
如果要上公网或给多人用，必须改密码、收紧端口暴露、加鉴权与备份策略。
