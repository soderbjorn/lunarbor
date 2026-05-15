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
  listDirectoryEntries: (path) => ipcRenderer.invoke("notegrow:listDirectoryEntries", path),
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

// Hand off the per-app layout-state JSON the same way. Read at boot via
// `globalThis.__darknessLayoutState`, parsed by `LayoutState.fromJsonString`
// in toolkit-store. Absence of the global means "no persisted state yet"
// and the app should fall through to `LayoutState.defaults()`.
const layoutArg = (process.argv || []).find(a => a && a.startsWith("--darkness-layout-state="));
if (layoutArg) {
  const value = decodeURIComponent(layoutArg.substring("--darkness-layout-state=".length));
  contextBridge.exposeInMainWorld("__darknessLayoutState", value);
}

// Same boot-snapshot mechanism for the toolkit-owned layout state
// (per-tab pane geometry, layout preset, paneOrder — all under
// `PersistKeys.LAYOUT_STATE`). Distinct from `__darknessLayoutState`,
// which carries notegrow's typed tab list under `PersistKeys.LAYOUT`.
const layoutToolkitArg = (process.argv || []).find(a => a && a.startsWith("--darkness-layout-toolkit-state="));
if (layoutToolkitArg) {
  const value = decodeURIComponent(layoutToolkitArg.substring("--darkness-layout-toolkit-state=".length));
  contextBridge.exposeInMainWorld("__darknessLayoutToolkitState", value);
}

contextBridge.exposeInMainWorld("darknessApi", {
  /** Persist UI settings JSON to the shared darkness location. */
  writeUiSettings: (json) => ipcRenderer.invoke("darkness:writeUiSettings", json),
  /** Read UI settings JSON from the shared darkness location, or null. */
  readUiSettings: () => ipcRenderer.invoke("darkness:readUiSettings"),
  /**
   * Subscribe to external changes of the shared ui-settings file. The
   * callback receives the freshly-read JSON string. Self-writes from
   * this Electron process are filtered out by `main.js`. Returns an
   * unsubscribe function.
   *
   * @param {(json: string) => void} cb invoked once per external change
   * @returns {() => void} unsubscribe
   */
  onUiSettingsChanged: (cb) => {
    const handler = (_event, json) => { try { cb(json); } catch (_) { /* swallow */ } };
    ipcRenderer.on("darkness:uiSettingsChanged", handler);
    return () => ipcRenderer.removeListener("darkness:uiSettingsChanged", handler);
  },

  /**
   * Persist the per-app layout-state JSON atomically to disk. Renderer
   * calls this on every layout mutation (drag-resize end, tab close,
   * pane expand/restore, etc.). Renderer-side debouncing is the renderer's
   * concern; this bridge is fire-and-forget atomic write.
   *
   * @param {string} json a complete layout-state JSON document
   */
  writeLayoutState: (json) => ipcRenderer.invoke("darkness:writeLayoutState", json),
  /**
   * Read the per-app layout-state JSON, or null on first launch. Note
   * that `globalThis.__darknessLayoutState` already carries the boot
   * snapshot — this IPC read is for late re-loads only (e.g. an explicit
   * "discard local changes" reset gesture).
   *
   * @returns {Promise<string|null>} the JSON, or null
   */
  readLayoutState: () => ipcRenderer.invoke("darkness:readLayoutState"),

  /**
   * Persist the toolkit-owned layout state JSON (per-tab pane geometry,
   * layout preset, paneOrder — see `PersistedLayoutState` in
   * `toolkit-web`). Stored separately from `writeLayoutState` because
   * notegrow's typed tab list (`LAYOUT`) and the toolkit's geometry
   * snapshot (`LAYOUT_STATE`) have independent shapes and lifecycles.
   *
   * @param {string} json a complete LAYOUT_STATE JSON document
   */
  writeLayoutToolkitState: (json) => ipcRenderer.invoke("darkness:writeLayoutToolkitState", json),
  /**
   * Read the toolkit-owned layout state JSON, or null on first launch.
   * `globalThis.__darknessLayoutToolkitState` carries the boot snapshot;
   * this IPC read is for late re-loads only.
   *
   * @returns {Promise<string|null>} the JSON, or null
   */
  readLayoutToolkitState: () => ipcRenderer.invoke("darkness:readLayoutToolkitState"),

  /**
   * Toggle the custom (themed) title bar on the Electron main window.
   *
   * `titleBarStyle` is a creation-time BrowserWindow option in Electron
   * and cannot be mutated on an existing window, so the main process
   * destroys the current window and creates a new one with the requested
   * style. All renderer state reloads from disk (themes, layout, notes),
   * so the reload is purely visual.
   *
   * The value is cached in `<userData>/electron-chrome.json` so the next
   * cold start opens the window with the right chrome without a round
   * trip to the renderer.
   *
   * Called by the toolkit's renderer subscriber (`AppShellMount`) when
   * the user toggles the setting in the Settings sidebar.
   *
   * @param {boolean} enabled `true` to hide the native title bar and
   *   render the themed window chrome, `false` to show the native OS
   *   title bar.
   * @returns {Promise<void>}
   */
  setCustomTitleBar: (enabled) => ipcRenderer.invoke("darkness:setCustomTitleBar", enabled),

  /**
   * Subscribe to "show hotkeys" requests dispatched from the application
   * menu (macOS: Notegrow → Hotkeys…). The callback is invoked once per
   * menu activation. Returns an unsubscribe function.
   *
   * @param {() => void} cb invoked once per menu activation
   * @returns {() => void} unsubscribe
   */
  onShowHotkeys: (cb) => {
    const handler = () => { try { cb(); } catch (_) { /* swallow */ } };
    ipcRenderer.on("notegrow:show-hotkeys", handler);
    return () => ipcRenderer.removeListener("notegrow:show-hotkeys", handler);
  },

  /**
   * Subscribe to native macOS fullscreen state changes on the current
   * BrowserWindow. The callback receives `true` on `enter-full-screen`,
   * `false` on `leave-full-screen`, and once at boot reflecting the
   * window's initial fullscreen state (macOS may relaunch directly into
   * a restored fullscreen Space).
   *
   * Used by the renderer to toggle the toolkit's `dt-mac-fullscreen`
   * body class via `setDtMacFullscreenBodyClass`, which suppresses the
   * 80 px traffic-light reservation on `.dt-topbar` for the duration of
   * the fullscreen state (the OS hides the traffic-light cluster).
   *
   * @param {(enabled: boolean) => void} cb
   * @returns {() => void} unsubscribe
   */
  onFullscreenChange: (cb) => {
    const handler = (_event, enabled) => {
      try { cb(enabled === true); } catch (_) { /* swallow */ }
    };
    ipcRenderer.on("fullscreen-changed", handler);
    return () => ipcRenderer.removeListener("fullscreen-changed", handler);
  },
});
