#!/usr/bin/env node
/*
 * verify-link-clicks.mjs — regression check for LBR-8 (the new-window rule).
 *
 * Drives the real Electron app over CDP and checks that a plain press goes to
 * the target in place while Shift-, ⌘-, Ctrl- (the Mac's right-click) and
 * right-clicks open exactly ONE new window, leave the clicked pane where it
 * was, and leave the new window focused — on a `lunarbor:` link, a resolved
 * wiki link, a link inside a block, a bullet dot, a search-node result, a
 * pane search result and a "Linked from" backlink.
 *
 * Launches the app once (through scripts/ai-dev-run.sh, which enforces an
 * isolated data dir) and puts it back between cases: extra windows closed,
 * the first one back at Home. Seeds its own vault.
 *
 * Usage (from the repo root, Electron resources built, macOS):
 *   node scripts/verify-link-clicks.mjs /tmp/lunarbor-verify 9222
 *
 * Exits non-zero when any case fails. Stops its app on exit.
 */
import { execFileSync, spawnSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';

const [dataDir, port = '9222'] = process.argv.slice(2);
if (!dataDir || !dataDir.startsWith('/tmp/')) {
  console.error('usage: verify-link-clicks.mjs /tmp/<dir> [port]');
  process.exit(2);
}
const vault = `${dataDir}/vault`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const stopApp = () => spawnSync('pkill', ['-f', '--', `--remote-debugging-port=${port}`]);

// Seeded vault + a wiki link, a link in a block and a search node.
execFileSync('python3', ['scripts/seed-vault.py', '--wipe', '--vault', vault], { stdio: 'ignore' });
appendFileSync(`${vault}/_node.md`,
  '> Block link to [pasta](lunarbor:/Recipes/Pasta)\n- Wiki [[Soups]] here\n- Find {{search: Tomato}}\n');

async function launch() {
  stopApp();
  await sleep(1500);
  spawnSync('rm', ['-rf', `${dataDir}/Lunarbor`, `${dataDir}/lunarbor.json`]);
  const r = spawnSync('scripts/ai-dev-run.sh', [port, dataDir], {
    env: { ...process.env, LUNARBOR_LOCAL_DATA: dataDir },
  });
  if (r.status !== 0) throw new Error(`launch failed: ${r.stderr}`);
  await sleep(3000);
}

async function connect() {
  const targets = await (await fetch(`http://localhost:${port}/json`)).json();
  const ws = new WebSocket(targets.find((t) => t.type === 'page').webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  ws.onmessage = (m) => {
    const d = JSON.parse(m.data);
    if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); }
  };
  await new Promise((r) => (ws.onopen = r));
  const send = (method, params = {}) =>
    new Promise((r) => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  const evalJs = async (expr) =>
    (await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })).result?.result?.value;
  return { ws, send, evalJs };
}

// Modifier bits for Input.dispatchMouseEvent: 2 = Ctrl, 4 = Meta, 8 = Shift.
const HOW = { plain: 0, shift: 8, meta: 4, ctrl: 2, right: 0 };

// A closed window can linger as a `.dt-pane-closing-ghost` while it animates out.
const LIVE = `.dt-pane:not(.dt-pane-closing-ghost)`;
const PANES = `[...document.querySelectorAll('${LIVE} .dt-pane-content .lunarbor-editor')].map(e => {
  const p = e.closest('[data-pane-id]');
  return { id: p.getAttribute('data-pane-id'), focused: p.classList.contains('dt-pane-focused'),
           text: e.innerText.slice(0, 40) };
})`;

const TARGETS = {
  link: (t) => `[...e.querySelectorAll('[data-href]')].find(s => s.textContent === ${JSON.stringify(t)})`,
  dot: (t) => `[...e.querySelectorAll('[data-row]')].find(r => r.textContent.includes(${JSON.stringify(t)})).querySelector('.lunarbor-bullet')`,
  searchnode: () => `e.querySelector('.lunarbor-search-node-hit')`,
  searchhit: () => `e.closest('.dt-pane-content').querySelector('.lunarbor-search-hit-body')`,
  backlink: () => `e.closest('.dt-pane-content').querySelector('.lunarbor-backlinks-row')`,
};

async function point(evalJs, sel) {
  return evalJs(`(() => { const e = document.querySelector('${LIVE} .dt-pane-content .lunarbor-editor');
    const s = ${sel}; if (!s) return null; const r = s.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; })()`);
}

async function press(send, p, how) {
  const modifiers = HOW[how];
  const button = how === 'right' ? 'right' : 'left';
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: p.x, y: p.y, modifiers });
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: p.x, y: p.y, button, modifiers, clickCount: 1, buttons: button === 'left' ? 1 : 2 });
  await sleep(120);
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: p.x, y: p.y, button, modifiers, clickCount: 1, buttons: 0 });
}

/**
 * Puts the app back between cases: closes every window but the first and
 * takes that one Home (closing an open search first).
 */
async function reset(send, evalJs) {
  // Each extra window's close button, then the toolkit's "Close pane" confirm.
  for (let i = 0; i < 5; i++) {
    const closed = await evalJs(`(() => { const p = [...document.querySelectorAll('${LIVE}[data-pane-id]')][1];
      if (!p) return false; p.querySelector('.dt-pane-action-close')?.click(); return true; })()`);
    if (!closed) break;
    for (let t = 0; t < 20 && !(await evalJs(`!!document.querySelector('.dt-modal-btn-confirm')`)); t++) await sleep(100);
    await evalJs(`document.querySelector('.dt-modal-btn-confirm')?.click()`);
    await sleep(800);
  }
  const key = async (k, code, vk, modifiers) => {
    await send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: k, code, windowsVirtualKeyCode: vk, modifiers });
    await send('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code, windowsVirtualKeyCode: vk, modifiers });
  };
  if (await evalJs(`!!document.querySelector('.lunarbor-search-hit')`)) {
    await evalJs(`document.querySelector('.dt-pane-content input')?.focus()`);
    await key('Escape', 'Escape', 27, 0);
    await sleep(600);
  }
  const panes = await evalJs(PANES);
  if (panes.length !== 1) throw new Error(`reset left ${panes.length} windows`);
  if (!panes[0].text.includes('Welcome')) {
    await press(send, await point(evalJs, `e.querySelector('[data-row]')`), 'plain');
    await sleep(300);
    // Shift-Ctrl-Cmd-Up: Home.
    await key('ArrowUp', 'ArrowUp', 38, 2 | 4 | 8);
    await sleep(1500);
  }
  const home = await evalJs(PANES);
  if (!home[0].text.includes('Welcome')) throw new Error(`reset could not go Home: ${home[0].text}`);
}

/** One case: prepare, press the target, check panes. `expect` is the text the clicked target leads to. */
async function runCase({ target, text, how, prep, expect }, { send, evalJs }) {
  await reset(send, evalJs);
  if (prep === 'soupsPage') {
    await press(send, await point(evalJs, TARGETS.link('soups')), 'plain');
    await sleep(2500);
  }
  if (prep === 'search') {
    await press(send, await point(evalJs, `e.querySelector('[data-row]')`), 'plain');
    await sleep(400);
    await send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'f', code: 'KeyF', windowsVirtualKeyCode: 70, modifiers: 4 });
    await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'f', code: 'KeyF', windowsVirtualKeyCode: 70, modifiers: 4 });
    await sleep(500);
    await send('Input.insertText', { text: 'Tomato' });
    await sleep(2500);
  }
  const before = await evalJs(PANES);
  // Backlinks and search results arrive a moment after the page.
  let p = null;
  for (let t = 0; t < 50 && !(p = await point(evalJs, TARGETS[target](text))); t++) await sleep(100);
  if (!p) return `target not found`;
  await press(send, p, how);
  await sleep(2000);
  const after = await evalJs(PANES);
  if (how === 'plain') {
    if (after.length !== before.length) return `plain press opened a window (${after.length} panes)`;
    if (!after[0].text.includes(expect)) return `plain press did not go there: ${after[0].text}`;
    return null;
  }
  if (after.length !== before.length + 1) return `expected one new window, got ${after.length - before.length}`;
  const source = after.find((x) => x.id === before[0].id);
  if (source.text !== before[0].text) return `the clicked pane navigated too: ${source.text}`;
  const fresh = after.find((x) => !before.some((b) => b.id === x.id));
  if (!fresh.text.includes(expect)) return `new window is not at the target: ${fresh.text}`;
  if (!fresh.focused) return `new window is not focused`;
  return null;
}

const cases = [];
for (const how of ['plain', 'shift', 'meta', 'ctrl', 'right']) cases.push({ target: 'link', text: 'soups', how, expect: 'Tomato' });
for (const how of ['shift', 'ctrl']) cases.push({ target: 'link', text: 'Soups', how, expect: 'Tomato' });
cases.push({ target: 'link', text: 'pasta', how: 'ctrl', expect: 'Carbonara' });
for (const how of ['plain', 'shift', 'ctrl', 'right']) cases.push({ target: 'dot', text: 'Recipes', how, expect: 'Pasta' });
for (const how of ['plain', 'shift', 'ctrl', 'right']) cases.push({ target: 'searchnode', how, expect: 'Tomato' });
for (const how of ['plain', 'shift', 'ctrl', 'right']) cases.push({ target: 'backlink', how, prep: 'soupsPage', expect: 'Welcome' });
// Last: an open pane search outlives `reset`.
for (const how of ['plain', 'shift', 'ctrl']) cases.push({ target: 'searchhit', how, prep: 'search', expect: 'Tomato' });

let failed = 0;
try {
  await launch();
  const cdp = await connect();
  for (const c of cases) {
    const err = await runCase(c, cdp);
    const name = `${c.target}${c.text ? ` "${c.text}"` : ''} ${c.how}`;
    console.log(`${err ? 'FAIL' : 'ok  '} ${name}${err ? ` — ${err}` : ''}`);
    if (err) failed++;
  }
  cdp.ws.close();
} finally {
  stopApp();
}
console.log(failed ? `${failed} of ${cases.length} failed` : `all ${cases.length} passed`);
process.exit(failed ? 1 : 0);
