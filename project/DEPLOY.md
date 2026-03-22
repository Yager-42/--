# 部署说明（Docker Compose）

这份文档教你用 Docker 一键把项目跑起来（前端 + 后端 + MySQL/Redis/RabbitMQ/Kafka/Cassandra/Elasticsearch/MinIO）。

## 0. 你会得到什么

- 前端地址：`http://localhost:3000`
- 后端地址：`http://localhost:8080`
- 后端健康检查：`http://localhost:8080/api/v1/health`
- RabbitMQ 管理台：`http://localhost:15672`（账号/密码：`guest/guest`）
- MinIO 控制台：`http://localhost:9001`（账号/密码：`minioadmin/minioadmin`）
- Elasticsearch：`http://localhost:9200`

## 1. 前置条件（必须）

1) 电脑装好 Docker（Windows 一般是 Docker Desktop）。

2) Docker 能在命令行用：

```bash
docker --version
docker compose version
```

如果 `docker` 提示“不存在”，先把 Docker 装好并启动。

## 2. 一键启动（推荐）

在项目目录里运行：

```bash
cd project
docker compose up -d --build
```

第一次启动会比较久，因为要拉镜像、编译后端、构建前端。

## 3. 怎么确认“启动成功”（按顺序检查）

1) 看容器是否都在跑：

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

## 4. 数据初始化说明（你一般不需要手动做）

这套编排会自动做三件初始化：

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

### 6.3 前端能开，但接口报错

这套部署里，前端通过 Nginx 把 `/api/*`、`/file/*`、`/id/*`、`/kv/*` 代理到后端容器。

如果你看到接口还是去 `localhost:8080` 或者跨域报错：

- 先确认你访问的是 `http://localhost:3000`（不是直接打开 `dist` 文件）
- 再看前端容器日志：`docker compose logs -f frontend`

## 7. 生产环境提醒（必须知道）

这份 Compose 是“本地/演示环境”配置，默认密码都是弱口令（例如 `root/root`、`guest/guest`）。
如果要上公网或给多人用，必须改密码、收紧端口暴露、加鉴权与备份策略。

