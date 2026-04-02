# WSL Canal Middleware Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Canal into the existing WSL Docker middleware stack so local middleware can publish MySQL Post CDC raw events to RabbitMQ for the Nexus CDC pipeline.

**Architecture:** Extend `project/docker-compose.middleware.yml` instead of creating a separate compose file. Enable MySQL row-format binlog, add a RabbitMQ topology initializer for the raw CDC exchange/queue, add a `canal-server` service with checked-in config, and make the local startup script wait until Canal is running.

**Tech Stack:** Docker Compose, WSL, MySQL 8, RabbitMQ, Canal Server

---

## Chunk 1: Middleware Compose Wiring

### Task 1: Enable MySQL binlog for Canal

**Files:**
- Modify: `project/docker-compose.middleware.yml`

- [ ] Add MySQL startup flags for `server-id`, `log-bin`, `binlog-format=ROW`, and `binlog-row-image=FULL`
- [ ] Keep existing charset/collation behavior unchanged
- [ ] Validate `docker compose -f project/docker-compose.middleware.yml config`

### Task 2: Add Rabbit raw CDC topology initialization

**Files:**
- Modify: `project/docker-compose.middleware.yml`

- [ ] Add a one-shot `rabbitmq-init` service after `rabbitmq`
- [ ] Declare `search.cdc.raw.exchange`, `search.cdc.raw.queue`, and bindings for the raw routing keys
- [ ] Validate compose rendering again

## Chunk 2: Canal Service

### Task 3: Add checked-in Canal configuration

**Files:**
- Create: `project/docker/canal/conf/canal.properties`
- Create: `project/docker/canal/conf/search_cdc_raw/instance.properties`

- [ ] Configure Canal in RabbitMQ mode
- [ ] Point Canal instance to `mysql:3306`
- [ ] Limit subscription to `nexus_social.content_post` and `nexus_social.content_post_type`

### Task 4: Add `canal-server` to middleware compose

**Files:**
- Modify: `project/docker-compose.middleware.yml`

- [ ] Add `canal-server` service using the checked-in config
- [ ] Depend on healthy MySQL and successful Rabbit topology init
- [ ] Keep it internal-only (no host port binding)

## Chunk 3: Startup Flow Verification

### Task 5: Wait for Canal in the local startup script

**Files:**
- Modify: `project/scripts/start-local-all.ps1`

- [ ] Add a helper that waits until a named compose service is running inside WSL
- [ ] Use it for `canal-server` after middleware port checks
- [ ] Keep existing backend/frontend startup behavior unchanged

### Task 6: Validate static wiring

**Files:**
- Modify: `project/docker-compose.middleware.yml`
- Modify: `project/scripts/start-local-all.ps1`
- Create: `project/docker/canal/conf/canal.properties`
- Create: `project/docker/canal/conf/search_cdc_raw/instance.properties`

- [ ] Run `docker compose -f project/docker-compose.middleware.yml config`
- [ ] Run PowerShell parse check on `project/scripts/start-local-all.ps1`
- [ ] Inspect resulting diff for only Canal-related infrastructure changes
