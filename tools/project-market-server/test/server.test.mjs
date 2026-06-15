import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { ProjectMarketStore, createProjectMarketServer } from '../server.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '../../..');
const validatorPath = path.join(
  repoRoot,
  'app/src/main/assets/builtin_skills/oob-project-sandbox/scripts/validate_component_package.py'
);

async function makePackage(root) {
  const packageRoot = path.join(root, 'package');
  await fsp.mkdir(packageRoot, { recursive: true });
  await fsp.writeFile(
    path.join(packageRoot, 'component.json'),
    JSON.stringify({ title: 'Daily Review' }, null, 2),
    'utf8'
  );
  await fsp.writeFile(path.join(packageRoot, 'README.md'), '# Daily Review\n', 'utf8');
  const manifest = {
    schemaVersion: 'oob.project.component.v1',
    componentId: 'daily-review-card',
    name: 'Daily Review Card',
    version: '0.1.0',
    type: 'widget',
    description: 'A configurable Xiaowan card for daily review prompts.',
    entry: {
      kind: 'config',
      path: 'component.json',
    },
    files: [
      {
        path: 'component.json',
        role: 'config',
        required: true,
      },
      {
        path: 'README.md',
        role: 'docs',
        required: false,
      },
    ],
    capabilities: ['local_config', 'prompt_template'],
    permissions: [],
    dataPolicy: {
      uploadsUserData: false,
      uploadsRuntimeData: false,
      storesDataLocally: false,
    },
  };
  const manifestPath = path.join(packageRoot, 'oob_project_component_manifest.v1.json');
  await fsp.writeFile(manifestPath, JSON.stringify(manifest, null, 2), 'utf8');
  return { packageRoot, manifestPath };
}

test('imports a valid project package and creates a market archive', async () => {
  const tempRoot = await fsp.mkdtemp(path.join(os.tmpdir(), 'oob-project-market-'));
  const store = new ProjectMarketStore({
    storeRoot: path.join(tempRoot, 'store'),
    validatorPath,
  });
  await store.ensureReady();
  const pkg = await makePackage(tempRoot);

  const result = await store.importProject(pkg);
  assert.equal(result.item.componentId, 'daily-review-card');
  assert.equal(result.item.dataPolicySummary.uploadsUserData, false);
  assert.equal(result.item.dataPolicySummary.uploadsRuntimeData, false);

  const market = await store.listProjects('http://127.0.0.1:17331');
  assert.equal(market.items.length, 1);
  assert.equal(
    market.items[0].downloadUrl,
    'http://127.0.0.1:17331/api/projects/daily-review-card/0.1.0/archive'
  );

  const archiveStat = await fsp.stat(result.archivePath);
  assert.ok(archiveStat.size > 0);
});

test('rejects packages that list user data paths', async () => {
  const tempRoot = await fsp.mkdtemp(path.join(os.tmpdir(), 'oob-project-market-'));
  const store = new ProjectMarketStore({
    storeRoot: path.join(tempRoot, 'store'),
    validatorPath,
  });
  await store.ensureReady();
  const pkg = await makePackage(tempRoot);
  const manifest = JSON.parse(await fsp.readFile(pkg.manifestPath, 'utf8'));
  manifest.files.push({
    path: 'data/private.json',
    role: 'data',
    required: false,
  });
  await fsp.mkdir(path.join(pkg.packageRoot, 'data'), { recursive: true });
  await fsp.writeFile(path.join(pkg.packageRoot, 'data/private.json'), '{}', 'utf8');
  await fsp.writeFile(pkg.manifestPath, JSON.stringify(manifest, null, 2), 'utf8');

  await assert.rejects(
    () => store.importProject(pkg),
    /data\/private\.json is blocked/
  );
});

test('creates, edits, rebuilds, and clones a project', async () => {
  const tempRoot = await fsp.mkdtemp(path.join(os.tmpdir(), 'oob-project-market-'));
  const store = new ProjectMarketStore({
    storeRoot: path.join(tempRoot, 'store'),
    validatorPath,
  });
  await store.ensureReady();

  const created = await store.createProject({
    componentId: 'meeting-summary-card',
    name: 'Meeting Summary Card',
    version: '0.1.0',
    type: 'widget',
    description: 'Summarizes meeting notes.',
    capabilities: 'local_config, prompt_template',
    component: {
      title: 'Meeting Summary',
      prompt: 'Summarize the current meeting.',
    },
  });
  assert.equal(created.item.componentId, 'meeting-summary-card');

  const updated = await store.updateProject({
    componentId: 'meeting-summary-card',
    version: '0.1.0',
    manifestUpdates: {
      name: 'Meeting Brief Card',
      description: 'Builds a meeting brief.',
      capabilities: ['local_config'],
      configuration: {
        schema: {
          type: 'object',
        },
      },
    },
    entryContent: JSON.stringify({ title: 'Brief', prompt: 'Draft a brief.' }, null, 2),
  });
  assert.equal(updated.item.name, 'Meeting Brief Card');

  const details = await store.projectDetails({
    componentId: 'meeting-summary-card',
    version: '0.1.0',
  });
  assert.equal(details.manifest.name, 'Meeting Brief Card');
  assert.match(details.entry.content, /Draft a brief/);
  assert.ok(details.files.some((file) => file.path === 'component.json'));

  const rebuilt = await store.rebuildProject({
    componentId: 'meeting-summary-card',
    version: '0.1.0',
  });
  assert.equal(rebuilt.item.version, '0.1.0');

  const cloned = await store.cloneProject({
    componentId: 'meeting-summary-card',
    version: '0.1.0',
    targetVersion: '0.1.1',
  });
  assert.equal(cloned.item.version, '0.1.1');

  const market = await store.listProjects();
  assert.deepEqual(
    market.items.map((item) => `${item.componentId}@${item.version}`),
    ['meeting-summary-card@0.1.0', 'meeting-summary-card@0.1.1']
  );
});

test('rolls back edits that introduce secrets', async () => {
  const tempRoot = await fsp.mkdtemp(path.join(os.tmpdir(), 'oob-project-market-'));
  const store = new ProjectMarketStore({
    storeRoot: path.join(tempRoot, 'store'),
    validatorPath,
  });
  await store.ensureReady();
  await store.createProject({
    componentId: 'safe-card',
    name: 'Safe Card',
    version: '0.1.0',
    type: 'widget',
  });

  await assert.rejects(
    () =>
      store.updateProject({
        componentId: 'safe-card',
        version: '0.1.0',
        manifestUpdates: {
          name: 'Unsafe Card',
        },
        entryContent: '{"token":"sk-123456789012345678901234"}',
      }),
    /appears to contain a secret or token/
  );

  const details = await store.projectDetails({
    componentId: 'safe-card',
    version: '0.1.0',
  });
  assert.equal(details.manifest.name, 'Safe Card');
  assert.doesNotMatch(details.entry.content, /sk-123456789012345678901234/);
});

test('serves health and project list over HTTP', async () => {
  const tempRoot = await fsp.mkdtemp(path.join(os.tmpdir(), 'oob-project-market-'));
  const store = new ProjectMarketStore({
    storeRoot: path.join(tempRoot, 'store'),
    validatorPath,
  });
  await store.ensureReady();
  const server = createProjectMarketServer(store);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const address = server.address();
    const baseUrl = `http://127.0.0.1:${address.port}`;
    const health = await fetch(`${baseUrl}/api/health`).then((res) => res.json());
    assert.equal(health.ok, true);
    const projects = await fetch(`${baseUrl}/api/projects`).then((res) => res.json());
    assert.equal(projects.ok, true);
    assert.deepEqual(projects.items, []);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('serves project management API over HTTP', async () => {
  const tempRoot = await fsp.mkdtemp(path.join(os.tmpdir(), 'oob-project-market-'));
  const store = new ProjectMarketStore({
    storeRoot: path.join(tempRoot, 'store'),
    validatorPath,
  });
  await store.ensureReady();
  const server = createProjectMarketServer(store);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const address = server.address();
    const baseUrl = `http://127.0.0.1:${address.port}`;
    const create = await fetch(`${baseUrl}/api/projects/create`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        componentId: 'api-card',
        name: 'API Card',
        version: '0.1.0',
        type: 'widget',
      }),
    }).then((res) => res.json());
    assert.equal(create.ok, true);

    const detail = await fetch(`${baseUrl}/api/projects/api-card/0.1.0`).then((res) =>
      res.json()
    );
    assert.equal(detail.ok, true);
    assert.equal(detail.manifest.name, 'API Card');

    const update = await fetch(`${baseUrl}/api/projects/update`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        componentId: 'api-card',
        version: '0.1.0',
        manifestUpdates: {
          name: 'Updated API Card',
        },
      }),
    }).then((res) => res.json());
    assert.equal(update.ok, true);
    assert.equal(update.item.name, 'Updated API Card');
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('uploads and downloads sandbox apps through built-in server store', async () => {
  const tempRoot = await fsp.mkdtemp(path.join(os.tmpdir(), 'oob-project-market-'));
  const localStore = new ProjectMarketStore({
    storeRoot: path.join(tempRoot, 'local'),
    validatorPath,
  });
  const serverStore = new ProjectMarketStore({
    storeRoot: path.join(tempRoot, 'server'),
    validatorPath,
  });
  await localStore.ensureReady();
  await serverStore.ensureReady();
  await localStore.createProject({
    componentId: 'server-sync-card',
    name: 'Server Sync Card',
    version: '0.1.0',
    type: 'widget',
    description: 'A sandbox app used to verify server sync.',
  });

  const uploaded = await serverStore.copyProjectFrom(localStore, {
    componentId: 'server-sync-card',
    version: '0.1.0',
    overwrite: true,
  });
  assert.equal(uploaded.item.componentId, 'server-sync-card');
  const serverMarket = await serverStore.listProjects('http://127.0.0.1:17331', {
    archiveRoutePrefix: '/api/server/projects',
  });
  assert.equal(
    serverMarket.items[0].downloadUrl,
    'http://127.0.0.1:17331/api/server/projects/server-sync-card/0.1.0/archive'
  );

  await localStore.removeProject({
    componentId: 'server-sync-card',
    version: '0.1.0',
  });
  const downloaded = await localStore.copyProjectFrom(serverStore, {
    componentId: 'server-sync-card',
    version: '0.1.0',
    overwrite: true,
  });
  assert.equal(downloaded.item.name, 'Server Sync Card');
  const localMarket = await localStore.listProjects();
  assert.deepEqual(
    localMarket.items.map((item) => `${item.componentId}@${item.version}`),
    ['server-sync-card@0.1.0']
  );
});

test('serves built-in server upload and download API over HTTP', async () => {
  const tempRoot = await fsp.mkdtemp(path.join(os.tmpdir(), 'oob-project-market-'));
  const localStore = new ProjectMarketStore({
    storeRoot: path.join(tempRoot, 'local'),
    validatorPath,
  });
  const serverStore = new ProjectMarketStore({
    storeRoot: path.join(tempRoot, 'server'),
    validatorPath,
  });
  await localStore.ensureReady();
  await serverStore.ensureReady();
  await localStore.createProject({
    componentId: 'api-server-card',
    name: 'API Server Card',
    version: '0.1.0',
    type: 'widget',
    description: 'A sandbox app used to verify server APIs.',
  });
  const server = createProjectMarketServer(localStore, { serverStore });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const address = server.address();
    const baseUrl = `http://127.0.0.1:${address.port}`;
    const upload = await fetch(`${baseUrl}/api/server/upload`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        componentId: 'api-server-card',
        version: '0.1.0',
        overwrite: true,
      }),
    }).then((res) => res.json());
    assert.equal(upload.ok, true);
    assert.match(upload.item.downloadUrl, /\/api\/server\/projects\/api-server-card\/0\.1\.0\/archive$/);

    await localStore.removeProject({
      componentId: 'api-server-card',
      version: '0.1.0',
    });

    const download = await fetch(`${baseUrl}/api/server/download`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        componentId: 'api-server-card',
        version: '0.1.0',
        overwrite: true,
      }),
    }).then((res) => res.json());
    assert.equal(download.ok, true);
    assert.equal(download.item.componentId, 'api-server-card');

    const archive = await fetch(`${baseUrl}/api/server/projects/api-server-card/0.1.0/archive`);
    assert.equal(archive.status, 200);
    assert.equal(archive.headers.get('content-type'), 'application/zip');
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
