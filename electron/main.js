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
}

/**
 * IPC: write the shared darkness ui-settings JSON to disk.
 * Renderer calls this after the user changes a theme via any future
 * theme-editor surface.
 */
ipcMain.handle("darkness:writeUiSettings", async (_event, json) => {
  const target = defaultDarknessSettingsPath();
  await fs.mkdir(path.dirname(target), { recursive: true });
  await fs.writeFile(target, json, "utf8");
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
