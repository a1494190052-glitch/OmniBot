const serverStatus = document.querySelector('#serverStatus');
const formStatus = document.querySelector('#formStatus');
const createStatus = document.querySelector('#createStatus');
const editorStatus = document.querySelector('#editorStatus');
const storeRoot = document.querySelector('#storeRoot');
const projectList = document.querySelector('#projectList');
const fileList = document.querySelector('#fileList');
const createForm = document.querySelector('#createForm');
const importForm = document.querySelector('#importForm');
const editorPanel = document.querySelector('#editorPanel');
const validateButton = document.querySelector('#validateButton');
const refreshButton = document.querySelector('#refreshButton');
const rebuildButton = document.querySelector('#rebuildButton');
const cloneButton = document.querySelector('#cloneButton');
const removeSelectedButton = document.querySelector('#removeSelectedButton');
const manifestPath = document.querySelector('#manifestPath');
const packageRoot = document.querySelector('#packageRoot');
const createComponentId = document.querySelector('#createComponentId');
const createName = document.querySelector('#createName');
const createVersion = document.querySelector('#createVersion');
const createType = document.querySelector('#createType');
const createDescription = document.querySelector('#createDescription');
const createCapabilities = document.querySelector('#createCapabilities');
const createComponentJson = document.querySelector('#createComponentJson');
const editorTitle = document.querySelector('#editorTitle');
const editorSubtitle = document.querySelector('#editorSubtitle');
const editorDownload = document.querySelector('#editorDownload');
const editName = document.querySelector('#editName');
const editType = document.querySelector('#editType');
const editDescription = document.querySelector('#editDescription');
const editCapabilities = document.querySelector('#editCapabilities');
const editConfiguration = document.querySelector('#editConfiguration');
const editEntryContent = document.querySelector('#editEntryContent');
const entryLabel = document.querySelector('#entryLabel');
const cloneVersion = document.querySelector('#cloneVersion');

let selectedProject = null;

function setStatus(element, text, type = '') {
  element.textContent = text;
  element.classList.toggle('ok', type === 'ok');
  element.classList.toggle('error', type === 'error');
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: {
      'content-type': 'application/json',
      ...(options.headers || {}),
    },
    ...options,
  });
  const payload = await response.json();
  if (!response.ok) {
    const error = new Error(payload.error || `HTTP ${response.status}`);
    error.payload = payload;
    throw error;
  }
  return payload;
}

function formatError(error) {
  return error.payload?.validation?.stdout || error.payload?.error || error.message;
}

function importPayload() {
  return {
    manifestPath: manifestPath.value.trim(),
    packageRoot: packageRoot.value.trim() || undefined,
  };
}

function createPayload() {
  return {
    componentId: createComponentId.value.trim(),
    name: createName.value.trim(),
    version: createVersion.value.trim(),
    type: createType.value,
    description: createDescription.value.trim(),
    capabilities: createCapabilities.value,
    component: createComponentJson.value,
  };
}

function selectedIdentity() {
  if (!selectedProject) {
    throw new Error('Select a project first');
  }
  return {
    componentId: selectedProject.item.componentId,
    version: selectedProject.item.version,
  };
}

function renderFiles(files) {
  fileList.innerHTML = '';
  if (!files.length) {
    const empty = document.createElement('div');
    empty.className = 'empty compact';
    empty.textContent = 'No files';
    fileList.append(empty);
    return;
  }
  for (const file of files) {
    const row = document.createElement('div');
    row.className = 'fileRow';
    const path = document.createElement('span');
    path.textContent = file.path;
    const size = document.createElement('span');
    size.className = 'muted';
    size.textContent = `${file.size} B`;
    row.append(path, size);
    fileList.append(row);
  }
}

function fillEditor(details) {
  selectedProject = details;
  const { item, manifest, entry, files } = details;
  editorPanel.hidden = false;
  editorTitle.textContent = item.name || item.componentId;
  editorSubtitle.textContent = `${item.componentId}@${item.version}`;
  editorDownload.href = item.downloadUrl;
  editorDownload.download = `${item.componentId}-${item.version}.zip`;
  editName.value = manifest.name || '';
  editType.value = manifest.type || 'widget';
  editDescription.value = manifest.description || '';
  editCapabilities.value = (manifest.capabilities || []).join(', ');
  editConfiguration.value = JSON.stringify(manifest.configuration || {}, null, 2);
  entryLabel.textContent = entry?.path ? `Entry file: ${entry.path}` : 'Entry file';
  editEntryContent.disabled = !entry?.editable;
  editEntryContent.value = entry?.editable ? entry.content || '' : entry?.reason || 'Entry file is not editable';
  cloneVersion.value = suggestNextPatchVersion(item.version);
  renderFiles(files || []);
  setStatus(editorStatus, 'Ready', 'ok');
}

function suggestNextPatchVersion(version) {
  const match = String(version || '').match(/^(\d+)\.(\d+)\.(\d+)(.*)$/);
  if (!match) return '';
  return `${match[1]}.${match[2]}.${Number(match[3]) + 1}${match[4] || ''}`;
}

async function loadProjectDetails(componentId, version) {
  setStatus(editorStatus, 'Loading');
  const details = await api(
    `/api/projects/${encodeURIComponent(componentId)}/${encodeURIComponent(version)}`
  );
  fillEditor(details);
}

function renderProjects(items) {
  projectList.innerHTML = '';
  if (!items.length) {
    const empty = document.createElement('div');
    empty.className = 'empty';
    empty.textContent = 'No stored projects';
    projectList.append(empty);
    return;
  }

  for (const item of items) {
    const row = document.createElement('article');
    row.className = 'projectItem';

    const main = document.createElement('button');
    main.type = 'button';
    main.className = 'projectSummary';
    main.addEventListener('click', () => {
      loadProjectDetails(item.componentId, item.version).catch((error) => {
        setStatus(editorStatus, formatError(error), 'error');
      });
    });

    const title = document.createElement('div');
    title.className = 'projectTitle';
    const name = document.createElement('strong');
    name.textContent = item.name || item.componentId;
    const version = document.createElement('span');
    version.className = 'muted';
    version.textContent = `${item.componentId}@${item.version}`;
    title.append(name, version);

    const description = document.createElement('p');
    description.className = 'muted';
    description.textContent = item.description || 'No description';

    const meta = document.createElement('div');
    meta.className = 'projectMeta';
    const tags = [item.type, ...(item.capabilities || [])].filter(Boolean);
    for (const tagText of tags) {
      const tag = document.createElement('span');
      tag.className = 'tag';
      tag.textContent = tagText;
      meta.append(tag);
    }

    main.append(title, description, meta);

    const actions = document.createElement('div');
    actions.className = 'projectActions';
    const edit = document.createElement('button');
    edit.type = 'button';
    edit.textContent = 'Edit';
    edit.addEventListener('click', () => {
      loadProjectDetails(item.componentId, item.version).catch((error) => {
        setStatus(editorStatus, formatError(error), 'error');
      });
    });

    const download = document.createElement('a');
    download.className = 'buttonLink';
    download.href = item.downloadUrl;
    download.download = `${item.componentId}-${item.version}.zip`;
    download.textContent = 'Download';

    actions.append(edit, download);
    row.append(main, actions);
    projectList.append(row);
  }
}

async function loadProjects() {
  const payload = await api('/api/projects');
  storeRoot.textContent = payload.storeRoot;
  renderProjects(payload.items || []);
  return payload.items || [];
}

async function checkHealth() {
  try {
    await api('/api/health');
    setStatus(serverStatus, 'Online', 'ok');
  } catch (error) {
    setStatus(serverStatus, 'Offline', 'error');
  }
}

createForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const submit = createForm.querySelector('button[type="submit"]');
  submit.disabled = true;
  setStatus(createStatus, 'Creating project');
  try {
    const payload = await api('/api/projects/create', {
      method: 'POST',
      body: JSON.stringify(createPayload()),
    });
    setStatus(createStatus, `${payload.item.componentId}@${payload.item.version} created`, 'ok');
    await loadProjects();
    await loadProjectDetails(payload.item.componentId, payload.item.version);
  } catch (error) {
    setStatus(createStatus, formatError(error), 'error');
  } finally {
    submit.disabled = false;
  }
});

validateButton.addEventListener('click', async () => {
  validateButton.disabled = true;
  setStatus(formStatus, 'Validating');
  try {
    const payload = await api('/api/validate', {
      method: 'POST',
      body: JSON.stringify(importPayload()),
    });
    const output = payload.validation?.stdout || 'Validation passed';
    setStatus(formStatus, output, 'ok');
  } catch (error) {
    setStatus(formStatus, formatError(error), 'error');
  } finally {
    validateButton.disabled = false;
  }
});

importForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const submit = importForm.querySelector('button[type="submit"]');
  submit.disabled = true;
  setStatus(formStatus, 'Building package');
  try {
    const payload = await api('/api/projects/import', {
      method: 'POST',
      body: JSON.stringify(importPayload()),
    });
    setStatus(formStatus, `${payload.item.componentId}@${payload.item.version} stored`, 'ok');
    await loadProjects();
    await loadProjectDetails(payload.item.componentId, payload.item.version);
  } catch (error) {
    setStatus(formStatus, formatError(error), 'error');
  } finally {
    submit.disabled = false;
  }
});

editorPanel.addEventListener('submit', async (event) => {
  event.preventDefault();
  const submit = editorPanel.querySelector('button[type="submit"]');
  submit.disabled = true;
  setStatus(editorStatus, 'Saving and building');
  try {
    const identity = selectedIdentity();
    const payload = await api('/api/projects/update', {
      method: 'POST',
      body: JSON.stringify({
        ...identity,
        manifestUpdates: {
          name: editName.value,
          type: editType.value,
          description: editDescription.value,
          capabilities: editCapabilities.value,
          configuration: editConfiguration.value,
        },
        entryContent: editEntryContent.disabled ? undefined : editEntryContent.value,
      }),
    });
    setStatus(editorStatus, `${payload.item.componentId}@${payload.item.version} saved`, 'ok');
    await loadProjects();
    await loadProjectDetails(payload.item.componentId, payload.item.version);
  } catch (error) {
    setStatus(editorStatus, formatError(error), 'error');
  } finally {
    submit.disabled = false;
  }
});

rebuildButton.addEventListener('click', async () => {
  rebuildButton.disabled = true;
  setStatus(editorStatus, 'Rebuilding');
  try {
    const payload = await api('/api/projects/rebuild', {
      method: 'POST',
      body: JSON.stringify(selectedIdentity()),
    });
    setStatus(editorStatus, `${payload.item.componentId}@${payload.item.version} rebuilt`, 'ok');
    await loadProjects();
    await loadProjectDetails(payload.item.componentId, payload.item.version);
  } catch (error) {
    setStatus(editorStatus, formatError(error), 'error');
  } finally {
    rebuildButton.disabled = false;
  }
});

cloneButton.addEventListener('click', async () => {
  cloneButton.disabled = true;
  setStatus(editorStatus, 'Cloning');
  try {
    const identity = selectedIdentity();
    const payload = await api('/api/projects/clone', {
      method: 'POST',
      body: JSON.stringify({
        ...identity,
        targetVersion: cloneVersion.value.trim(),
      }),
    });
    setStatus(editorStatus, `${payload.item.componentId}@${payload.item.version} cloned`, 'ok');
    await loadProjects();
    await loadProjectDetails(payload.item.componentId, payload.item.version);
  } catch (error) {
    setStatus(editorStatus, formatError(error), 'error');
  } finally {
    cloneButton.disabled = false;
  }
});

removeSelectedButton.addEventListener('click', async () => {
  removeSelectedButton.disabled = true;
  setStatus(editorStatus, 'Removing');
  try {
    const identity = selectedIdentity();
    await api('/api/projects/remove', {
      method: 'POST',
      body: JSON.stringify(identity),
    });
    selectedProject = null;
    editorPanel.hidden = true;
    await loadProjects();
  } catch (error) {
    setStatus(editorStatus, formatError(error), 'error');
  } finally {
    removeSelectedButton.disabled = false;
  }
});

refreshButton.addEventListener('click', async () => {
  refreshButton.disabled = true;
  try {
    await loadProjects();
  } finally {
    refreshButton.disabled = false;
  }
});

await checkHealth();
await loadProjects();
