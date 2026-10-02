const { contextBridge, ipcRenderer } = require("electron");

// Vault root resolved by the main process from LUNARBOR_VAULT /
// LUNARBOR_LOCAL_DATA (default ~/lunarbor-db), passed as
// `--lunarbor-vault=<encoded>` and exposed as `noteApi.vaultRoot`.
// JsAppGraph hands it to NoteRepository; the renderer never guesses it.
const vaultArg = (process.argv || []).find(a => a && a.startsWith("--lunarbor-vault="));
const vaultRoot = vaultArg
  ? decodeURIComponent(vaultArg.substring("--lunarbor-vault=".length))
  : null;

contextBridge.exposeInMainWorld("noteApi", {
  /** Absolute vault root for this run, or null if the main process sent none. */
  vaultRoot,
  ensureDirectory: (path) => ipcRenderer.invoke("lunarbor:ensureDirectory", path),
  readFileIfExists: (path) => ipcRenderer.invoke("lunarbor:readFileIfExists", path),
  writeFile: (path, content) => ipcRenderer.invoke("lunarbor:writeFile", path, content),
  writeBinary: (path, bytes) => ipcRenderer.invoke("lunarbor:writeBinary", path, bytes),
  readBinary: (path) => ipcRenderer.invoke("lunarbor:readBinary", path),
  fileSize: (path) => ipcRenderer.invoke("lunarbor:fileSize", path),
  deleteFile: (path) => ipcRenderer.invoke("lunarbor:deleteFile", path),
  deleteDirectoryIfEmpty: (path) => ipcRenderer.invoke("lunarbor:deleteDirectoryIfEmpty", path),
  moveFile: (from, to) => ipcRenderer.invoke("lunarbor:moveFile", from, to),
  moveDirectory: (from, to) => ipcRenderer.invoke("lunarbor:moveDirectory", from, to),
  listDirectory: (path) => ipcRenderer.invoke("lunarbor:listDirectory", path),
  listDirectoryEntries: (path) => ipcRenderer.invoke("lunarbor:listDirectoryEntries", path),
  /**
   * Opens a vault file in the system's default app (TRF-7). Takes a
   * vault-relative path; the main process resolves it and refuses paths
   * outside the vault. Resolves to "" on success, else an error message.
   */
  openPath: (pathRel) => ipcRenderer.invoke("lunarbor:openPath", pathRel),
  /**
   * Shows a vault entry selected in its folder in Finder (folder-entry
   * menu's "Reveal in Finder"). Vault-relative; refused outside the vault.
   */
  revealPath: (pathRel) => ipcRenderer.invoke("lunarbor:revealPath", pathRel),
  /**
   * The current vault: `{ path, locked }`. `locked` is true when
   * LUNARBOR_VAULT pinned it, so App settings cannot change it.
   */
  getVault: () => ipcRenderer.invoke("lunarbor:getVault"),
  /** Native folder picker for a new vault. Resolves to the path, or null if cancelled. */
  chooseVaultFolder: () => ipcRenderer.invoke("lunarbor:chooseVaultFolder"),
  /**
   * Persists `dir` as the vault and reopens the window against it.
   * Resolves to "" on success, else an error message. The renderer must
   * have flushed every edit first.
   */
  setVault: (dir) => ipcRenderer.invoke("lunarbor:setVault", dir),
  /**
   * Subscribe to changes made to the vault outside the app. The callback
   * receives an array of absolute paths (files and folders); the app's
   * own writes are filtered out by the main process (VaultWatcher.kt).
   * Returns an unsubscribe function.
   */
  onVaultChanged: (cb) => {
    const handler = (_event, paths) => { try { cb(paths); } catch (_) { /* swallow */ } };
    ipcRenderer.on("lunarbor:vaultChanged", handler);
    return () => ipcRenderer.removeListener("lunarbor:vaultChanged", handler);
  },
  /**
   * App settings → Agent access (McpHttpServer.kt). `getMcp` resolves to
   * `{ enabled, port, url, running, error, connections }`, each connection
   * `{ id, name, key, folder, allowEdits }`; `setMcp` takes a patch of
   * `{ enabled, port }`. `addMcpConnection({ name, folder, allowEdits })`,
   * `updateMcpConnection({ id, name?, folder?, allowEdits? })`,
   * `removeMcpConnection(id)` and `newMcpKey(id)` (agents set up with the
   * old key stop working) all resolve to the new status.
   * `chooseMcpFolder(current)` opens a folder chooser in the vault and
   * resolves to `{ folder }` (vault-relative), `{ error }` or `{}`.
   */
  getMcp: () => ipcRenderer.invoke("lunarbor:getMcp"),
  setMcp: (patch) => ipcRenderer.invoke("lunarbor:setMcp", patch),
  addMcpConnection: (spec) => ipcRenderer.invoke("lunarbor:addMcpConnection", spec),
  updateMcpConnection: (patch) => ipcRenderer.invoke("lunarbor:updateMcpConnection", patch),
  removeMcpConnection: (id) => ipcRenderer.invoke("lunarbor:removeMcpConnection", id),
  newMcpKey: (id) => ipcRenderer.invoke("lunarbor:newMcpKey", id),
  chooseMcpFolder: (current) => ipcRenderer.invoke("lunarbor:chooseMcpFolder", current),
  /**
   * App settings → Backup (VaultBackup.kt). Each resolves to the status
   * `{ folder, intervalHours, running, error, lastBackupName, lastBackupMs }`.
   * `setBackup` takes `{ intervalHours }`; `chooseBackupFolder` opens a
   * folder picker; `backupNow` zips the vault (writes wait meanwhile) and
   * resolves when it is done.
   */
  getBackup: () => ipcRenderer.invoke("lunarbor:getBackup"),
  setBackup: (patch) => ipcRenderer.invoke("lunarbor:setBackup", patch),
  chooseBackupFolder: () => ipcRenderer.invoke("lunarbor:chooseBackupFolder"),
  backupNow: () => ipcRenderer.invoke("lunarbor:backupNow"),
  /**
   * Answer automatic backups: `cb()` is called when one is due; it saves
   * pending edits and calls `backupNow`. Subscribing tells the main
   * process this window answers (and lets it check the schedule at once).
   */
  onBackupDue: (cb) => {
    ipcRenderer.on("lunarbor:backupDue", () => { try { cb(); } catch (e) { console.error("[lunarbor] backup failed", e); } });
    ipcRenderer.invoke("lunarbor:backupReady");
  },
  /** Status pushes (backup started / finished). Returns an unsubscribe function. */
  onBackupStatus: (cb) => {
    const handler = (_event, status) => { try { cb(status); } catch (_) { /* swallow */ } };
    ipcRenderer.on("lunarbor:backupStatus", handler);
    return () => ipcRenderer.removeListener("lunarbor:backupStatus", handler);
  },
  /**
   * Answer MCP requests: `cb(body, allowEdits, folder)` resolves to the
   * JSON response, or null when nothing is to be sent back. `allowEdits`
   * and `folder` (vault-relative, "" for the whole vault) are those of the
   * connection whose key the request carried. Subscribing tells the main
   * process this window answers; there is one subscriber.
   */
  serveMcp: (cb) => {
    ipcRenderer.on("lunarbor:mcpRequest", async (_event, id, body, allowEdits, folder) => {
      let answer = null;
      try { answer = await cb(body, allowEdits, folder || ""); } catch (e) { console.error("[lunarbor] MCP request failed", e); }
      ipcRenderer.invoke("lunarbor:mcpResponse", { id, body: answer });
    });
    ipcRenderer.invoke("lunarbor:mcpReady");
  },
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
// which carries lunarbor's typed tab list under `PersistKeys.LAYOUT`.
const layoutToolkitArg = (process.argv || []).find(a => a && a.startsWith("--darkness-layout-toolkit-state="));
if (layoutToolkitArg) {
  const value = decodeURIComponent(layoutToolkitArg.substring("--darkness-layout-toolkit-state=".length));
  contextBridge.exposeInMainWorld("__darknessLayoutToolkitState", value);
}

// Authoritative window-chrome flag passed by main.js, sourced from the
// cached `electron-chrome.json`. The toolkit's
// `autoApplyCustomTitleBarBodyClass` reads this to toggle the
// `dt-custom-titlebar` body class synchronously, so the 80 px
// traffic-light reservation on `.dt-topbar` applies on the very first
// frame (the stock `ElectronIpcPersister` doesn't round-trip
// THEME_SNAPSHOT, so the async snapshot read can't deliver this).
const customTitleBarArg = (process.argv || []).find(a => a && a.startsWith("--darkness-custom-titlebar="));
const customTitleBarBoot = customTitleBarArg
  ? customTitleBarArg.substring("--darkness-custom-titlebar=".length) === "true"
  : false;

contextBridge.exposeInMainWorld("darknessApi", {
  /**
   * Boot-time custom-titlebar flag from the main process's
   * `electron-chrome.json` cache. Consumed by darkness-toolkit's
   * `autoApplyCustomTitleBarBodyClass` to set `dt-custom-titlebar`
   * before the async persister read completes.
   *
   * @type {boolean}
   */
  customTitleBar: customTitleBarBoot,
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
   * lunarbor's typed tab list (`LAYOUT`) and the toolkit's geometry
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
   * menu (macOS: Lunarbor → Hotkeys…). The callback is invoked once per
   * menu activation. Returns an unsubscribe function.
   *
   * @param {() => void} cb invoked once per menu activation
   * @returns {() => void} unsubscribe
   */
  onShowHotkeys: (cb) => {
    const handler = () => { try { cb(); } catch (_) { /* swallow */ } };
    ipcRenderer.on("lunarbor:show-hotkeys", handler);
    return () => ipcRenderer.removeListener("lunarbor:show-hotkeys", handler);
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
