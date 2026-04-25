const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("noteApi", {
  ensureDirectory: (path) => ipcRenderer.invoke("notegrow:ensureDirectory", path),
  readFileIfExists: (path) => ipcRenderer.invoke("notegrow:readFileIfExists", path),
  writeFile: (path, content) => ipcRenderer.invoke("notegrow:writeFile", path, content),
  deleteFile: (path) => ipcRenderer.invoke("notegrow:deleteFile", path),
  deleteDirectoryIfEmpty: (path) => ipcRenderer.invoke("notegrow:deleteDirectoryIfEmpty", path),
  moveFile: (from, to) => ipcRenderer.invoke("notegrow:moveFile", from, to),
  moveDirectory: (from, to) => ipcRenderer.invoke("notegrow:moveDirectory", from, to),
  listDirectory: (path) => ipcRenderer.invoke("notegrow:listDirectory", path),
});
