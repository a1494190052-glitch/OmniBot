#!/usr/bin/env node

import crypto from 'node:crypto';
import { execFile } from 'node:child_process';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '../..');
const publicDir = path.join(__dirname, 'public');
const defaultValidatorPath = path.join(
  repoRoot,
  'app/src/main/assets/builtin_skills/oob-project-sandbox/scripts/validate_component_package.py'
);
const marketSchemaVersion = 'oob.project.market.v1';
const componentSchemaVersion = 'oob.project.component.v1';
const componentManifestFile = 'oob_project_component_manifest.v1.json';
const defaultStoreRoot = path.join(os.homedir(), '.omnibot', 'project-market-server');
const componentIdRe = /^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$/;
const semverRe = /^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$/;
const allowedTypes = new Set([
  'skill',
  'widget',
  'workflow',
  'template_app',
  'asset_bundle',
  'display',
]);
const editableTextLimit = 256 * 1024;
const textTypes = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.svg', 'image/svg+xml'],
  ['.txt', 'text/plain; charset=utf-8'],
]);

function printHelp() {
  console.log(`Omnibot Project Market Server

Usage:
  omnibot-project-market [options]
  node server.mjs [options]

Options:
  --host <host>          Listen host. Defaults to 127.0.0.1.
  --port <port>          Listen port. Defaults to 17331.
  --store <path>         Storage root. Defaults to ~/.omnibot/project-market-server.
  --server-store <path>  Built-in server storage root. Defaults to <store>/builtin-server.
  --python <path>        Python executable. Defaults to python3.
  --validator <path>     Project package validator path.
  -h, --help             Show this help.`);
}

function readOptionValue(args, index, option) {
  const arg = args[index];
  const equalsIndex = arg.indexOf('=');
  if (equalsIndex >= 0) {
    return { value: arg.slice(equalsIndex + 1), nextIndex: index };
  }
  const value = args[index + 1];
  if (value == null || value.startsWith('-')) {
    throw new Error(`${option} requires a value.`);
  }
  return { value, nextIndex: index + 1 };
}

function parseArgs(args) {
  const options = {
    host: process.env.OMNIBOT_PROJECT_MARKET_HOST || '127.0.0.1',
    port: Number(process.env.OMNIBOT_PROJECT_MARKET_PORT || 17331),
    storeRoot: process.env.OMNIBOT_PROJECT_MARKET_STORE || defaultStoreRoot,
    serverStoreRoot: process.env.OMNIBOT_PROJECT_MARKET_SERVER_STORE || '',
    python: process.env.PYTHON || 'python3',
    validatorPath: process.env.OMNIBOT_PROJECT_MARKET_VALIDATOR || defaultValidatorPath,
  };
  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === '-h' || arg === '--help') {
      options.help = true;
      continue;
    }
    const optionMap = {
      '--host': 'host',
      '--port': 'port',
      '--store': 'storeRoot',
      '--server-store': 'serverStoreRoot',
      '--python': 'python',
      '--validator': 'validatorPath',
    };
    const matchedOption = Object.keys(optionMap).find(
      (option) => arg === option || arg.startsWith(`${option}=`)
    );
    if (!matchedOption) {
      throw new Error(`Unknown option: ${arg}`);
    }
    const parsed = readOptionValue(args, index, matchedOption);
    const key = optionMap[matchedOption];
    options[key] = key === 'port' ? Number(parsed.value) : parsed.value;
    index = parsed.nextIndex;
  }
  if (!Number.isInteger(options.port) || options.port < 1 || options.port > 65535) {
    throw new Error('port must be an integer between 1 and 65535');
  }
  options.storeRoot = expandHomePath(options.storeRoot);
  options.serverStoreRoot = hasText(options.serverStoreRoot)
    ? expandHomePath(options.serverStoreRoot)
    : path.join(options.storeRoot, 'builtin-server');
  options.validatorPath = path.resolve(expandHomePath(options.validatorPath));
  return options;
}

function expandHomePath(rawPath) {
  const value = String(rawPath || '').trim();
  if (value === '~') return os.homedir();
  if (value.startsWith('~/') || value.startsWith('~\\')) {
    return path.join(os.homedir(), value.slice(2));
  }
  return value;
}

function hasText(value) {
  return String(value ?? '').trim().length > 0;
}

function safeName(value) {
  return String(value || '').replace(/[^a-zA-Z0-9._-]/g, '-');
}

function assertComponentId(value) {
  if (!componentIdRe.test(String(value || ''))) {
    throw new Error('componentId must use lowercase letters, digits, and hyphens');
  }
}

function assertSemver(value) {
  if (!semverRe.test(String(value || ''))) {
    throw new Error('version must be semantic version format, for example 0.1.0');
  }
}

function assertComponentType(value) {
  if (!allowedTypes.has(String(value || ''))) {
    throw new Error(`type must be one of ${Array.from(allowedTypes).sort().join(', ')}`);
  }
}

function isSubpath(parent, child) {
  const relative = path.relative(parent, child);
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

function normalizePackagePath(value) {
  const raw = String(value || '').replace(/\\/g, '/').trim();
  if (!raw || raw.startsWith('/')) return '';
  const parts = [];
  for (const part of raw.split('/')) {
    if (!part || part === '.') continue;
    if (part === '..') return '';
    parts.push(part);
  }
  return parts.join('/');
}

function sha256File(filePath) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    const stream = fs.createReadStream(filePath);
    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('error', reject);
    stream.on('end', () => resolve(hash.digest('hex')));
  });
}

function execFileText(command, args, options = {}) {
  return new Promise((resolve) => {
    execFile(command, args, { ...options, encoding: 'utf8' }, (error, stdout, stderr) => {
      resolve({
        ok: !error,
        code: error?.code ?? 0,
        stdout: stdout || '',
        stderr: stderr || '',
      });
    });
  });
}

async function readJson(filePath, fallback) {
  try {
    const text = await fsp.readFile(filePath, 'utf8');
    return JSON.parse(text);
  } catch (error) {
    if (error?.code === 'ENOENT' && fallback !== undefined) return fallback;
    throw error;
  }
}

async function writeJson(filePath, value) {
  await fsp.mkdir(path.dirname(filePath), { recursive: true });
  const text = `${JSON.stringify(value, null, 2)}\n`;
  await fsp.writeFile(filePath, text, 'utf8');
}

function parseJsonField(value, fieldName) {
  if (value === undefined) return undefined;
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!trimmed) return {};
    try {
      return JSON.parse(trimmed);
    } catch {
      throw new Error(`${fieldName} must be valid JSON`);
    }
  }
  return value;
}

function stringArray(value) {
  if (value === undefined) return undefined;
  if (typeof value === 'string') {
    return value
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean);
  }
  if (!Array.isArray(value)) {
    throw new Error('value must be an array or comma-separated string');
  }
  return value.map((item) => String(item).trim()).filter(Boolean);
}

async function removePath(targetPath) {
  await fsp.rm(targetPath, { recursive: true, force: true });
}

async function copyDeclaredFile(sourceRoot, targetRoot, relPath) {
  const normalized = normalizePackagePath(relPath);
  if (!normalized) {
    throw new Error(`Invalid package path: ${relPath}`);
  }
  const sourcePath = path.resolve(sourceRoot, normalized);
  const targetPath = path.resolve(targetRoot, normalized);
  if (!isSubpath(path.resolve(sourceRoot), sourcePath)) {
    throw new Error(`Package path escapes source root: ${relPath}`);
  }
  if (!isSubpath(path.resolve(targetRoot), targetPath)) {
    throw new Error(`Package path escapes target root: ${relPath}`);
  }
  await fsp.mkdir(path.dirname(targetPath), { recursive: true });
  await fsp.copyFile(sourcePath, targetPath);
}

async function pathExists(targetPath) {
  try {
    await fsp.access(targetPath);
    return true;
  } catch {
    return false;
  }
}

async function readTextIfSmall(filePath, maxBytes = editableTextLimit) {
  const stat = await fsp.stat(filePath);
  if (stat.size > maxBytes) {
    return {
      editable: false,
      reason: `file is larger than ${maxBytes} bytes`,
    };
  }
  const buffer = await fsp.readFile(filePath);
  if (buffer.includes(0)) {
    return {
      editable: false,
      reason: 'file appears to be binary',
    };
  }
  return {
    editable: true,
    content: buffer.toString('utf8'),
  };
}

async function listPackageFiles(packageDir) {
  const root = path.resolve(packageDir);
  const results = [];
  async function walk(dir) {
    const entries = await fsp.readdir(dir, { withFileTypes: true });
    entries.sort((a, b) => a.name.localeCompare(b.name));
    for (const entry of entries) {
      const fullPath = path.join(dir, entry.name);
      const relPath = path.relative(root, fullPath).replace(/\\/g, '/');
      if (entry.isDirectory()) {
        await walk(fullPath);
        continue;
      }
      if (!entry.isFile()) continue;
      const stat = await fsp.stat(fullPath);
      results.push({
        path: relPath,
        size: stat.size,
        sha256: await sha256File(fullPath),
      });
    }
  }
  if (await pathExists(root)) {
    await walk(root);
  }
  return results;
}

async function copyPackageDirectory(sourceDir, targetDir) {
  await fsp.cp(sourceDir, targetDir, { recursive: true });
}

async function createZipArchive({ python, sourceDir, archivePath }) {
  const script = [
    'import os, sys, zipfile',
    'source = sys.argv[1]',
    'archive = sys.argv[2]',
    'with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:',
    '    for root, dirs, files in os.walk(source):',
    '        dirs[:] = sorted(d for d in dirs if d != "__pycache__")',
    '        for name in sorted(files):',
    '            p = os.path.join(root, name)',
    '            rel = os.path.relpath(p, source).replace(os.sep, "/")',
    '            z.write(p, rel)',
  ].join('\n');
  await fsp.mkdir(path.dirname(archivePath), { recursive: true });
  const result = await execFileText(python, ['-c', script, sourceDir, archivePath]);
  if (!result.ok) {
    throw new Error(result.stderr || result.stdout || 'Failed to create archive');
  }
}

export class ProjectMarketStore {
  constructor({ storeRoot = defaultStoreRoot, python = 'python3', validatorPath = defaultValidatorPath } = {}) {
    this.storeRoot = path.resolve(expandHomePath(storeRoot));
    this.python = python;
    this.validatorPath = path.resolve(expandHomePath(validatorPath));
    this.marketPath = path.join(this.storeRoot, 'market.json');
    this.packagesRoot = path.join(this.storeRoot, 'packages');
    this.archivesRoot = path.join(this.storeRoot, 'archives');
  }

  async ensureReady() {
    await fsp.mkdir(this.packagesRoot, { recursive: true });
    await fsp.mkdir(this.archivesRoot, { recursive: true });
    const market = await this.readMarket();
    await writeJson(this.marketPath, market);
  }

  async readMarket() {
    const market = await readJson(this.marketPath, {
      schemaVersion: marketSchemaVersion,
      items: [],
    });
    if (market.schemaVersion !== marketSchemaVersion || !Array.isArray(market.items)) {
      return { schemaVersion: marketSchemaVersion, items: [] };
    }
    return market;
  }

  async listProjects(requestOrigin = '', { archiveRoutePrefix = '/api/projects' } = {}) {
    const market = await this.readMarket();
    return {
      storeRoot: this.storeRoot,
      schemaVersion: market.schemaVersion,
      items: market.items.map((item) =>
        this.withDownloadUrl(item, requestOrigin, archiveRoutePrefix)
      ),
    };
  }

  packageDir(componentId, version) {
    return path.join(this.packagesRoot, safeName(componentId), safeName(version));
  }

  manifestPath(componentId, version) {
    return path.join(this.packageDir(componentId, version), componentManifestFile);
  }

  async readStoredManifest(componentId, version) {
    return readJson(this.manifestPath(componentId, version));
  }

  async findMarketItem(componentId, version) {
    const market = await this.readMarket();
    return market.items.find(
      (item) => item.componentId === componentId && item.version === version
    );
  }

  async projectDetails({ componentId, version }, requestOrigin = '') {
    if (!hasText(componentId) || !hasText(version)) {
      throw new Error('componentId and version are required');
    }
    const packageDir = this.packageDir(componentId, version);
    const manifest = await this.readStoredManifest(componentId, version);
    const item =
      (await this.findMarketItem(componentId, version)) ||
      (await this.buildMarketItem({ manifest, packageDir }));
    const entryPath = normalizePackagePath(manifest.entry?.path);
    let entry = {
      path: entryPath,
      editable: false,
    };
    if (entryPath) {
      const resolvedEntryPath = path.resolve(packageDir, entryPath);
      if (isSubpath(packageDir, resolvedEntryPath) && (await pathExists(resolvedEntryPath))) {
        entry = {
          path: entryPath,
          ...(await readTextIfSmall(resolvedEntryPath)),
        };
      }
    }
    return {
      item: this.withDownloadUrl(item, requestOrigin),
      manifest,
      files: await listPackageFiles(packageDir),
      entry,
    };
  }

  async validatePackage({ manifestPath, packageRoot }) {
    if (!hasText(manifestPath)) {
      throw new Error('manifestPath is required');
    }
    const resolvedManifest = path.resolve(expandHomePath(manifestPath));
    const resolvedRoot = path.resolve(expandHomePath(packageRoot || path.dirname(resolvedManifest)));
    const args = [this.validatorPath, '--manifest', resolvedManifest, '--root', resolvedRoot];
    const result = await execFileText(this.python, args);
    return {
      ok: result.ok,
      code: result.code,
      stdout: result.stdout.trim(),
      stderr: result.stderr.trim(),
      manifestPath: resolvedManifest,
      packageRoot: resolvedRoot,
    };
  }

  async importProject({ manifestPath, packageRoot }) {
    const validation = await this.validatePackage({ manifestPath, packageRoot });
    if (!validation.ok) {
      const message = [validation.stdout, validation.stderr].filter(Boolean).join('\n');
      const error = new Error(message || 'Project package validation failed');
      error.statusCode = 422;
      error.validation = validation;
      throw error;
    }

    const manifest = await readJson(validation.manifestPath);
    const componentId = manifest.componentId;
    const version = manifest.version;
    if (!hasText(componentId) || !hasText(version)) {
      throw new Error('manifest must include componentId and version');
    }

    const packageDir = this.packageDir(componentId, version);
    await removePath(packageDir);
    await fsp.mkdir(packageDir, { recursive: true });

    const files = Array.isArray(manifest.files) ? manifest.files : [];
    for (const file of files) {
      const relPath = normalizePackagePath(file.path);
      const sourcePath = path.resolve(validation.packageRoot, relPath);
      if (file.required === false && !(await pathExists(sourcePath))) {
        continue;
      }
      await copyDeclaredFile(validation.packageRoot, packageDir, file.path);
    }
    await fsp.copyFile(validation.manifestPath, path.join(packageDir, componentManifestFile));
    const rebuild = await this.rebuildProject({ componentId, version });
    return {
      item: rebuild.item,
      validation,
      archivePath: rebuild.archivePath,
      packagePath: packageDir,
    };
  }

  async buildMarketItem({ manifest, packageDir }) {
    const componentId = manifest.componentId;
    const version = manifest.version;
    const archivePath = this.archivePath(componentId, version);
    const manifestPath = path.join(packageDir, componentManifestFile);
    const manifestSha256 = await sha256File(manifestPath);
    const archiveSha256 = (await pathExists(archivePath)) ? await sha256File(archivePath) : '';
    return {
      componentId,
      name: manifest.name,
      version,
      type: manifest.type,
      description: manifest.description || '',
      downloadUrl: `/api/projects/${encodeURIComponent(componentId)}/${encodeURIComponent(version)}/archive`,
      manifestSha256,
      archiveSha256,
      capabilities: Array.isArray(manifest.capabilities) ? manifest.capabilities : [],
      dataPolicySummary: {
        uploadsUserData: false,
        uploadsRuntimeData: false,
      },
      local: {
        packagePath: packageDir,
        archivePath,
        storedAt: new Date().toISOString(),
      },
    };
  }

  async rebuildProject({ componentId, version }) {
    if (!hasText(componentId) || !hasText(version)) {
      throw new Error('componentId and version are required');
    }
    const packageDir = this.packageDir(componentId, version);
    const manifestPath = this.manifestPath(componentId, version);
    const validation = await this.validatePackage({
      manifestPath,
      packageRoot: packageDir,
    });
    if (!validation.ok) {
      const message = [validation.stdout, validation.stderr].filter(Boolean).join('\n');
      const error = new Error(message || 'Project package validation failed');
      error.statusCode = 422;
      error.validation = validation;
      throw error;
    }
    const manifest = await readJson(manifestPath);
    const archivePath = this.archivePath(componentId, version);
    await createZipArchive({ python: this.python, sourceDir: packageDir, archivePath });
    const item = await this.buildMarketItem({ manifest, packageDir });
    await this.upsertMarketItem(item);
    return {
      item,
      validation,
      archivePath,
      packagePath: packageDir,
    };
  }

  async createProject({
    componentId,
    name,
    version = '0.1.0',
    type = 'widget',
    description = '',
    capabilities = [],
    configuration,
    component,
    readme,
    overwrite = false,
  }) {
    assertComponentId(componentId);
    assertSemver(version);
    assertComponentType(type);
    if (!hasText(name)) {
      throw new Error('name is required');
    }
    const packageDir = this.packageDir(componentId, version);
    if (!overwrite && (await pathExists(packageDir))) {
      const error = new Error(`${componentId}@${version} already exists`);
      error.statusCode = 409;
      throw error;
    }
    await removePath(packageDir);
    await fsp.mkdir(packageDir, { recursive: true });
    const componentValue = parseJsonField(component, 'component') ?? {
      title: name,
      prompt: '',
    };
    const configurationValue = parseJsonField(configuration, 'configuration') ?? {
      schema: {
        type: 'object',
        properties: {
          title: {
            type: 'string',
            default: name,
          },
          prompt: {
            type: 'string',
            default: '',
          },
        },
      },
    };
    const manifest = {
      schemaVersion: componentSchemaVersion,
      componentId,
      name: String(name).trim(),
      version,
      type,
      description:
        String(description || '').trim() || 'A configurable Omnibot Project component.',
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
      capabilities: stringArray(capabilities) ?? [],
      permissions: [],
      configuration: configurationValue,
      dataPolicy: {
        uploadsUserData: false,
        uploadsRuntimeData: false,
        storesDataLocally: false,
        excludedPaths: [
          'data/**',
          'logs/**',
          'cache/**',
          '.env',
          '*.jks',
          '*.keystore',
        ],
      },
    };
    await writeJson(path.join(packageDir, 'component.json'), componentValue);
    await fsp.writeFile(
      path.join(packageDir, 'README.md'),
      hasText(readme) ? String(readme) : `# ${name}\n`,
      'utf8'
    );
    await writeJson(path.join(packageDir, componentManifestFile), manifest);
    return this.rebuildProject({ componentId, version });
  }

  applyManifestUpdates(manifest, updates = {}) {
    const next = structuredClone(manifest);
    if (Object.prototype.hasOwnProperty.call(updates, 'name')) {
      if (!hasText(updates.name)) throw new Error('name is required');
      next.name = String(updates.name).trim();
    }
    if (Object.prototype.hasOwnProperty.call(updates, 'description')) {
      next.description = String(updates.description || '').trim();
    }
    if (Object.prototype.hasOwnProperty.call(updates, 'type')) {
      assertComponentType(updates.type);
      next.type = String(updates.type);
    }
    if (Object.prototype.hasOwnProperty.call(updates, 'capabilities')) {
      next.capabilities = stringArray(updates.capabilities) ?? [];
    }
    if (Object.prototype.hasOwnProperty.call(updates, 'configuration')) {
      next.configuration = parseJsonField(updates.configuration, 'configuration') ?? {};
    }
    if (Object.prototype.hasOwnProperty.call(updates, 'permissions')) {
      next.permissions = Array.isArray(updates.permissions) ? updates.permissions : [];
    }
    next.dataPolicy = {
      ...(next.dataPolicy || {}),
      uploadsUserData: false,
      uploadsRuntimeData: false,
    };
    return next;
  }

  async updateProject({ componentId, version, manifestUpdates = {}, entryContent }) {
    if (!hasText(componentId) || !hasText(version)) {
      throw new Error('componentId and version are required');
    }
    const packageDir = this.packageDir(componentId, version);
    const manifestPath = this.manifestPath(componentId, version);
    const currentManifestText = await fsp.readFile(manifestPath, 'utf8');
    const currentManifest = JSON.parse(currentManifestText);
    const nextManifest = this.applyManifestUpdates(currentManifest, manifestUpdates);
    let entryPath = '';
    let currentEntryContent = null;

    try {
      await writeJson(manifestPath, nextManifest);
      if (entryContent !== undefined) {
        const entryRelPath = normalizePackagePath(nextManifest.entry?.path);
        const declaredPaths = new Set(
          (Array.isArray(nextManifest.files) ? nextManifest.files : [])
            .map((file) => normalizePackagePath(file.path))
            .filter(Boolean)
        );
        if (!entryRelPath || !declaredPaths.has(entryRelPath)) {
          throw new Error('entry.path must be declared in files before editing entry content');
        }
        if (Buffer.byteLength(String(entryContent), 'utf8') > editableTextLimit) {
          throw new Error(`entryContent must be ${editableTextLimit} bytes or smaller`);
        }
        entryPath = path.resolve(packageDir, entryRelPath);
        if (!isSubpath(packageDir, entryPath)) {
          throw new Error('entry.path escapes package root');
        }
        currentEntryContent = (await pathExists(entryPath))
          ? await fsp.readFile(entryPath, 'utf8')
          : null;
        await fsp.writeFile(entryPath, String(entryContent), 'utf8');
      }
      return await this.rebuildProject({ componentId, version });
    } catch (error) {
      await fsp.writeFile(manifestPath, currentManifestText, 'utf8');
      if (entryPath) {
        if (currentEntryContent === null) {
          await removePath(entryPath);
        } else {
          await fsp.writeFile(entryPath, currentEntryContent, 'utf8');
        }
      }
      throw error;
    }
  }

  async cloneProject({ componentId, version, targetComponentId, targetVersion, targetName, overwrite = false }) {
    if (!hasText(componentId) || !hasText(version) || !hasText(targetVersion)) {
      throw new Error('componentId, version, and targetVersion are required');
    }
    const nextComponentId = hasText(targetComponentId) ? String(targetComponentId).trim() : componentId;
    assertComponentId(nextComponentId);
    assertSemver(targetVersion);
    const sourceDir = this.packageDir(componentId, version);
    const targetDir = this.packageDir(nextComponentId, targetVersion);
    if (!(await pathExists(sourceDir))) {
      const error = new Error(`${componentId}@${version} does not exist`);
      error.statusCode = 404;
      throw error;
    }
    if (!overwrite && (await pathExists(targetDir))) {
      const error = new Error(`${nextComponentId}@${targetVersion} already exists`);
      error.statusCode = 409;
      throw error;
    }
    await removePath(targetDir);
    await fsp.mkdir(path.dirname(targetDir), { recursive: true });
    await copyPackageDirectory(sourceDir, targetDir);
    const manifestPath = path.join(targetDir, componentManifestFile);
    const manifest = await readJson(manifestPath);
    manifest.componentId = nextComponentId;
    manifest.version = targetVersion;
    if (hasText(targetName)) {
      manifest.name = String(targetName).trim();
    }
    manifest.dataPolicy = {
      ...(manifest.dataPolicy || {}),
      uploadsUserData: false,
      uploadsRuntimeData: false,
    };
    await writeJson(manifestPath, manifest);
    return this.rebuildProject({
      componentId: nextComponentId,
      version: targetVersion,
    });
  }

  async copyProjectFrom(sourceStore, { componentId, version, overwrite = false }) {
    if (!hasText(componentId) || !hasText(version)) {
      throw new Error('componentId and version are required');
    }
    const sourceDir = sourceStore.packageDir(componentId, version);
    if (!(await pathExists(sourceDir))) {
      const error = new Error(`${componentId}@${version} does not exist`);
      error.statusCode = 404;
      throw error;
    }
    const targetDir = this.packageDir(componentId, version);
    if (!overwrite && (await pathExists(targetDir))) {
      const error = new Error(`${componentId}@${version} already exists`);
      error.statusCode = 409;
      throw error;
    }
    await removePath(targetDir);
    await fsp.mkdir(path.dirname(targetDir), { recursive: true });
    await copyPackageDirectory(sourceDir, targetDir);
    return this.rebuildProject({ componentId, version });
  }

  async upsertMarketItem(item) {
    const market = await this.readMarket();
    const nextItems = market.items.filter(
      (existing) =>
        existing.componentId !== item.componentId || existing.version !== item.version
    );
    nextItems.push(item);
    nextItems.sort((a, b) =>
      `${a.componentId}@${a.version}`.localeCompare(`${b.componentId}@${b.version}`)
    );
    await writeJson(this.marketPath, {
      schemaVersion: marketSchemaVersion,
      items: nextItems,
    });
  }

  async removeProject({ componentId, version }) {
    if (!hasText(componentId) || !hasText(version)) {
      throw new Error('componentId and version are required');
    }
    const market = await this.readMarket();
    const existing = market.items.find(
      (item) => item.componentId === componentId && item.version === version
    );
    const nextItems = market.items.filter(
      (item) => item.componentId !== componentId || item.version !== version
    );
    await writeJson(this.marketPath, {
      schemaVersion: marketSchemaVersion,
      items: nextItems,
    });
    await removePath(path.join(this.packagesRoot, safeName(componentId), safeName(version)));
    await removePath(path.join(this.archivesRoot, `${safeName(componentId)}-${safeName(version)}.zip`));
    return { removed: Boolean(existing) };
  }

  archivePath(componentId, version) {
    return path.join(this.archivesRoot, `${safeName(componentId)}-${safeName(version)}.zip`);
  }

  withDownloadUrl(item, requestOrigin = '', archiveRoutePrefix = '/api/projects') {
    const clone = structuredClone(item);
    clone.downloadUrl = `${archiveRoutePrefix}/${encodeURIComponent(clone.componentId)}/${encodeURIComponent(clone.version)}/archive`;
    if (requestOrigin && String(clone.downloadUrl || '').startsWith('/')) {
      clone.downloadUrl = `${requestOrigin}${clone.downloadUrl}`;
    }
    return clone;
  }
}

async function readRequestJson(req) {
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > 1024 * 1024) {
      const error = new Error('Request body is too large');
      error.statusCode = 413;
      throw error;
    }
    chunks.push(chunk);
  }
  const raw = Buffer.concat(chunks).toString('utf8').trim();
  return raw ? JSON.parse(raw) : {};
}

function sendJson(res, statusCode, payload) {
  const body = `${JSON.stringify(payload, null, 2)}\n`;
  res.writeHead(statusCode, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
  });
  res.end(body);
}

function sendError(res, error) {
  sendJson(res, error.statusCode || 500, {
    ok: false,
    error: error.message || String(error),
    validation: error.validation,
  });
}

function requestOrigin(req) {
  const proto = req.headers['x-forwarded-proto'] || 'http';
  const host = req.headers.host || '';
  return host ? `${proto}://${host}` : '';
}

async function serveStatic(req, res, pathname) {
  const rawPath = pathname === '/' ? '/index.html' : pathname;
  const targetPath = path.resolve(publicDir, `.${decodeURIComponent(rawPath)}`);
  if (!isSubpath(publicDir, targetPath)) {
    res.writeHead(403);
    res.end('Forbidden');
    return;
  }
  try {
    const stat = await fsp.stat(targetPath);
    if (!stat.isFile()) {
      res.writeHead(404);
      res.end('Not found');
      return;
    }
    const contentType = textTypes.get(path.extname(targetPath).toLowerCase()) || 'application/octet-stream';
    res.writeHead(200, {
      'content-type': contentType,
      'content-length': stat.size,
      'cache-control': 'no-store',
    });
    fs.createReadStream(targetPath).pipe(res);
  } catch (error) {
    if (error?.code === 'ENOENT') {
      res.writeHead(404);
      res.end('Not found');
      return;
    }
    throw error;
  }
}

export function createProjectMarketServer(store, { serverStore } = {}) {
  return http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url || '/', 'http://127.0.0.1');
      const pathname = url.pathname;

      if (req.method === 'GET' && pathname === '/api/health') {
        sendJson(res, 200, {
          ok: true,
          service: 'omnibot-project-market',
          storeRoot: store.storeRoot,
          serverStoreRoot: serverStore?.storeRoot || null,
        });
        return;
      }

      if (req.method === 'GET' && pathname === '/api/projects') {
        sendJson(res, 200, {
          ok: true,
          ...(await store.listProjects(requestOrigin(req))),
        });
        return;
      }

      if (req.method === 'GET' && pathname === '/api/market') {
        const market = await store.listProjects(requestOrigin(req));
        sendJson(res, 200, {
          schemaVersion: market.schemaVersion,
          items: market.items,
        });
        return;
      }

      if (req.method === 'GET' && pathname === '/api/server/projects') {
        if (!serverStore) {
          const error = new Error('built-in server store is not configured');
          error.statusCode = 503;
          throw error;
        }
        sendJson(res, 200, {
          ok: true,
          ...(await serverStore.listProjects(requestOrigin(req), {
            archiveRoutePrefix: '/api/server/projects',
          })),
        });
        return;
      }

      if (req.method === 'GET' && pathname === '/api/server/market') {
        if (!serverStore) {
          const error = new Error('built-in server store is not configured');
          error.statusCode = 503;
          throw error;
        }
        const market = await serverStore.listProjects(requestOrigin(req), {
          archiveRoutePrefix: '/api/server/projects',
        });
        sendJson(res, 200, {
          schemaVersion: market.schemaVersion,
          items: market.items,
        });
        return;
      }

      if (req.method === 'POST' && pathname === '/api/validate') {
        const body = await readRequestJson(req);
        const validation = await store.validatePackage(body);
        sendJson(res, validation.ok ? 200 : 422, {
          ok: validation.ok,
          validation,
        });
        return;
      }

      if (req.method === 'POST' && pathname === '/api/projects/import') {
        const body = await readRequestJson(req);
        const result = await store.importProject(body);
        sendJson(res, 201, {
          ok: true,
          item: store.withDownloadUrl(result.item, requestOrigin(req)),
          validation: result.validation,
        });
        return;
      }

      if (req.method === 'POST' && pathname === '/api/projects/create') {
        const body = await readRequestJson(req);
        const result = await store.createProject(body);
        sendJson(res, 201, {
          ok: true,
          item: store.withDownloadUrl(result.item, requestOrigin(req)),
          validation: result.validation,
        });
        return;
      }

      if (req.method === 'POST' && pathname === '/api/projects/update') {
        const body = await readRequestJson(req);
        const result = await store.updateProject(body);
        sendJson(res, 200, {
          ok: true,
          item: store.withDownloadUrl(result.item, requestOrigin(req)),
          validation: result.validation,
        });
        return;
      }

      if (req.method === 'POST' && pathname === '/api/projects/rebuild') {
        const body = await readRequestJson(req);
        const result = await store.rebuildProject(body);
        sendJson(res, 200, {
          ok: true,
          item: store.withDownloadUrl(result.item, requestOrigin(req)),
          validation: result.validation,
        });
        return;
      }

      if (req.method === 'POST' && pathname === '/api/projects/clone') {
        const body = await readRequestJson(req);
        const result = await store.cloneProject(body);
        sendJson(res, 201, {
          ok: true,
          item: store.withDownloadUrl(result.item, requestOrigin(req)),
          validation: result.validation,
        });
        return;
      }

      if (req.method === 'POST' && pathname === '/api/projects/remove') {
        const body = await readRequestJson(req);
        const result = await store.removeProject(body);
        sendJson(res, 200, {
          ok: true,
          ...result,
        });
        return;
      }

      if (req.method === 'POST' && pathname === '/api/server/upload') {
        if (!serverStore) {
          const error = new Error('built-in server store is not configured');
          error.statusCode = 503;
          throw error;
        }
        const body = await readRequestJson(req);
        const result = await serverStore.copyProjectFrom(store, {
          componentId: body.componentId,
          version: body.version,
          overwrite: body.overwrite !== false,
        });
        sendJson(res, 201, {
          ok: true,
          item: serverStore.withDownloadUrl(
            result.item,
            requestOrigin(req),
            '/api/server/projects'
          ),
          validation: result.validation,
        });
        return;
      }

      if (req.method === 'POST' && pathname === '/api/server/download') {
        if (!serverStore) {
          const error = new Error('built-in server store is not configured');
          error.statusCode = 503;
          throw error;
        }
        const body = await readRequestJson(req);
        const result = await store.copyProjectFrom(serverStore, {
          componentId: body.componentId,
          version: body.version,
          overwrite: body.overwrite !== false,
        });
        sendJson(res, 201, {
          ok: true,
          item: store.withDownloadUrl(result.item, requestOrigin(req)),
          validation: result.validation,
        });
        return;
      }

      const detailMatch = pathname.match(/^\/api\/projects\/([^/]+)\/([^/]+)$/);
      if (req.method === 'GET' && detailMatch) {
        const componentId = decodeURIComponent(detailMatch[1]);
        const version = decodeURIComponent(detailMatch[2]);
        sendJson(res, 200, {
          ok: true,
          ...(await store.projectDetails({ componentId, version }, requestOrigin(req))),
        });
        return;
      }

      const archiveMatch = pathname.match(/^\/api\/projects\/([^/]+)\/([^/]+)\/archive$/);
      if (req.method === 'GET' && archiveMatch) {
        const componentId = decodeURIComponent(archiveMatch[1]);
        const version = decodeURIComponent(archiveMatch[2]);
        const archivePath = store.archivePath(componentId, version);
        const stat = await fsp.stat(archivePath);
        res.writeHead(200, {
          'content-type': 'application/zip',
          'content-length': stat.size,
          'content-disposition': `attachment; filename="${safeName(componentId)}-${safeName(version)}.zip"`,
          'cache-control': 'no-store',
        });
        fs.createReadStream(archivePath).pipe(res);
        return;
      }

      const serverArchiveMatch = pathname.match(/^\/api\/server\/projects\/([^/]+)\/([^/]+)\/archive$/);
      if (req.method === 'GET' && serverArchiveMatch) {
        if (!serverStore) {
          const error = new Error('built-in server store is not configured');
          error.statusCode = 503;
          throw error;
        }
        const componentId = decodeURIComponent(serverArchiveMatch[1]);
        const version = decodeURIComponent(serverArchiveMatch[2]);
        const archivePath = serverStore.archivePath(componentId, version);
        const stat = await fsp.stat(archivePath);
        res.writeHead(200, {
          'content-type': 'application/zip',
          'content-length': stat.size,
          'content-disposition': `attachment; filename="${safeName(componentId)}-${safeName(version)}.zip"`,
          'cache-control': 'no-store',
        });
        fs.createReadStream(archivePath).pipe(res);
        return;
      }

      if (req.method === 'GET') {
        await serveStatic(req, res, pathname);
        return;
      }

      res.writeHead(405, { allow: 'GET, POST' });
      res.end('Method not allowed');
    } catch (error) {
      sendError(res, error);
    }
  });
}

export async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  if (options.help) {
    printHelp();
    return null;
  }
  const store = new ProjectMarketStore(options);
  const serverStore = new ProjectMarketStore({
    ...options,
    storeRoot: options.serverStoreRoot,
  });
  await store.ensureReady();
  await serverStore.ensureReady();
  const server = createProjectMarketServer(store, { serverStore });
  await new Promise((resolve) => {
    server.listen(options.port, options.host, resolve);
  });
  const address = server.address();
  const port = typeof address === 'object' && address ? address.port : options.port;
  console.log(`Project market server: http://${options.host}:${port}`);
  console.log(`Storage: ${store.storeRoot}`);
  console.log(`Built-in server storage: ${serverStore.storeRoot}`);
  return server;
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  main().catch((error) => {
    console.error(error?.stack || error?.message || String(error));
    process.exitCode = 1;
  });
}
