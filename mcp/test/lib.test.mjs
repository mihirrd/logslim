import { test } from "node:test";
import assert from "node:assert/strict";
import { buildUrl, cap } from "../dist/lib.js";

test("buildUrl: appends query params, skips undefined/null/empty", () => {
  const url = buildUrl("http://h:8080", "/api/templates", {
    search: "boom",
    last: undefined,
    limit: 20,
    blank: "",
    nope: null,
  });
  const u = new URL(url);
  assert.equal(u.pathname, "/api/templates");
  assert.equal(u.searchParams.get("search"), "boom");
  assert.equal(u.searchParams.get("limit"), "20");
  assert.equal(u.searchParams.has("last"), false);
  assert.equal(u.searchParams.has("blank"), false);
  assert.equal(u.searchParams.has("nope"), false);
});

test("buildUrl: normalizes a trailing slash on the base", () => {
  assert.equal(buildUrl("http://h:8080/", "/api/stats"), "http://h:8080/api/stats");
  assert.equal(buildUrl("http://h:8080///", "/api/stats"), "http://h:8080/api/stats");
});

test("buildUrl: coerces boolean/number values", () => {
  const u = new URL(buildUrl("http://h", "/x", { baseline: true, n: 0 }));
  assert.equal(u.searchParams.get("baseline"), "true");
  // 0 is a meaningful value, not empty — it should be kept
  assert.equal(u.searchParams.get("n"), "0");
});

test("cap: wraps an array longer than max with explicit truncation metadata", () => {
  const data = Array.from({ length: 250 }, (_, i) => i);
  const out = cap(data, 200, "narrow it");
  assert.equal(out.truncated, true);
  assert.equal(out.returned, 200);
  assert.equal(out.totalAvailable, 250);
  assert.equal(out.hint, "narrow it");
  assert.equal(out.items.length, 200);
  assert.deepEqual(out.items[199], 199);
});

test("cap: passes through arrays within the limit unchanged", () => {
  const data = [1, 2, 3];
  assert.equal(cap(data, 200, "h"), data); // same reference, no wrapper
});

test("cap: passes through an array exactly at the limit", () => {
  const data = Array.from({ length: 200 }, (_, i) => i);
  assert.equal(cap(data, 200, "h"), data);
});

test("cap: passes through non-array values (objects)", () => {
  const obj = { templateId: 1, occurrences: [] };
  assert.equal(cap(obj, 200, "h"), obj);
});
