import { assertEquals, assertThrows } from "jsr:@std/assert@1";

import {
  aggregateUsage,
  capLimit,
  collapseConsecutiveUiText,
  haversineMeters,
  parseBearer,
  parseRangeBound,
  PLACE_MERGE_METERS,
  resolveTypes,
  safeRelativePath,
  simplifyPlaces,
  zonedDayRange,
} from "./pure.ts";

const token = `olk_${"a".repeat(40)}`;

Deno.test("parseBearer accepts an olk_ token and rejects anything else", () => {
  assertEquals(parseBearer(`Bearer ${token}`), token);
  assertEquals(parseBearer(`bearer   ${token}`), token);
  assertEquals(parseBearer(null), null);
  assertEquals(parseBearer(""), null);
  assertEquals(parseBearer("Basic abc"), null);
  assertEquals(parseBearer(`Bearer ${token} extra`), null);
  assertEquals(parseBearer(`Bearer olk_${"a".repeat(39)}`), null);
  assertEquals(parseBearer(`Bearer OLK_${"a".repeat(40)}`), null);
  assertEquals(parseBearer(`Bearer olk_${"a".repeat(40)}!`), null);
});

Deno.test("Asia/Singapore local day is UTC+8", () => {
  const range = zonedDayRange("2026-10-22", "Asia/Singapore");
  assertEquals(range.from, "2026-10-21T16:00:00.000Z");
  assertEquals(range.to, "2026-10-22T16:00:00.000Z");
  assertEquals(parseRangeBound("2026-10-22", "Asia/Singapore", "from"), range.from);
  assertEquals(parseRangeBound("2026-10-22", "Asia/Singapore", "to"), range.to);
  assertEquals(parseRangeBound("2026-10-22T00:00:00+08:00", "Asia/Singapore", "from"), range.from);
});

Deno.test("fixed offsets and unknown zones", () => {
  assertEquals(zonedDayRange("2026-10-22", "UTC+8").from, "2026-10-21T16:00:00.000Z");
  assertThrows(() => zonedDayRange("2026-10-22", "Not/AZone"));
});

Deno.test("simplifyPlaces drops points within 50m of the last kept point", () => {
  const start = { lat: 1.3521, lng: 103.8198, at: "2026-10-22T00:00:00.000Z" };
  const near = moveNorth(start, 10);
  const almost = moveNorth(start, 40);
  const far = moveNorth(start, 80);
  assertEquals(haversineMeters(start.lat, start.lng, almost.lat, almost.lng) < PLACE_MERGE_METERS, true);
  assertEquals(haversineMeters(start.lat, start.lng, far.lat, far.lng) < PLACE_MERGE_METERS, false);
  const clusters = simplifyPlaces([
    { ...far, at: "2026-10-22T00:03:00.000Z", accuracy_m: 8 },
    { ...almost, at: "2026-10-22T00:02:00.000Z" },
    { ...near, at: "2026-10-22T00:01:00.000Z" },
    start,
  ]);
  assertEquals(clusters.length, 2);
  assertEquals(clusters[0].points, 3);
  assertEquals(clusters[0].first_seen, start.at);
  assertEquals(clusters[0].last_seen, "2026-10-22T00:02:00.000Z");
  assertEquals(clusters[0].lat, start.lat);
  assertEquals(clusters[0].accuracy_m, null);
  assertEquals(clusters[1].points, 1);
  assertEquals(clusters[1].first_seen, "2026-10-22T00:03:00.000Z");
  assertEquals(clusters[1].accuracy_m, 8);
});

Deno.test("collapseConsecutiveUiText merges only consecutive duplicates", () => {
  const rows = collapseConsecutiveUiText([
    { id: 1, type: "ui_text", package_name: "com.tencent.mm", content: "你好  世界" },
    { id: 2, type: "ui_text", package_name: "com.tencent.mm", content: "你好 世界" },
    { id: 3, type: "ui_text", package_name: "com.tencent.mm", content: "下一屏" },
    { id: 4, type: "tap", package_name: "com.tencent.mm", content: "下一屏" },
    { id: 5, type: "ui_text", package_name: "com.tencent.mm", content: "下一屏" },
    { id: 6, type: "ui_text", package_name: "com.xingin.xhs", content: "下一屏" },
  ]);
  assertEquals(rows.map((row) => [row.id, row.collapsed_count]), [
    [1, 2],
    [3, 1],
    [4, 1],
    [5, 1],
    [6, 1],
  ]);
});

Deno.test("aggregateUsage sums duration_ms and pairs fg/bg when it is missing", () => {
  const apps = aggregateUsage([
    {
      type: "app_fg",
      package_name: "com.tencent.mm",
      app_label: "微信",
      occurred_at: "2026-10-22T01:00:00.000Z",
      extras: { session_id: "s1" },
    },
    {
      type: "app_bg",
      package_name: "com.tencent.mm",
      occurred_at: "2026-10-22T01:00:05.000Z",
      extras: { session_id: "s1", duration_ms: "5000" },
    },
    {
      type: "app_fg",
      package_name: "com.tencent.mm",
      occurred_at: "2026-10-22T02:00:00.000Z",
      extras: { session_id: "s2" },
    },
    {
      type: "app_bg",
      package_name: "com.tencent.mm",
      occurred_at: "2026-10-22T02:00:04.000Z",
      extras: { session_id: "s2" },
    },
    {
      type: "app_fg",
      package_name: "com.tencent.mm",
      occurred_at: "2026-10-22T03:00:00.000Z",
      extras: { session_id: "open" },
    },
    {
      type: "app_bg",
      package_name: "com.tencent.mm",
      occurred_at: "2026-10-22T03:00:09.000Z",
      extras: { session_id: "other" },
    },
    {
      type: "app_bg",
      package_name: "com.ss.android.ugc.aweme",
      app_label: "抖音",
      occurred_at: "2026-10-22T04:00:00.000Z",
      extras: { duration_ms: 7000 },
    },
  ]);
  assertEquals(apps, [
    {
      package_name: "com.tencent.mm",
      app_label: "微信",
      duration_ms: 9000,
      sessions: 3,
    },
    {
      package_name: "com.ss.android.ugc.aweme",
      app_label: "抖音",
      duration_ms: 7000,
      sessions: 1,
    },
  ]);
});

Deno.test("scopes and path guards", () => {
  assertEquals(resolveTypes(["*"], ["note"]).types, ["note"]);
  assertEquals(resolveTypes(["note"], ["ui_text"]).error?.includes("ui_text"), true);
  assertEquals(resolveTypes(["note", "share"]).types, ["note", "share"]);
  assertEquals(capLimit("80", 20, 50), 50);
  assertEquals(safeRelativePath("uid/2026/10/a.jpg"), "uid/2026/10/a.jpg");
  assertEquals(safeRelativePath("../a.jpg"), null);
  assertEquals(safeRelativePath("/etc/passwd"), "etc/passwd");
  assertEquals(safeRelativePath("a/../../b"), null);
});

function moveNorth(point: { lat: number; lng: number }, meters: number) {
  const dLat = (meters / 6_371_000) * (180 / Math.PI);
  return { lat: point.lat + dLat, lng: point.lng };
}
