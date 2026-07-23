import assert from "node:assert/strict";
import test from "node:test";

import {
  normalizeRelease,
  normalizeUpdateChannel,
  selectLatestRelease,
} from "./worker.js";

test("update channels default to public", () => {
  assert.equal(normalizeUpdateChannel(undefined), "public");
  assert.equal(normalizeUpdateChannel(" VLM-Core "), "vlm-core");
  assert.equal(normalizeUpdateChannel("vlm/core"), "public");
});

test("release normalization preserves isolated channels", () => {
  const release = normalizeRelease({
    tag: "v0.5.6.2",
    track: "beta",
    channel: "vlm-core",
    assets: [],
  }, {});

  assert.equal(release.channel, "vlm-core");
});

test("release selection cannot cross update channels", () => {
  const releases = [
    { tag: "v0.5.6.2", version: "0.5.6.2", track: "beta", channel: "public", publishedAt: 1 },
    { tag: "v0.5.6.3", version: "0.5.6.3", track: "beta", channel: "vlm-core", publishedAt: 2 },
    { tag: "v0.5.7", version: "0.5.7", track: "stable", publishedAt: 3 },
  ];

  assert.equal(selectLatestRelease(releases, true, "vlm-core")?.tag, "v0.5.6.3");
  assert.equal(selectLatestRelease(releases, false, "vlm-core"), null);
  assert.equal(selectLatestRelease(releases, true, "public")?.tag, "v0.5.7");
});
