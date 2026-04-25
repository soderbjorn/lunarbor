const { app, BrowserWindow, ipcMain } = require("electron");
const fs = require("fs/promises");
const path = require("path");

const APP_NAME = "Notegrow";
app.setName(APP_NAME);

if (!app.requestSingleInstanceLock()) {
  app.quit();
  return;
}

let mainWindow = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1024,
    height: 720,
    title: APP_NAME,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, "preload.js"),
    },
  });

  mainWindow.loadFile(path.join(__dirname, "resources", "web", "index.html"));
}

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
