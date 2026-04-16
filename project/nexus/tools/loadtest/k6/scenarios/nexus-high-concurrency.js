import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const TOKEN = __ENV.TOKEN || "";
const POST_ID = Number(__ENV.POST_ID || "1");
const TARGET_USER_ID = Number(__ENV.TARGET_USER_ID || "2");

const bizSuccess = new Rate("biz_success_rate");
const bizFailure = new Rate("biz_failure_rate");
const bizSuccessCount = new Counter("biz_success_count");
const bizFailureCount = new Counter("biz_failure_count");
const feedTotalCount = new Counter("endpoint_feed_total_count");
const feedSuccessCount = new Counter("endpoint_feed_success_count");
const reactionTotalCount = new Counter("endpoint_reaction_total_count");
const reactionSuccessCount = new Counter("endpoint_reaction_success_count");
const followTotalCount = new Counter("endpoint_follow_total_count");
const followSuccessCount = new Counter("endpoint_follow_success_count");
const unfollowTotalCount = new Counter("endpoint_unfollow_total_count");
const unfollowSuccessCount = new Counter("endpoint_unfollow_success_count");
const searchTotalCount = new Counter("endpoint_search_total_count");
const searchSuccessCount = new Counter("endpoint_search_success_count");

function authHeaders() {
  const headers = { "Content-Type": "application/json" };
  if (TOKEN && TOKEN.trim().length > 0) {
    headers["Authorization"] = TOKEN;
  }
  return { headers };
}

function markResult(res, name) {
  const isHttpOk = res.status === 200;
  const code = isHttpOk ? res.json("code") : "";
  const isBizOk = isHttpOk && code === "0000";
  const ok = check(res, {
    [`${name} http 200`]: () => isHttpOk,
    [`${name} code 0000`]: () => isBizOk,
  });
  if (isBizOk) {
    bizSuccess.add(1);
    bizSuccessCount.add(1);
  } else {
    bizFailure.add(1);
    bizFailureCount.add(1);
  }
  return ok;
}

export const options = {
  scenarios: {
    feed_read: {
      executor: "ramping-arrival-rate",
      exec: "feedRead",
      startRate: Number(__ENV.FEED_START_RATE || 30),
      timeUnit: "1s",
      preAllocatedVUs: Number(__ENV.FEED_PRE_VUS || 50),
      maxVUs: Number(__ENV.FEED_MAX_VUS || 500),
      stages: [
        { target: Number(__ENV.FEED_RATE_1 || 80), duration: __ENV.FEED_DUR_1 || "1m" },
        { target: Number(__ENV.FEED_RATE_2 || 150), duration: __ENV.FEED_DUR_2 || "2m" },
        { target: 0, duration: __ENV.FEED_DUR_3 || "30s" },
      ],
    },
    reaction_write: {
      executor: "ramping-arrival-rate",
      exec: "reactionWrite",
      startRate: Number(__ENV.REACTION_START_RATE || 20),
      timeUnit: "1s",
      preAllocatedVUs: Number(__ENV.REACTION_PRE_VUS || 40),
      maxVUs: Number(__ENV.REACTION_MAX_VUS || 400),
      stages: [
        { target: Number(__ENV.REACTION_RATE_1 || 60), duration: __ENV.REACTION_DUR_1 || "1m" },
        { target: Number(__ENV.REACTION_RATE_2 || 120), duration: __ENV.REACTION_DUR_2 || "2m" },
        { target: 0, duration: __ENV.REACTION_DUR_3 || "30s" },
      ],
    },
    relation_write: {
      executor: "ramping-arrival-rate",
      exec: "relationWrite",
      startRate: Number(__ENV.REL_START_RATE || 10),
      timeUnit: "1s",
      preAllocatedVUs: Number(__ENV.REL_PRE_VUS || 30),
      maxVUs: Number(__ENV.REL_MAX_VUS || 300),
      stages: [
        { target: Number(__ENV.REL_RATE_1 || 40), duration: __ENV.REL_DUR_1 || "1m" },
        { target: Number(__ENV.REL_RATE_2 || 80), duration: __ENV.REL_DUR_2 || "2m" },
        { target: 0, duration: __ENV.REL_DUR_3 || "30s" },
      ],
    },
    search_read: {
      executor: "ramping-arrival-rate",
      exec: "searchRead",
      startRate: Number(__ENV.SEARCH_START_RATE || 20),
      timeUnit: "1s",
      preAllocatedVUs: Number(__ENV.SEARCH_PRE_VUS || 30),
      maxVUs: Number(__ENV.SEARCH_MAX_VUS || 300),
      stages: [
        { target: Number(__ENV.SEARCH_RATE_1 || 60), duration: __ENV.SEARCH_DUR_1 || "1m" },
        { target: Number(__ENV.SEARCH_RATE_2 || 120), duration: __ENV.SEARCH_DUR_2 || "2m" },
        { target: 0, duration: __ENV.SEARCH_DUR_3 || "30s" },
      ],
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.02"],
    http_req_duration: ["p(95)<500", "p(99)<1200"],
    biz_success_rate: ["rate>0.98"],
    checks: ["rate>0.98"],
  },
};

export function feedRead() {
  const res = http.get(
    `${BASE_URL}/api/v1/feed/timeline?limit=20`,
    authHeaders()
  );
  feedTotalCount.add(1);
  if (markResult(res, "feed_timeline")) {
    feedSuccessCount.add(1);
  }
  sleep(0.03);
}

export function reactionWrite() {
  const body = JSON.stringify({
    requestId: `k6-r-${__VU}-${__ITER}`,
    targetId: POST_ID,
    targetType: "POST",
    type: "LIKE",
    action: __ITER % 2 === 0 ? "ADD" : "REMOVE",
  });
  const res = http.post(
    `${BASE_URL}/api/v1/interact/reaction`,
    body,
    authHeaders()
  );
  reactionTotalCount.add(1);
  if (markResult(res, "interact_reaction")) {
    reactionSuccessCount.add(1);
  }
  sleep(0.03);
}

export function relationWrite() {
  const endpoint = __ITER % 2 === 0 ? "follow" : "unfollow";
  const body = JSON.stringify({
    targetId: TARGET_USER_ID,
  });
  const res = http.post(
    `${BASE_URL}/api/v1/relation/${endpoint}`,
    body,
    authHeaders()
  );
  if (endpoint === "follow") {
    followTotalCount.add(1);
    if (markResult(res, "relation_follow")) {
      followSuccessCount.add(1);
    }
  } else {
    unfollowTotalCount.add(1);
    if (markResult(res, "relation_unfollow")) {
      unfollowSuccessCount.add(1);
    }
  }
  sleep(0.03);
}

export function searchRead() {
  const res = http.get(
    `${BASE_URL}/api/v1/search?q=test&size=10`,
    authHeaders()
  );
  searchTotalCount.add(1);
  if (markResult(res, "search")) {
    searchSuccessCount.add(1);
  }
  sleep(0.03);
}
