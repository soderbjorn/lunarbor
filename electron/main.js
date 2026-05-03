const { app, BrowserWindow, ipcMain } = require("electron");
const fs = require("fs/promises");
const fsSync = require("fs");
const os = require("os");
const path = require("path");

const APP_NAME = "Notegrow";
app.setName(APP_NAME);

/**
 * Resolves the OS-conventional path of the shared darkness UI-settings
 * file. Mirrors the convention in `toolkit-store/jvmMain` so any Darkness
 * app on the machine sees the same theme state.
 *
 * @returns {string} absolute path to the shared ui-settings.json
 */
function defaultDarknessSettingsPath() {
  const home = os.homedir();
  if (process.platform === "darwin") {
    return path.join(home, "Library", "Application Support", "Darkness", "ui-settings.json");
  } else if (process.platform === "win32") {
    const appData = process.env.APPDATA || path.join(home, "AppData", "Roaming");
    return path.join(appData, "Darkness", "ui-settings.json");
  } else {
    const xdg = process.env.XDG_CONFIG_HOME && process.env.XDG_CONFIG_HOME.length > 0
      ? process.env.XDG_CONFIG_HOME
      : path.join(home, ".config");
    return path.join(xdg, "darkness", "ui-settings.json");
  }
}

/**
 * Reads the shared darkness ui-settings.json synchronously at startup,
 * returning the file contents as a string or null if absent/unreadable.
 * Synchronous so it can run before the BrowserWindow is created and
 * its value handed to the renderer via additionalArguments.
 *
 * @returns {string|null} file contents, or null
 */
function readDarknessSettingsSync() {
  try {
    return fsSync.readFileSync(defaultDarknessSettingsPath(), "utf8");
  } catch (err) {
    return null;
  }
}

/**
 * Resolves the per-app darkness layout-state path. Mirrors
 * `defaultAppLayoutStatePath(appName)` in `toolkit-store/jvmMain` so a
 * future jvm-side reader (test harness, CLI) sees the same file. Note
 * that — unlike ui-settings, which is shared across the Darkness app
 * family — layout-state is **per-app**: notegrow's pane tree has no
 * meaning to termtastic, and writes happen on every drag.
 *
 * @returns {string} absolute path to this app's layout-state.json
 */
function defaultAppLayoutStatePath() {
  const home = os.homedir();
  if (process.platform === "darwin") {
    return path.join(home, "Library", "Application Support", "Darkness", APP_NAME, "layout-state.json");
  } else if (process.platform === "win32") {
    const appData = process.env.APPDATA || path.join(home, "AppData", "Roaming");
    return path.join(appData, "Darkness", APP_NAME, "layout-state.json");
  } else {
    const xdg = process.env.XDG_CONFIG_HOME && process.env.XDG_CONFIG_HOME.length > 0
      ? process.env.XDG_CONFIG_HOME
      : path.join(home, ".config");
    return path.join(xdg, "darkness", APP_NAME.toLowerCase(), "layout-state.json");
  }
}

/**
 * Reads this app's layout-state.json synchronously at startup. Same
 * pattern as [readDarknessSettingsSync] — runs before the BrowserWindow
 * exists so the JSON can be packed into `additionalArguments` and
 * exposed to the renderer as `globalThis.__darknessLayoutState` before
 * any rendering happens.
 *
 * @returns {string|null} file contents, or null
 */
function readDarknessLayoutStateSync() {
  try {
    return fsSync.readFileSync(defaultAppLayoutStatePath(), "utf8");
  } catch (err) {
    return null;
  }
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
  return;
}

let mainWindow = null;

function createWindow() {
  // Read the shared darkness ui-settings JSON synchronously and pass it
  // to the renderer through `additionalArguments` so the preload script
  // can hand it off to the Kotlin/JS bundle as `globalThis.__darknessSettings`
  // before any rendering happens.
  const settingsJson = readDarknessSettingsSync();
  const layoutJson = readDarknessLayoutStateSync();
  const additionalArguments = [];
  if (settingsJson) {
    additionalArguments.push(`--darkness-settings=${encodeURIComponent(settingsJson)}`);
  }
  if (layoutJson) {
    additionalArguments.push(`--darkness-layout-state=${encodeURIComponent(layoutJson)}`);
  }

  mainWindow = new BrowserWindow({
    width: 1024,
    height: 720,
    title: APP_NAME,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, "preload.js"),
      additionalArguments,
    },
  });

  mainWindow.loadFile(path.join(__dirname, "resources", "web", "index.html"));

  installSharedThemesWatcher();
}

/**
 * Holds the active `fs.watch` handle so re-creating the window doesn't
 * leak watchers. `null` when no watcher is attached (or after teardown).
 *
 * @type {import('fs').FSWatcher|null}
 */
let sharedThemesWatcher = null;

/**
 * Coalesce timer for fs.watch — some editors fire `change` twice per save
 * (the file write itself plus a fsync), and a write+rename produces both
 * `rename` and `change` events. We collapse all events inside a 200ms
 * window into a single read+notify cycle.
 *
 * @type {NodeJS.Timeout|null}
 */
let sharedThemesDebounce = null;

/**
 * Installs an `fs.watch` on the shared darkness ui-settings file. On
 * each (debounced) change, re-reads the file and IPC-sends the JSON to
 * the renderer over the `darkness:uiSettingsChanged` channel — but only
 * when the bytes don't match what this Electron process itself last
 * wrote (so renderer-driven writes don't loop back as "external" change).
 *
 * Called once per BrowserWindow at construction time. Idempotent: closes
 * any prior watcher first.
 */
function installSharedThemesWatcher() {
  if (sharedThemesWatcher) {
    try { sharedThemesWatcher.close(); } catch (_) { /* already closed */ }
    sharedThemesWatcher = null;
  }
  const target = defaultDarknessSettingsPath();
  const dir = path.dirname(target);
  const fname = path.basename(target);
  try { fsSync.mkdirSync(dir, { recursive: true }); } catch (_) { /* dir already exists */ }
  try {
    sharedThemesWatcher = fsSync.watch(dir, (_eventType, changedName) => {
      if (changedName !== fname) return;
      if (sharedThemesDebounce) clearTimeout(sharedThemesDebounce);
      sharedThemesDebounce = setTimeout(() => {
        sharedThemesDebounce = null;
        let bytes;
        try {
          bytes = fsSync.readFileSync(target);
        } catch (err) {
          // File may have been transiently absent during a rename; ignore.
          return;
        }
        if (lastWrittenBytes && bytes.equals(lastWrittenBytes)) return;
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.webContents.send("darkness:uiSettingsChanged", bytes.toString("utf8"));
        }
      }, 200);
    });
  } catch (err) {
    // Some filesystems / sandbox configs reject fs.watch — in that case
    // the renderer simply won't get live updates. Boot-time read still works.
    sharedThemesWatcher = null;
  }
}

/**
 * Tracks the bytes most recently written by this Electron process so the
 * file-watch handler installed below doesn't bounce on its own writes.
 * Compared byte-for-byte with the freshly-read file on every event;
 * mismatch means a different writer (another Darkness app, a manual edit)
 * touched the file and the renderer should be notified.
 *
 * @type {Buffer|null}
 */
let lastWrittenBytes = null;

/**
 * IPC: write the shared darkness ui-settings JSON to disk **atomically**.
 * Writes to `<path>.tmp` first, then renames into place — concurrent
 * readers (this Electron process's own file-watch, the termtastic server,
 * future Darkness apps) never see a partial write.
 *
 * Renderer calls this after the user changes a theme via any future
 * theme-editor surface.
 */
ipcMain.handle("darkness:writeUiSettings", async (_event, json) => {
  const target = defaultDarknessSettingsPath();
  await fs.mkdir(path.dirname(target), { recursive: true });
  const tmp = target + ".tmp";
  const bytes = Buffer.from(json, "utf8");
  await fs.writeFile(tmp, bytes);
  await fs.rename(tmp, target);
  lastWrittenBytes = bytes;
});

/**
 * IPC: read the shared darkness ui-settings JSON. Returns null if the
 * file doesn't exist (first launch).
 */
ipcMain.handle("darkness:readUiSettings", async () => {
  try {
    return await fs.readFile(defaultDarknessSettingsPath(), "utf8");
  } catch (err) {
    if (err && err.code === "ENOENT") return null;
    throw err;
  }
});

/**
 * Tracks the bytes most recently written by this Electron process to
 * the layout-state file, used by [installLayoutStateWatcher] for
 * self-write suppression. Independent buffer from [lastWrittenBytes]
 * because the two files have different write cadences.
 *
 * @type {Buffer|null}
 */
let lastWrittenLayoutBytes = null;

/**
 * IPC: write this app's layout-state JSON to disk **atomically** in the
 * per-app subdir. Mirrors [darkness:writeUiSettings] verbatim — same
 * tmp+rename pattern, same self-write suppression hook — but targets
 * the per-app `layout-state.json` instead of the shared `ui-settings.json`.
 *
 * Renderer calls this on every drag-resize end, tab close, pane
 * close/expand, and similar layout mutations. Writes are debounced
 * renderer-side; this handler does no extra throttling.
 */
ipcMain.handle("darkness:writeLayoutState", async (_event, json) => {
  const target = defaultAppLayoutStatePath();
  await fs.mkdir(path.dirname(target), { recursive: true });
  const tmp = target + ".tmp";
  const bytes = Buffer.from(json, "utf8");
  await fs.writeFile(tmp, bytes);
  await fs.rename(tmp, target);
  lastWrittenLayoutBytes = bytes;
});

/**
 * IPC: read this app's layout-state JSON. Returns null if the file
 * doesn't exist (first launch). Matches [darkness:readUiSettings]'s
 * shape so the renderer's preload bridge stays symmetric.
 */
ipcMain.handle("darkness:readLayoutState", async () => {
  try {
    return await fs.readFile(defaultAppLayoutStatePath(), "utf8");
  } catch (err) {
    if (err && err.code === "ENOENT") return null;
    throw err;
  }
});

ipcMain.handle("notegrow:ensureDirectory", async (_event, dirPath) => {
  await fs.mkdir(dirPath, { recursive: true });
});

ipcMain.handle("notegrow:readFileIfExists", async (_event, filePath) => {
  try {
    return await fs.readFile(filePath, "utf8");
  } catch (err) {
    if (err && err.code === "ENOENT") return null;
    throw err;
  }
});

ipcMain.handle("notegrow:writeFile", async (_event, filePath, content) => {
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  await fs.writeFile(filePath, content, "utf8");
});

ipcMain.handle("notegrow:deleteFile", async (_event, filePath) => {
  try {
    await fs.unlink(filePath);
  } catch (err) {
    if (err && err.code === "ENOENT") return;
    throw err;
  }
});

ipcMain.handle("notegrow:deleteDirectoryIfEmpty", async (_event, dirPath) => {
  try {
    await fs.rmdir(dirPath);
  } catch (err) {
    if (err && (err.code === "ENOENT" || err.code === "ENOTEMPTY" || err.code === "EEXIST")) return;
    throw err;
  }
});

ipcMain.handle("notegrow:moveFile", async (_event, from, to) => {
  await fs.mkdir(path.dirname(to), { recursive: true });
  await fs.rename(from, to);
});

ipcMain.handle("notegrow:moveDirectory", async (_event, from, to) => {
  await fs.mkdir(path.dirname(to), { recursive: true });
  await fs.rename(from, to);
});

ipcMain.handle("notegrow:listDirectory", async (_event, dirPath) => {
  try {
    return await fs.readdir(dirPath);
  } catch (err) {
    if (err && err.code === "ENOENT") return [];
    throw err;
  }
});

ipcMain.handle("notegrow:listDirectoryEntries", async (_event, dirPath) => {
  try {
    const entries = await fs.readdir(dirPath, { withFileTypes: true });
    return entries.map((e) => ({ name: e.name, isDirectory: e.isDirectory() }));
  } catch (err) {
    if (err && err.code === "ENOENT") return [];
    throw err;
  }
});

app.on("second-instance", () => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.focus();
  }
});

app.whenReady().then(createWindow);

app.on("window-all-closed", () => {
  app.quit();
});
