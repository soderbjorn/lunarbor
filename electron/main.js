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
