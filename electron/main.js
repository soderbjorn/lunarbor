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
  const additionalArguments = settingsJson
    ? [`--darkness-settings=${encodeURIComponent(settingsJson)}`]
    : [];

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
