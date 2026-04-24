const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("noteApi", {
  ensureDirectory: (path) => ipcRenderer.invoke("notegrow:ensureDirectory", path),
  readFileIfExists: (path) => ipcRenderer.invoke("notegrow:readFileIfExists", path),
  writeFile: (path, content) => ipcRenderer.invoke("notegrow:writeFile", path, content),
});
