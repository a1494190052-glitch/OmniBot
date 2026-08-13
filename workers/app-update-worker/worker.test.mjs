import assert from "node:assert/strict";
import test from "node:test";

import worker from "./worker.js";

const CATALOG = JSON.stringify({
  openai: {
    id: "openai",
    name: "OpenAI",
    models: {
      "gpt-test": {
        id: "gpt-test",
        name: "GPT Test",
      },
    },
  },
});

class MemoryR2Object {
  constructor(key, value, options = {}) {
    this.key = key;
    this.value = value;
    this.size = new TextEncoder().encode(value).byteLength;
    this.etag = options.etag || `r2-${this.size}`;
    this.uploaded = new Date();
    this.httpMetadata = options.httpMetadata || {};
    this.customMetadata = options.customMetadata || {};
  }

  get body() {
    return new Response(this.value).body;
  }

  async text() {
    return this.value;
  }
}

class MemoryR2Bucket {
  constructor() {
    this.objects = new Map();
  }

  async head(key) {
    return this.objects.get(key) || null;
  }

  async get(key) {
    return this.objects.get(key) || null;
  }

  async put(key, value, options = {}) {
    const text = typeof value === "string"
      ? value
      : new TextDecoder().decode(value);
    const object = new MemoryR2Object(key, text, options);
    this.objects.set(key, object);
    return object;
  }

  async list({ prefix = "" } = {}) {
    const objects = [...this.objects.values()]
      .filter((object) => object.key.startsWith(prefix));
    return { objects, truncated: false };
  }
}

function testEnv(bucket) {
  return {
    APP_UPDATE_BUCKET: bucket,
    ADMIN_TOKEN: "test-token",
  };
}

async function seedMirror(bucket, {
  lowercaseMetadata = true,
  withMetadata = true,
} = {}) {
  const status = {
    schemaVersion: 1,
    publisher: "github-actions",
    lastCheckedAt: 1_700_000_000_000,
    lastSuccessfulAt: 1_700_000_000_000,
    changed: true,
    consecutiveFailures: 0,
    lastError: "",
    upstreamUrl: "https://models.dev/api.json",
    upstreamEtag: '"upstream-v1"',
    sha256: "catalog-sha256",
    providerCount: 1,
    modelCount: 1,
    size: new TextEncoder().encode(CATALOG).byteLength,
  };
  const metadata = lowercaseMetadata
    ? {
      sha256: status.sha256,
      upstreametag: status.upstreamEtag,
      fetchedat: String(status.lastSuccessfulAt),
      providercount: String(status.providerCount),
      modelcount: String(status.modelCount),
      size: String(status.size),
    }
    : {
      sha256: status.sha256,
      upstreamEtag: status.upstreamEtag,
      fetchedAt: String(status.lastSuccessfulAt),
      providerCount: String(status.providerCount),
      modelCount: String(status.modelCount),
      size: String(status.size),
    };

  await bucket.put("metadata/models-dev/current.json", CATALOG, {
    etag: "r2-catalog-etag",
    customMetadata: withMetadata ? metadata : {},
  });
  await bucket.put(
    "metadata/models-dev/status.json",
    JSON.stringify(status),
  );
  return status;
}

test("serves CI-published R2 catalog with SHA conditional GET", async () => {
  const bucket = new MemoryR2Bucket();
  const env = testEnv(bucket);
  const status = await seedMirror(bucket);

  const response = await worker.fetch(
    new Request("https://updates.example/catalog/models-dev/api.json"),
    env,
  );
  assert.equal(response.status, 200);
  assert.equal(await response.text(), CATALOG);
  assert.equal(response.headers.get("etag"), `"${status.sha256}"`);
  assert.equal(response.headers.get("x-models-dev-provider-count"), "1");
  assert.equal(response.headers.get("access-control-allow-origin"), "*");

  const notModified = await worker.fetch(
    new Request("https://updates.example/catalog/models-dev/api.json", {
      headers: { "if-none-match": `W/"${status.sha256}"` },
    }),
    env,
  );
  assert.equal(notModified.status, 304);
  assert.equal(await notModified.text(), "");
});

test("update checks default to a disabled policy and ignore legacy environment values", async () => {
  const bucket = new MemoryR2Bucket();
  const env = {
    ...testEnv(bucket),
    MIN_CLOUD_SERVICE_VERSION: "99.0",
    CLOUD_SERVICE_UPDATE_MESSAGE: "legacy environment value",
  };
  const response = await worker.fetch(
    new Request("https://updates.example/updates?currentVersion=0.5.6.1"),
    env,
  );

  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.deepEqual(payload.cloudServicePolicy, {
    schemaVersion: 1,
    enabled: false,
    minimumVersion: "",
    accessAllowed: true,
    message: "",
  });
});

test("admin console exposes cloud-service policy controls", async () => {
  const response = await worker.fetch(
    new Request("https://updates.example/admin"),
    testEnv(new MemoryR2Bucket()),
  );
  const html = await response.text();

  assert.equal(response.status, 200);
  assert.match(html, /id="nav-cloud-policy"/);
  assert.match(html, /id="cloud-minimum-version"/);
  assert.match(html, /id="cloud-policy-message"/);
  assert.match(html, /\/admin\/cloud-service-policy/);
  const script = html.split("<script>")[1]?.split("</script>")[0] || "";
  assert.doesNotThrow(() => new Function(script));
});

test("admin UI policy is authenticated, stored in R2, and applied to update checks", async () => {
  const bucket = new MemoryR2Bucket();
  const env = testEnv(bucket);

  const unauthorized = await worker.fetch(
    new Request("https://updates.example/admin/cloud-service-policy"),
    env,
  );
  assert.equal(unauthorized.status, 401);

  const savedResponse = await worker.fetch(
    new Request("https://updates.example/admin/cloud-service-policy", {
      method: "PUT",
      headers: {
        authorization: "Bearer test-token",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        minimumVersion: "v0.5.7",
        message: "Upgrade before using cloud services",
      }),
    }),
    env,
  );
  assert.equal(savedResponse.status, 200);
  const saved = await savedResponse.json();
  assert.equal(saved.policy.enabled, true);
  assert.equal(saved.policy.minimumVersion, "0.5.7");
  assert.equal(saved.policy.message, "Upgrade before using cloud services");

  const storedObject = await bucket.get("metadata/config/cloud-service-policy.json");
  assert.ok(storedObject);

  const readResponse = await worker.fetch(
    new Request("https://updates.example/admin/cloud-service-policy", {
      headers: { authorization: "Bearer test-token" },
    }),
    env,
  );
  const read = await readResponse.json();
  assert.equal(read.policy.minimumVersion, "0.5.7");

  const blockedResponse = await worker.fetch(
    new Request("https://updates.example/updates?currentVersion=0.5.6.15"),
    env,
  );
  const blocked = await blockedResponse.json();
  assert.equal(blocked.cloudServicePolicy.enabled, true);
  assert.equal(blocked.cloudServicePolicy.minimumVersion, "0.5.7");
  assert.equal(blocked.cloudServicePolicy.accessAllowed, false);
  assert.equal(
    blocked.cloudServicePolicy.message,
    "Upgrade before using cloud services",
  );

  const allowedResponse = await worker.fetch(
    new Request("https://updates.example/updates?currentVersion=0.5.7"),
    env,
  );
  const allowed = await allowedResponse.json();
  assert.equal(allowed.cloudServicePolicy.accessAllowed, true);
  assert.equal(allowed.cloudServicePolicy.message, "");
});

test("admin UI can disable the cloud-service floor and rejects invalid versions", async () => {
  const bucket = new MemoryR2Bucket();
  const env = testEnv(bucket);
  const headers = {
    authorization: "Bearer test-token",
    "content-type": "application/json",
  };

  const invalidResponse = await worker.fetch(
    new Request("https://updates.example/admin/cloud-service-policy", {
      method: "PUT",
      headers,
      body: JSON.stringify({ minimumVersion: "0.5.beta", message: "" }),
    }),
    env,
  );
  assert.equal(invalidResponse.status, 400);

  await worker.fetch(
    new Request("https://updates.example/admin/cloud-service-policy", {
      method: "PUT",
      headers,
      body: JSON.stringify({ minimumVersion: "0.5.7", message: "blocked" }),
    }),
    env,
  );
  const disabledResponse = await worker.fetch(
    new Request("https://updates.example/admin/cloud-service-policy", {
      method: "PUT",
      headers,
      body: JSON.stringify({ minimumVersion: "", message: "" }),
    }),
    env,
  );
  const disabled = await disabledResponse.json();
  assert.equal(disabled.policy.enabled, false);
  assert.equal(disabled.policy.minimumVersion, "");

  const updateResponse = await worker.fetch(
    new Request("https://updates.example/updates?currentVersion=0.5.6.15"),
    env,
  );
  const update = await updateResponse.json();
  assert.equal(update.cloudServicePolicy.enabled, false);
  assert.equal(update.cloudServicePolicy.accessAllowed, true);
});

test("falls back to the R2 object ETag when custom metadata is unavailable", async () => {
  const bucket = new MemoryR2Bucket();
  const env = testEnv(bucket);
  await seedMirror(bucket, { withMetadata: false });

  const response = await worker.fetch(
    new Request("https://updates.example/catalog/models-dev/api.json"),
    env,
  );
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("etag"), '"r2-catalog-etag"');
});

test("returns 503 before GitHub Actions publishes the first catalog", async () => {
  const response = await worker.fetch(
    new Request("https://updates.example/catalog/models-dev/api.json"),
    testEnv(new MemoryR2Bucket()),
  );
  assert.equal(response.status, 503);
});

test("admin status is authenticated and combines R2 metadata with CI status", async () => {
  const bucket = new MemoryR2Bucket();
  const env = testEnv(bucket);
  await seedMirror(bucket, { withMetadata: false });

  const unauthorized = await worker.fetch(
    new Request("https://updates.example/admin/models-dev"),
    env,
  );
  assert.equal(unauthorized.status, 401);

  const response = await worker.fetch(
    new Request("https://updates.example/admin/models-dev", {
      headers: { authorization: "Bearer test-token" },
    }),
    env,
  );
  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.equal(payload.upstreamUrl, "https://models.dev/api.json");
  assert.equal(payload.current.sha256, "catalog-sha256");
  assert.equal(payload.current.providerCount, 1);
  assert.equal(payload.current.modelCount, 1);
  assert.equal(payload.refresh.publisher, "github-actions");
});

test("Worker no longer exposes an in-process models.dev refresh route", async () => {
  const bucket = new MemoryR2Bucket();
  const response = await worker.fetch(
    new Request("https://updates.example/admin/models-dev/refresh", {
      method: "POST",
      headers: { authorization: "Bearer test-token" },
    }),
    testEnv(bucket),
  );
  assert.equal(response.status, 404);
});
