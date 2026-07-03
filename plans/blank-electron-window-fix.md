# Blank Electron window — stale `build/js/packages` cache

## Symptom

Launching the Electron app shows a blank window. The DevTools console
shows a thrown `bs` (Kotlin `ClassCastException`) during module init:

```
bs
  at vi (web.js:1:14428)              ← Kotlin runtime: throw CCE
  at sc (web.js:1:52376)              ← kotlin.text.padStart
  at Object.i (web.js:1:1027446)      ← Metro DependencyGraph.kt vicinity
  at 888 (web.js:1:1031979)           ← Metro module top-level eval
  at n (web.js:1:1051449)             ← __webpack_require__
  at 568 (web.js:1:872154)            ← TreeFacts:web entry
  ...
```

The error fires before any rendering happens, so the host `<div id="app">`
never gets mounted.

## Root cause

Webpack reads its inputs from `build/js/packages/<module>/kotlin/`, not
from `<module>/build/compileSync/...`. After a Kotlin or Metro version
bump, the freshly compiled JS lands in `compileSync` but the
`build/js/packages/...` copy can be left behind — Gradle's UP-TO-DATE
check on `jsProductionExecutableCompileSync` doesn't always notice the
divergence.

The stale file at `build/js/packages/TreeFacts-web/kotlin/metro-runtime-js.js`
(385 lines) was from an older Metro build that imported

```js
var protoOf = kotlin_kotlin.$_$.gb;
var initMetadataForClass = kotlin_kotlin.$_$.ga;
```

In that older Kotlin/JS runtime, `$_$.gb` exported `protoOf`. But the
bundled `kotlin-kotlin-stdlib.js` is from Kotlin 2.3.20, where the
mangled export keys have shifted: `$_$.gb` now exports
`String.padStart` (`sc`), and `protoOf` lives at `$_$.ba`.

So at module init, Metro's class-registration code ran:

```js
s(y).equals = function (t) { return t instanceof y && ... };
//   ↑
//   stale Metro thought s = protoOf
//   bundled stdlib actually delivered s = padStart
```

`padStart(y, …)` requires a `CharSequence` and throws `ClassCastException`
when handed a class constructor. The throw happens during the bundle's
top-level eval, so nothing in the app initializes.

The fresh `web/build/compileSync/.../metro-runtime-js.js` (133 lines)
already had the correct 2.3.20 imports (`protoOf = $_$.ba`,
`initMetadataForClass = $_$.f9`). It just wasn't being copied through to
`build/js/packages/`.

## Fix

```sh
./gradlew :web:jsProductionExecutableCompileSync --rerun-tasks
./gradlew :web:jsBrowserProductionWebpack --rerun-tasks
./gradlew :electron:copyWebBundle
```

`--rerun-tasks` is the important bit — without it Gradle keeps the
broken cache as UP-TO-DATE. After the rebuild the bundle drops from
~1.05 MB to ~778 KB (the duplicated stale Metro common code is gone)
and the app launches normally.

`./gradlew clean` followed by a normal build also works but throws away
much more than necessary.

## How to recognize it next time

A blank Electron window plus a thrown Kotlin runtime exception
(`bs`/`ms`/`ks` / "Cannot cast …") originating in `padStart`,
`ensureNotNull`, `THROW_CCE`, or another stdlib helper *during top-level
module evaluation* (i.e. before `window.onload` runs) is almost always
this class of mismatch — a stale per-module JS file in
`build/js/packages/<module>/kotlin/` that was compiled against a
different Kotlin runtime version than the bundled stdlib.

Quick sanity check:

```sh
md5 build/js/packages/TreeFacts-web/kotlin/metro-runtime-js.js \
    web/build/compileSync/js/main/productionExecutable/kotlin/metro-runtime-js.js
```

If the two hashes differ, the staging copy is stale and the
`--rerun-tasks` recipe above will resync it. The same check works for
any other dependency JS file under `build/js/packages/.../kotlin/`.
