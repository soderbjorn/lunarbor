/* main.js — stub that hands control to the Kotlin/JS Node bundle.
 *
 * The real main-process logic (path resolution, atomic JSON writes,
 * IPC handlers, BrowserWindow setup, app menu, single-instance lock,
 * external-link handling, shared-themes file watcher, notegrow:*
 * file ops) lives in the :electron-main Gradle module and is compiled
 * to resources/main/ by `copyMainBundle`. */
require("./resources/main/Notegrow-electron-main.js");
