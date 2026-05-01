# Nexus High Concurrency Interface Groups

This grouping is based on current controller endpoints under `nexus-trigger/src/main/java/cn/nexus/trigger/http`.

## Tier 1 - Core high-concurrency (first load-test wave)

- Read hot path
  - `GET /api/v1/feed/timeline`
  - `GET /api/v1/search`
  - `GET /api/v1/comment/list`
  - `GET /api/v1/comment/hot`
- Write hot path
  - `POST /api/v1/interact/reaction`
  - `POST /api/v1/relation/follow`
  - `POST /api/v1/relation/unfollow`
  - `POST /api/v1/interact/comment`
- Mixed read/write path
  - `POST /api/v1/relation/state/batch`
  - `GET /api/v1/notification/list`
  - `POST /api/v1/notification/read`
  - `POST /api/v1/notification/read/all`

## Tier 2 - Important but usually secondary in peak QPS

- `GET /api/v1/feed/profile/{targetId}`
- `GET /api/v1/user/profile/page`
- `GET /api/v1/user/profile`
- `GET /api/v1/relation/followers`
- `GET /api/v1/relation/following`

## Tier 3 - Heavy transactional or background-coupled

- `POST /api/v1/content/publish`
- `PUT /api/v1/content/draft`
- `POST /api/v1/content/schedule`
- `POST /api/v1/media/upload/session`
- `POST /api/v1/risk/decision`

## Why Tier 1 is the default benchmark set

- It represents the most frequent user traffic on social products.
- It covers both cache-heavy reads and write paths with fan-out effects.
- It is sensitive to Redis/MySQL bottlenecks and lock/contention behavior.
- It gives a stable baseline before moving to publish/risk heavy workflows.
