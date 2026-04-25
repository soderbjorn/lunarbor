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

// Hand off the shared darkness ui-settings JSON, parsed out of the
// `--darkness-settings=...` argument that main.js packs into the
// BrowserWindow's additionalArguments. The Kotlin/JS bundle reads
// `globalThis.__darknessSettings` at boot to apply the theme.
const darknessArg = (process.argv || []).find(a => a && a.startsWith("--darkness-settings="));
if (darknessArg) {
  const value = decodeURIComponent(darknessArg.substring("--darkness-settings=".length));
  contextBridge.exposeInMainWorld("__darknessSettings", value);
}

contextBridge.exposeInMainWorld("darknessApi", {
  /** Persist UI settings JSON to the shared darkness location. */
  writeUiSettings: (json) => ipcRenderer.invoke("darkness:writeUiSettings", json),
  /** Read UI settings JSON from the shared darkness location, or null. */
  readUiSettings: () => ipcRenderer.invoke("darkness:readUiSettings"),
});
