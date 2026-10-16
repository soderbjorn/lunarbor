/* FileThread.kt (electron-main, jsMain) — the main process's file I/O, on a thread of its own.
 *
 * Node runs every async `fs` call (and `dns.lookup`, async `zlib`) on
 * libuv's shared thread pool. In Lunarbor's main process that pool has
 * been caught wedged (Electron 32 and 44, macOS): work is queued, all
 * four pool threads sit idle waiting for it, and nothing ever runs —
 * a sampled stuck process showed exactly that, while `fs.statSync` on
 * the same vault answered at once. Saves stopped, MCP calls hung, and
 * even quitting hung (Node's exit joins the pool's threads).
 *
 * So the main process does not use the pool for files. [FileThread]
 * runs one `worker_threads` Worker that performs each operation with
 * the *synchronous* `fs` API on its own thread, and [fsPromises]
 * (NodeExternals.kt) is a stand-in for `fs/promises` backed by it, so
 * every caller — the renderer's file IPC, `lunarbor-asset://`, settings,
 * backups — goes through here without knowing. Messages between the
 * threads use the event loops' own wake-ups, not the pool.
 *
 * Rules for editors:
 *  - Never use Node's real `fs/promises`, callback `fs` or async `zlib`
 *    in the main process; add an operation here instead.
 *  - One worker runs operations one at a time, in the order they were
 *    sent, so writes to a file land in order.
 *  - A crashed worker fails the operations it held and is replaced on
 *    the next call. A worker blocked inside a call (a stalled cloud
 *    drive) is noticed by FsWatchdog.kt. */
package se.soderbjorn.lunarbor.electron

import kotlin.js.Promise

private val nodeRequireFileThread: dynamic = js("require")
private val workerThreadsModule: dynamic = nodeRequireFileThread("worker_threads")
private val bufferClassFileThread: dynamic = nodeRequireFileThread("buffer").Buffer

/**
 * The worker's source (CommonJS, run with `eval: true`). Each message is
 * `{ id, op, a: [...] }`; each reply `{ id, ok, v }` or
 * `{ id, ok: false, code, message }`.
 */
private const val WORKER_SOURCE: String = """
const { parentPort } = require('worker_threads');
const fs = require('fs');
const zlib = require('zlib');
function statOf(s) {
  return { mtimeMs: s.mtimeMs, size: s.size, dir: s.isDirectory(), file: s.isFile() };
}
function run(op, a) {
  switch (op) {
    case 'mkdir': return fs.mkdirSync(a[0], { recursive: a[1] }) || null;
    case 'writeFile': fs.writeFileSync(a[0], a[1]); return null;
    case 'rename': fs.renameSync(a[0], a[1]); return null;
    case 'unlink': fs.unlinkSync(a[0]); return null;
    case 'rmdir': fs.rmdirSync(a[0]); return null;
    case 'readFile': return a[1] ? fs.readFileSync(a[0], a[1]) : fs.readFileSync(a[0]);
    case 'readdir':
      if (!a[1]) return fs.readdirSync(a[0]);
      return fs.readdirSync(a[0], { withFileTypes: true }).map(d => ({
        name: d.name,
        k: d.isSymbolicLink() ? 'l' : d.isDirectory() ? 'd' : d.isFile() ? 'f' : 'o',
      }));
    case 'stat': return statOf(fs.statSync(a[0]));
    case 'open': return fs.openSync(a[0], a[1]);
    case 'write': {
      const b = a[1];
      let done = 0;
      while (done < b.length) done += fs.writeSync(a[0], b, done, b.length - done);
      return null;
    }
    case 'close': fs.closeSync(a[0]); return null;
    case 'deflateRaw': return zlib.deflateRawSync(a[0]);
    default: throw new Error('unknown file operation ' + op);
  }
}
parentPort.on('message', m => {
  let reply;
  try {
    reply = { id: m.id, ok: true, v: run(m.op, m.a) };
  } catch (e) {
    reply = { id: m.id, ok: false, code: e && e.code, message: String((e && e.message) || e) };
  }
  parentPort.postMessage(reply);
});
"""

/**
 * The file I/O thread: sends operations to the worker and resolves their
 * promises from its replies.
 *
 * ### Callers
 * - [fsPromises] (NodeExternals.kt) — every main-process file operation.
 * - `ZipWriter.deflateRaw` — compression for backups ([deflateRaw]).
 * - `FsWatchdog` probes it with a `stat` of the temp folder.
 */
internal object FileThread {
    private var worker: dynamic = null
    private var nextId = 0
    private val pending = HashMap<Int, Pair<(dynamic) -> Unit, (Throwable) -> Unit>>()

    /**
     * Runs [op] with [args] on the file thread.
     *
     * @return A promise of the operation's result; a failed operation
     *   rejects with an `Error` carrying Node's `code` (e.g. `ENOENT`).
     */
    fun call(op: String, vararg args: dynamic): Promise<dynamic> = Promise { resolve, reject ->
        val w = ensureWorker()
        val id = ++nextId
        // Keep the process alive only while an operation is out.
        if (pending.isEmpty()) w.ref()
        pending[id] = resolve to reject
        val message: dynamic = js("({})")
        message.id = id
        message.op = op
        message.a = args
        w.postMessage(message)
    }

    /** Raw DEFLATE of [data] (a `Buffer`), compressed on the file thread. */
    fun deflateRaw(data: dynamic): Promise<dynamic> = call("deflateRaw", data).then<dynamic> { out: dynamic -> asBuffer(out) }

    private fun ensureWorker(): dynamic {
        val existing: dynamic = worker
        if (existing != null) return existing
        val options: dynamic = js("({})")
        options.eval = true
        val workerClass: dynamic = workerThreadsModule.Worker
        val source = WORKER_SOURCE
        val w: dynamic = js("new workerClass(source, options)")
        w.on("message") { reply: dynamic -> settle(reply) }
        w.on("error") { err: dynamic -> console.error("[lunarbor] file thread failed", err) }
        w.on("exit") { _: dynamic ->
            if (worker === w) worker = null
            failAll("the file thread stopped")
        }
        worker = w
        return w
    }

    private fun settle(reply: dynamic) {
        val (resolve, reject) = pending.remove(reply.id as Int) ?: return
        // The app's lifetime is the windows', not this thread's. (After the
        // `message` listener: adding one would ref the worker again.)
        if (pending.isEmpty()) worker?.unref()
        if (reply.ok == true) {
            resolve(reply.v)
        } else {
            val err: dynamic = js("new Error(reply.message)")
            if (reply.code != null) err.code = reply.code
            reject(err.unsafeCast<Throwable>())
        }
    }

    private fun failAll(why: String) {
        val all = pending.values.toList()
        pending.clear()
        for ((_, reject) in all) reject(Throwable(why))
    }
}

/** A Node `Buffer` over [bytes], a `Uint8Array` as a `Buffer` arrives from the worker. */
private fun asBuffer(bytes: dynamic): dynamic =
    bufferClassFileThread.from(bytes.buffer, bytes.byteOffset, bytes.byteLength)

/**
 * A stand-in for Node's `fs/promises` whose calls run on [FileThread]:
 * the subset the main process uses, with the same shapes — `readFile`
 * without an encoding gives a `Buffer`, `stat` an object with `mtimeMs`,
 * `size`, `isDirectory()` and `isFile()`, `readdir` with
 * `{ withFileTypes: true }` Dirent-like objects, and `open` a handle with
 * `write(buffer)` and `close()`.
 *
 * Built once by [fsPromises] (NodeExternals.kt).
 */
internal fun fileThreadPromises(): FsPromises {
    val o: dynamic = js("({})")
    o.mkdir = { path: String, options: dynamic -> FileThread.call("mkdir", path, options?.recursive == true) }
    o.writeFile = { path: String, data: dynamic -> FileThread.call("writeFile", path, data) }
    o.rename = { from: String, to: String -> FileThread.call("rename", from, to) }
    o.unlink = { path: String -> FileThread.call("unlink", path) }
    o.rmdir = { path: String -> FileThread.call("rmdir", path) }
    o.readFile = { path: String, encoding: dynamic ->
        if (encoding is String) FileThread.call("readFile", path, encoding)
        else FileThread.call("readFile", path, null).then<dynamic> { bytes: dynamic -> asBuffer(bytes) }
    }
    o.readdir = { path: String, options: dynamic ->
        val withTypes = options?.withFileTypes == true
        FileThread.call("readdir", path, withTypes).then { list: dynamic ->
            if (!withTypes) list else list.map { e: dynamic ->
                val k = e.k as String
                val d: dynamic = js("({})")
                d.name = e.name
                d.isDirectory = { k == "d" }
                d.isFile = { k == "f" }
                d.isSymbolicLink = { k == "l" }
                d
            }
        }
    }
    o.stat = { path: String ->
        FileThread.call("stat", path).then { s: dynamic ->
            val dir = s.dir == true
            val file = s.file == true
            s.isDirectory = { dir }
            s.isFile = { file }
            s
        }
    }
    o.open = { path: String, flags: String ->
        FileThread.call("open", path, flags).then { fd: dynamic ->
            val handle: dynamic = js("({})")
            handle.write = { data: dynamic -> FileThread.call("write", fd, data) }
            handle.close = { FileThread.call("close", fd) }
            handle
        }
    }
    return o.unsafeCast<FsPromises>()
}
