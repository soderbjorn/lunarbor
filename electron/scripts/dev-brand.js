#!/usr/bin/env node
/* dev-brand.js — dev-only branding of the stock Electron.app bundle.
 *
 * When you run the app with `electron .` (dev), the binary that actually
 * launches is node_modules/electron/dist/Electron.app — the stock,
 * unbranded Electron bundle. macOS derives the app's identity (menu-bar
 * name, Cmd-Tab switcher, and Dock tooltip) from that bundle, so dev
 * always showed "Electron". `app.setName()` can't fix it: it changes
 * Electron's internal name, not what macOS reads off the bundle.
 *
 * Getting every surface to say "Lunarbor" in dev requires patching four
 * distinct things — miss any one and something still says "Electron":
 *   1. The `.app` FOLDER name (Electron.app → Lunarbor.app). The Dock
 *      tooltip for a *running* app comes from the bundle folder name, and
 *      CFBundleDisplayName does NOT override it. This was the last hold-out.
 *   2. The Mach-O EXECUTABLE name (Contents/MacOS/Electron → …/Lunarbor),
 *      which is the process name (Cmd-Tab / `ps`).
 *   3. The Info.plist keys: CFBundleName, CFBundleDisplayName,
 *      CFBundleExecutable, and a UNIQUE CFBundleIdentifier — the stock
 *      `com.github.Electron` is shared by every Electron dev app on the
 *      machine, and LaunchServices keys by bundle id, so the collision
 *      makes macOS fall back to a cached "Electron" name.
 *   4. The icon (CFBundleIconFile → our icns).
 * Plus two side effects of touching a signed bundle: an ad-hoc re-sign
 * (or Apple Silicon refuses to launch it) and a LaunchServices refresh
 * (or the Dock keeps the stale cached name).
 *
 * It runs automatically before `npm start` (the `prestart` hook) and is:
 *   - macOS-only (no-op elsewhere; other platforms brand via the
 *     BrowserWindow `icon` option in ElectronMain.kt),
 *   - idempotent (skips fast once already branded, so `npm start` stays
 *     snappy),
 *   - self-healing (a fresh `npm install` restores the stock bundle;
 *     the next `npm start` re-brands it — including re-renaming the
 *     folder/executable, whatever their current names),
 *   - best-effort (any failure just falls back to the "Electron" name;
 *     it never blocks dev).
 *
 * The packaged (electron-builder) build is unaffected — it already ships
 * the correct name (`productName: "Lunarbor"`) and icon (`icons/icon.icns`).
 */
const { execFileSync } = require("child_process");
const fs = require("fs");
const path = require("path");

if (process.platform !== "darwin") process.exit(0);

const APP_NAME = "Lunarbor";
const APP_BUNDLE_ID = "se.soderbjorn.lunarbor.dev";
const ICNS_NAME = "lunarbor.icns";

const appDir = path.resolve(__dirname, "..");
const electronPkgDir = path.join(appDir, "node_modules", "electron");
const distDir = path.join(electronPkgDir, "dist");
const brandedApp = path.join(distDir, `${APP_NAME}.app`);
const srcIcns = path.join(appDir, "icons", "icon.icns");
// The `electron` launcher resolves the binary to spawn from path.txt,
// relative to dist/. It must point at the renamed bundle + executable.
const pathTxt = path.join(electronPkgDir, "path.txt");

const PLIST_BUDDY = "/usr/libexec/PlistBuddy";
const LSREGISTER =
  "/System/Library/Frameworks/CoreServices.framework/Versions/A/Frameworks/" +
  "LaunchServices.framework/Versions/A/Support/lsregister";

/** Absolute path to Info.plist inside a given `.app` bundle. */
const plistOf = (appPath) => path.join(appPath, "Contents", "Info.plist");

function plistRead(appPath, key) {
  try {
    return execFileSync(PLIST_BUDDY, ["-c", `Print :${key}`, plistOf(appPath)], {
      encoding: "utf8",
    }).trim();
  } catch {
    return "";
  }
}

function plistSet(appPath, key, value) {
  try {
    execFileSync(PLIST_BUDDY, ["-c", `Set :${key} ${value}`, plistOf(appPath)]);
  } catch {
    execFileSync(PLIST_BUDDY, ["-c", `Add :${key} string ${value}`, plistOf(appPath)]);
  }
}

/**
 * Locate the current Electron `.app` in dist/. Prefers an already-branded
 * bundle, then the stock `Electron.app`, then any lone `*.app` (covers a
 * bundle left over from a previous project name). Returns null if none —
 * e.g. electron isn't installed yet.
 */
function resolveCurrentApp() {
  if (fs.existsSync(brandedApp)) return brandedApp;
  const stock = path.join(distDir, "Electron.app");
  if (fs.existsSync(stock)) return stock;
  if (!fs.existsSync(distDir)) return null;
  const apps = fs.readdirSync(distDir).filter((n) => n.endsWith(".app"));
  return apps.length === 1 ? path.join(distDir, apps[0]) : null;
}

const currentApp = resolveCurrentApp();
if (!currentApp) process.exit(0); // electron not installed yet

// Fast path: already fully branded (folder, executable, and every plist
// key), nothing to do. Checking the branded folder + executable on disk —
// not just plist keys — means a half-finished prior run self-heals.
const brandedExec = path.join(brandedApp, "Contents", "MacOS", APP_NAME);
if (
  currentApp === brandedApp &&
  fs.existsSync(brandedExec) &&
  plistRead(brandedApp, "CFBundleExecutable") === APP_NAME &&
  plistRead(brandedApp, "CFBundleName") === APP_NAME &&
  plistRead(brandedApp, "CFBundleDisplayName") === APP_NAME &&
  plistRead(brandedApp, "CFBundleIdentifier") === APP_BUNDLE_ID
) {
  process.exit(0);
}

try {
  // 1. Rename the .app FOLDER → drives the Dock tooltip of a running app.
  if (currentApp !== brandedApp) {
    fs.renameSync(currentApp, brandedApp);
  }

  const macosDir = path.join(brandedApp, "Contents", "MacOS");
  const resourcesDir = path.join(brandedApp, "Contents", "Resources");

  // 2. Rename the Mach-O EXECUTABLE → drives the process / Cmd-Tab name.
  //    Don't assume its current name (stock "Electron", or a prior brand):
  //    there is exactly one file in Contents/MacOS — rename it.
  if (!fs.existsSync(brandedExec)) {
    const execs = fs
      .readdirSync(macosDir)
      .filter((n) => fs.statSync(path.join(macosDir, n)).isFile());
    if (execs.length !== 1) {
      throw new Error(
        `expected exactly one executable in ${macosDir}, found: ${execs.join(", ") || "none"}`
      );
    }
    fs.renameSync(path.join(macosDir, execs[0]), brandedExec);
  }

  // 3. Info.plist identity keys.
  plistSet(brandedApp, "CFBundleExecutable", APP_NAME);
  plistSet(brandedApp, "CFBundleName", APP_NAME);
  plistSet(brandedApp, "CFBundleDisplayName", APP_NAME);
  plistSet(brandedApp, "CFBundleIdentifier", APP_BUNDLE_ID);

  // 4. Icon.
  if (fs.existsSync(srcIcns)) {
    fs.copyFileSync(srcIcns, path.join(resourcesDir, ICNS_NAME));
    plistSet(brandedApp, "CFBundleIconFile", ICNS_NAME);
  }

  // Repoint the `electron` launcher at the renamed bundle + executable —
  // only now that both are guaranteed to exist, so path.txt never dangles.
  if (fs.existsSync(pathTxt)) {
    fs.writeFileSync(pathTxt, `${APP_NAME}.app/Contents/MacOS/${APP_NAME}`);
  }

  // Editing the bundle invalidates its code signature. On Apple Silicon
  // macOS refuses to launch an app whose signature no longer matches, so
  // re-seal it ad-hoc. Best-effort: warn but continue if it fails.
  try {
    execFileSync("codesign", ["--force", "--sign", "-", brandedApp], { stdio: "ignore" });
  } catch (e) {
    console.warn(`[dev-brand] ad-hoc re-sign failed (dev may still launch): ${e.message}`);
  }

  // Force LaunchServices to re-read the bundle so the new name/icon take
  // effect and the stale "Electron" registration is dropped.
  try {
    execFileSync(LSREGISTER, ["-f", brandedApp], { stdio: "ignore" });
  } catch (e) {
    console.warn(`[dev-brand] LaunchServices refresh failed: ${e.message}`);
  }

  console.log(`[dev-brand] Branded dev Electron bundle as "${APP_NAME}".`);
} catch (e) {
  console.warn(`[dev-brand] Could not brand dev bundle: ${e.message}`);
}
