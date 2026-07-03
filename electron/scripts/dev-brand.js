#!/usr/bin/env node
/* dev-brand.js — dev-only branding of the stock Electron.app bundle.
 *
 * When you run the app with `electron .` (dev), macOS reads the app
 * name and icon for the menu-bar app menu, the Cmd-Tab switcher, and
 * the Dock from the *running bundle's* Info.plist — which is
 * node_modules/electron/dist/Electron.app, i.e. "Electron". `app.setName()`
 * cannot change that at runtime, so dev always showed "Electron".
 *
 * This script patches that dev bundle in place to say "TreeFacts" and use
 * our icon. It runs automatically before `npm start` (see the `prestart`
 * hook in package.json) and is:
 *   - macOS-only (no-op elsewhere; other platforms brand via the
 *     BrowserWindow `icon` option in ElectronMain.kt),
 *   - idempotent (skips fast once already branded, so `npm start` stays
 *     snappy),
 *   - self-healing (a fresh `npm install` restores the stock bundle;
 *     the next `npm start` re-patches it),
 *   - best-effort (any failure just falls back to the "Electron" name;
 *     it never blocks dev).
 *
 * The packaged (electron-builder) build is unaffected — it already ships
 * the correct name (`productName: "TreeFacts"`) and icon (`build/icon.icns`).
 */
const { execFileSync } = require("child_process");
const fs = require("fs");
const path = require("path");

if (process.platform !== "darwin") process.exit(0);

const APP_NAME = "TreeFacts";
const appDir = path.resolve(__dirname, "..");
const electronPkgDir = path.join(appDir, "node_modules", "electron");
const electronApp = path.join(electronPkgDir, "dist", "Electron.app");
const plist = path.join(electronApp, "Contents", "Info.plist");
const resourcesDir = path.join(electronApp, "Contents", "Resources");
const macosDir = path.join(electronApp, "Contents", "MacOS");
const stockExec = path.join(macosDir, "Electron");
const brandedExec = path.join(macosDir, APP_NAME);
const srcIcns = path.join(appDir, "build", "icon.icns");
// The `electron` launcher resolves the binary to spawn from path.txt;
// it must point at whatever we rename the executable to.
const pathTxt = path.join(electronPkgDir, "path.txt");

// Electron not installed yet (e.g. running before `npm install`).
if (!fs.existsSync(plist)) process.exit(0);

const PLIST_BUDDY = "/usr/libexec/PlistBuddy";

function plistRead(key) {
  try {
    return execFileSync(PLIST_BUDDY, ["-c", `Print :${key}`, plist], { encoding: "utf8" }).trim();
  } catch {
    return "";
  }
}

function plistSet(key, value) {
  try {
    execFileSync(PLIST_BUDDY, ["-c", `Set :${key} ${value}`, plist]);
  } catch {
    // Key absent — add it.
    execFileSync(PLIST_BUDDY, ["-c", `Add :${key} string ${value}`, plist]);
  }
}

// Fast path: already fully branded, nothing to do. The executable name
// (CFBundleExecutable) is what the Dock tooltip / Cmd-Tab switcher show
// for a *running* process, so it's the load-bearing check here — the
// display-name keys alone aren't enough.
if (
  plistRead("CFBundleExecutable") === APP_NAME &&
  plistRead("CFBundleName") === APP_NAME &&
  plistRead("CFBundleDisplayName") === APP_NAME
) {
  process.exit(0);
}

try {
  // Rename the Mach-O executable → the Dock/switcher show the process
  // name, which is the executable's filename. Skip if a prior run
  // already did it (stock "Electron" gone, "TreeFacts" present).
  if (fs.existsSync(stockExec) && !fs.existsSync(brandedExec)) {
    fs.renameSync(stockExec, brandedExec);
  }
  plistSet("CFBundleExecutable", APP_NAME);
  // Repoint the `electron` launcher at the renamed binary.
  if (fs.existsSync(pathTxt)) {
    fs.writeFileSync(pathTxt, path.join("Electron.app", "Contents", "MacOS", APP_NAME));
  }

  plistSet("CFBundleName", APP_NAME);
  plistSet("CFBundleDisplayName", APP_NAME);

  if (fs.existsSync(srcIcns)) {
    fs.copyFileSync(srcIcns, path.join(resourcesDir, "treefacts.icns"));
    plistSet("CFBundleIconFile", "treefacts.icns");
  }

  // Editing the bundle invalidates its code signature. On Apple Silicon
  // macOS refuses to launch an app whose signature no longer matches, so
  // re-seal it ad-hoc. Best-effort: warn but continue if it fails.
  try {
    execFileSync("codesign", ["--force", "--sign", "-", electronApp], { stdio: "ignore" });
  } catch (e) {
    console.warn(`[dev-brand] ad-hoc re-sign failed (dev may still launch): ${e.message}`);
  }

  // The Dock/switcher name comes from LaunchServices' cached registration
  // of the bundle path — which still said "Electron" from before we
  // patched. Force LaunchServices to re-read the bundle so the new name
  // (and icon) take effect on the next launch.
  const lsregister =
    "/System/Library/Frameworks/CoreServices.framework/Versions/A/Frameworks/" +
    "LaunchServices.framework/Versions/A/Support/lsregister";
  try {
    execFileSync(lsregister, ["-f", electronApp], { stdio: "ignore" });
  } catch (e) {
    console.warn(`[dev-brand] LaunchServices refresh failed: ${e.message}`);
  }

  console.log(`[dev-brand] Branded dev Electron bundle as "${APP_NAME}".`);
} catch (e) {
  console.warn(`[dev-brand] Could not brand dev bundle: ${e.message}`);
}
