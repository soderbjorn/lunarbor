/*
 * ThreeLib.kt (jsMain)
 * --------------------
 * Minimal Kotlin bindings for three.js and its CSS3DRenderer addon, loaded
 * lazily: [loadThreeLib] imports both with a dynamic `import()`, so webpack
 * puts them in a chunk of their own that is fetched the first time 3D mode
 * opens (the way `DrawingEditor.kt` loads Excalidraw).
 *
 * A dynamically imported module has no static `@JsModule` binding, so the
 * classes are reached through [ThreeLib]'s factory methods and typed by the
 * external interfaces below — only the surface 3D mode calls. Modelled on
 * Lunamux's `Three.kt` / `ThreeCss3d.kt`; kept free of Lunarbor types so the
 * file can move to Lunula as more 3D lands.
 *
 * jsMain only.
 */

package se.soderbjorn.lunarbor.main.space.three

import kotlinx.coroutines.await
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.js.Promise

/** three.js `Vector3` — positions and scales. */
external interface Vec3 {
    var x: Double
    var y: Double
    var z: Double

    /** Sets all three components; returns this vector. */
    fun set(x: Double, y: Double, z: Double): Vec3

    /** Sets all three components to [s]; returns this vector. */
    fun setScalar(s: Double): Vec3
}

/** three.js `Quaternion` — an object's rotation. */
external interface Quat3 {
    /** Sets all four components; returns this quaternion. */
    fun set(x: Double, y: Double, z: Double, w: Double): Quat3
}

/** three.js `Object3D` — anything placed in a scene. */
external interface Object3 {
    val position: Vec3
    val quaternion: Quat3
    val scale: Vec3
    var visible: Boolean
    var frustumCulled: Boolean

    /** Adds a child that inherits this object's transform. */
    fun add(child: Object3)

    /** Removes a child added with [add]. */
    fun remove(child: Object3)

    /** Recomputes this object's (and its children's) world matrices. */
    fun updateMatrixWorld(force: Boolean = definedExternally)
}

/** three.js `PerspectiveCamera`. */
external interface Camera3 : Object3 {
    var aspect: Double
    var fov: Double

    /** Applies [aspect] / [fov] changes. */
    fun updateProjectionMatrix()
}

/** three.js `Color`. */
external interface Color3 {
    /** Sets the colour from a CSS colour string (`#rgb`, `rgb(…)`, names). */
    fun setStyle(style: String): Color3
}

/** three.js `PointsMaterial` — sprites for stars and dust. */
external interface PointsMaterial3 {
    val color: Color3
    var opacity: Double
    var blending: Int
    var needsUpdate: Boolean
}

/** three.js `WebGLRenderer`, drawing every view's background into one canvas. */
external interface WebGLRenderer3 {
    val domElement: HTMLCanvasElement
    var autoClear: Boolean

    fun setSize(width: Int, height: Int, updateStyle: Boolean = definedExternally)
    fun setPixelRatio(value: Double)
    fun setClearColor(color: Int, alpha: Double)
    fun clear()
    fun setViewport(x: Int, y: Int, width: Int, height: Int)
    fun setScissor(x: Int, y: Int, width: Int, height: Int)
    fun setScissorTest(enabled: Boolean)
    fun render(scene: Object3, camera: Camera3)
    fun dispose()
}

/** three.js `CSS3DRenderer` — places real DOM elements in 3D with CSS transforms. */
external interface Css3DRenderer3 {
    /** The wrapper element holding every placed element. */
    val domElement: HTMLElement

    fun setSize(width: Int, height: Int)

    /** Re-applies every object's transform for [camera]; attaches new elements. */
    fun render(scene: Object3, camera: Camera3)
}

/**
 * The loaded three.js and CSS3DRenderer modules, with factories for the
 * objects 3D mode builds.
 *
 * @param three The `three` module namespace.
 * @param css3d The `CSS3DRenderer.js` addon's module namespace.
 */
class ThreeLib internal constructor(private val three: dynamic, private val css3d: dynamic) {
    /**
     * The raw `three` module namespace, for the map shapes' scene
     * (`MapView`), which builds meshes, lines and lights the typed
     * factories here don't cover. Keep its use inside `MapView`.
     */
    val raw: dynamic get() = three

    /** `THREE.AdditiveBlending`: glowing specks on a dark space. */
    val additiveBlending: Int get() = three.AdditiveBlending as Int

    /** `THREE.NormalBlending`: dim specks on a pale space. */
    val normalBlending: Int get() = three.NormalBlending as Int

    /** A new, empty scene. */
    fun scene(): Object3 {
        val t = three
        return js("new t.Scene()").unsafeCast<Object3>()
    }

    /** A new transform group. */
    fun group(): Object3 {
        val t = three
        return js("new t.Group()").unsafeCast<Object3>()
    }

    /** A perspective camera; see `THREE.PerspectiveCamera`. */
    fun perspectiveCamera(fov: Double, aspect: Double, near: Double, far: Double): Camera3 {
        val t = three
        return js("new t.PerspectiveCamera(fov, aspect, near, far)").unsafeCast<Camera3>()
    }

    /** A transparent, antialiased WebGL renderer, or `null` when WebGL is unavailable. */
    fun webGLRenderer(): WebGLRenderer3? {
        val t = three
        return try {
            js("new t.WebGLRenderer({ antialias: true, alpha: true })").unsafeCast<WebGLRenderer3>()
        } catch (_: Throwable) {
            null
        }
    }

    /** A CSS3D renderer for one view. */
    fun css3dRenderer(): Css3DRenderer3 {
        val c = css3d
        return js("new c.CSS3DRenderer()").unsafeCast<Css3DRenderer3>()
    }

    /**
     * Wraps [element] so it can be placed in a scene. three.js sets
     * `pointer-events: auto` inline on it, which would beat the
     * stylesheet: a page's transparent, page-sized slot would then catch
     * clicks meant for the cards behind it. The inline value is cleared,
     * so the stylesheet decides (`.lunarbor-space-slot`: only its card
     * takes the pointer).
     */
    fun css3dObject(element: HTMLElement): Object3 {
        val c = css3d
        val obj = js("new c.CSS3DObject(element)").unsafeCast<Object3>()
        element.style.removeProperty("pointer-events")
        return obj
    }

    /**
     * A cloud of round sprites at [positions] (`x, y, z` triples).
     *
     * @param sizePx Sprite size: CSS pixels when [attenuate] is `false`,
     *   world units shrinking with distance otherwise.
     * @param sprite A canvas holding the sprite's image (a soft dot).
     * @param colors Optional per-point colours (`r, g, b` triples, 0..1),
     *   multiplied by the material's colour.
     * @return The points object and its material (to recolour later).
     */
    fun points(positions: FloatArray, sizePx: Double, attenuate: Boolean, sprite: HTMLCanvasElement, colors: FloatArray? = null): Pair<Object3, PointsMaterial3> {
        val t = three
        val array = js("new Float32Array(positions.length)")
        for (i in positions.indices) array[i] = positions[i]
        val geometry = js("new t.BufferGeometry()")
        geometry.setAttribute("position", js("new t.BufferAttribute(array, 3)"))
        if (colors != null) {
            val tint = js("new Float32Array(colors.length)")
            for (i in colors.indices) tint[i] = colors[i]
            geometry.setAttribute("color", js("new t.BufferAttribute(tint, 3)"))
        }
        val texture = js("new t.CanvasTexture(sprite)")
        val params: dynamic = js("({})")
        params.size = sizePx
        params.sizeAttenuation = attenuate
        params.map = texture
        params.transparent = true
        params.depthWrite = false
        params.vertexColors = colors != null
        val material = js("new t.PointsMaterial(params)").unsafeCast<PointsMaterial3>()
        val points = js("new t.Points(geometry, material)").unsafeCast<Object3>()
        return points to material
    }
}

/** The loaded library, once [loadThreeLib] has run. */
private var cachedThreeLib: ThreeLib? = null

/**
 * Loads three.js and its CSS3DRenderer (first call only; later calls
 * return the same [ThreeLib]). Called by `SpaceMode` when 3D mode opens.
 */
suspend fun loadThreeLib(): ThreeLib {
    cachedThreeLib?.let { return it }
    val modules: dynamic = importThree().await()
    val lib = ThreeLib(modules[0], modules[1])
    cachedThreeLib = lib
    return lib
}

/**
 * `[three, CSS3DRenderer addon]` as one promise. A dynamic import, so
 * webpack splits both into a chunk of their own.
 */
private fun importThree(): Promise<dynamic> =
    js("Promise.all([import('three'), import('three/examples/jsm/renderers/CSS3DRenderer.js')])")
