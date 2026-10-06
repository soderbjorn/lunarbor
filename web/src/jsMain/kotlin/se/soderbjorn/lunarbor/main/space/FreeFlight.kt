/*
 * FreeFlight.kt (jsMain)
 * ----------------------
 * Free flight in 3D mode: on the maps (Crown, Cone, Galaxy) F turns the
 * map's camera into a spaceship and F again lands it back on the map's
 * orbit; in Pages and Grove ⌥⌘F (or ⌃⌘4) takes off ([PageFlight]). Ported
 * from Lunamux's free flight so the two apps fly alike: the same keys, the
 * same ship model and the same "FREE FLIGHT" legend in the bottom left
 * ([MapLegend]).
 *
 *  - Ship model: a position, a velocity and an orthonormal basis (nose
 *    `f`, roof `u`; right = f × u) with pitch / yaw / roll velocities.
 *    Each step applies angular thrust, rotates the basis, thrusts along
 *    the rotated axes, moves, then damps — Lunamux's `applyFlyStep`.
 *    Steps are counted in 60 Hz frames ([step]'s `frames`), so flight runs
 *    at the same speed on any refresh rate.
 *  - Keys (by physical `code`, held while down): W / S throttle, A / D
 *    strafe, Shift descend, ↑ / ↓ pitch, ← / → yaw, Q / E roll. One-shot
 *    keys are handled by `MapView`, which owns the camera and the map.
 *  - [MapLegend] lists every key; a key flashes its row while used.
 *
 * Owned by `MapView` and [PageFlight]. View glue only — no vault logic.
 */

package se.soderbjorn.lunarbor.main.space

import se.soderbjorn.lunarbor.main.SpaceVec
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The flying camera of one map.
 *
 * ### Callers
 * - `MapView` creates one, calls [start] / [stop] on F, forwards key downs
 *   and ups ([press] / [release]), calls [step] every frame while
 *   [isOn] and reads [pos], [forward] and [up] to place its camera.
 *
 * @param scale World units per Lunamux unit: the map is smaller than
 *   Lunamux's world, so thrust is scaled down to keep the same feel.
 * @param legend The map's key legend: flashes the row of a key in use.
 */
internal class FreeFlight(private val scale: Double, private val legend: MapLegend) {
    /** `true` while flying. */
    var isOn = false
        private set

    /** The ship's position. */
    var pos: SpaceVec = SpaceVec.ZERO
        private set

    /** The ship's nose, unit length. */
    var forward: SpaceVec = SpaceVec(0.0, 0.0, -1.0)
        private set

    /** The ship's roof, unit length and at right angles to [forward]. */
    var up: SpaceVec = SpaceVec(0.0, 1.0, 0.0)
        private set

    private var vx = 0.0
    private var vy = 0.0
    private var vz = 0.0
    private var pitchVel = 0.0
    private var yawVel = 0.0
    private var rollVel = 0.0

    /** Physical key codes held down (only [FLY_KEY_CODES]). */
    private val held = HashSet<String>()

    /**
     * Takes off from the camera's current pose.
     *
     * @param from The camera's position.
     * @param lookAt A point the camera looks at (sets the nose).
     * @param roof The camera's up vector (made perpendicular to the nose).
     */
    fun start(from: SpaceVec, lookAt: SpaceVec, roof: SpaceVec) {
        pos = from
        forward = norm(lookAt - from) ?: SpaceVec(0.0, 0.0, -1.0)
        val d = dot(roof, forward)
        up = norm(roof - forward * d) ?: SpaceVec(0.0, 1.0, 0.0)
        clearVelocity()
        held.clear()
        isOn = true
        legend.show(flying = true)
    }

    /** Lands: momentum stops, held keys are forgotten, the legend shows the map's keys. */
    fun stop() {
        isOn = false
        held.clear()
        clearVelocity()
        legend.show(flying = false)
    }

    /**
     * A key went down while flying.
     *
     * @return `true` when it is a flight key (the caller consumes it).
     */
    fun press(code: String): Boolean {
        if (code !in FLY_KEY_CODES) return false
        held += code
        flyRowOf(code)?.let { legend.flash(it) }
        return true
    }

    /** A key went up; `true` when it was a held flight key. */
    fun release(code: String): Boolean = held.remove(code)

    /** Forgets every held key (the map lost the keyboard). */
    fun releaseAll() = held.clear()

    /** `true` while a key is held or the ship still drifts or turns. */
    val isMoving: Boolean
        get() = held.isNotEmpty() || vx != 0.0 || vy != 0.0 || vz != 0.0 ||
            abs(pitchVel) > 1e-6 || abs(yawVel) > 1e-6 || abs(rollVel) > 1e-6

    /**
     * Advances the ship by [frames] 60 Hz frames (Lunamux's per-frame step,
     * repeated; a fraction runs as one scaled step).
     */
    fun step(frames: Double) {
        var left = frames.coerceIn(0.0, 4.0)
        while (left > 1e-6) {
            val n = if (left >= 1.0) 1.0 else left
            stepOnce(n)
            left -= n
        }
        if (abs(pitchVel) <= 1e-6) pitchVel = 0.0
        if (abs(yawVel) <= 1e-6) yawVel = 0.0
        if (abs(rollVel) <= 1e-6) rollVel = 0.0
    }

    // ---------------------------------------------------------------- model

    private fun stepOnce(n: Double) {
        fun isHeld(c: String) = c in held
        val rot = FLY_ROT_ACCEL * n
        if (isHeld("ArrowUp")) pitchVel += rot
        if (isHeld("ArrowDown")) pitchVel -= rot
        if (isHeld("ArrowLeft")) yawVel += rot
        if (isHeld("ArrowRight")) yawVel -= rot
        if (isHeld("KeyQ")) rollVel += rot
        if (isHeld("KeyE")) rollVel -= rot
        rotate(pitchVel * n, yawVel * n, rollVel * n)
        val a = FLY_ACCEL * scale * n
        val f = forward
        val u = up
        val r = cross(f, u)
        if (isHeld("KeyW")) { vx += f.x * a; vy += f.y * a; vz += f.z * a }
        if (isHeld("KeyS")) { vx -= f.x * a; vy -= f.y * a; vz -= f.z * a }
        if (isHeld("KeyA")) { vx -= r.x * a; vy -= r.y * a; vz -= r.z * a }
        if (isHeld("KeyD")) { vx += r.x * a; vy += r.y * a; vz += r.z * a }
        if (isHeld("ShiftLeft") || isHeld("ShiftRight")) { vx -= u.x * a; vy -= u.y * a; vz -= u.z * a }
        pos = SpaceVec(pos.x + vx * n, pos.y + vy * n, pos.z + vz * n)
        val damp = FLY_DAMPING.pow(n)
        val rotDamp = FLY_ROT_DAMPING.pow(n)
        vx *= damp; vy *= damp; vz *= damp
        pitchVel *= rotDamp; yawVel *= rotDamp; rollVel *= rotDamp
        val eps = FLY_STOP_EPS * scale
        if (abs(vx) < eps) vx = 0.0
        if (abs(vy) < eps) vy = 0.0
        if (abs(vz) < eps) vz = 0.0
    }

    /** Lunamux's `rotateFlyBasis`: pitch about right, yaw about up, roll about the nose, re-orthonormalised. */
    private fun rotate(pitch: Double, yaw: Double, roll: Double) {
        var fx = forward.x; var fy = forward.y; var fz = forward.z
        var ux = up.x; var uy = up.y; var uz = up.z
        if (pitch != 0.0) {
            val c = cos(pitch); val s = sin(pitch)
            val nfx = fx * c + ux * s; val nfy = fy * c + uy * s; val nfz = fz * c + uz * s
            ux = -fx * s + ux * c; uy = -fy * s + uy * c; uz = -fz * s + uz * c
            fx = nfx; fy = nfy; fz = nfz
        }
        if (yaw != 0.0) {
            val rx = fy * uz - fz * uy; val ry = fz * ux - fx * uz; val rz = fx * uy - fy * ux
            val c = cos(yaw); val s = sin(yaw)
            fx = fx * c - rx * s; fy = fy * c - ry * s; fz = fz * c - rz * s
        }
        if (roll != 0.0) {
            val rx = fy * uz - fz * uy; val ry = fz * ux - fx * uz; val rz = fx * uy - fy * ux
            val c = cos(roll); val s = sin(roll)
            ux = ux * c - rx * s; uy = uy * c - ry * s; uz = uz * c - rz * s
        }
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        if (fl > 1e-9) { fx /= fl; fy /= fl; fz /= fl }
        val d = ux * fx + uy * fy + uz * fz
        ux -= d * fx; uy -= d * fy; uz -= d * fz
        val ul = sqrt(ux * ux + uy * uy + uz * uz)
        if (ul > 1e-9) { ux /= ul; uy /= ul; uz /= ul }
        forward = SpaceVec(fx, fy, fz)
        up = SpaceVec(ux, uy, uz)
    }

    private fun clearVelocity() {
        vx = 0.0; vy = 0.0; vz = 0.0
        pitchVel = 0.0; yawVel = 0.0; rollVel = 0.0
    }

    companion object {
        /** Lunamux's thrust: world units per frame² (scaled by [scale]). */
        const val FLY_ACCEL = 2.4

        /** Linear velocity kept per frame. */
        const val FLY_DAMPING = 0.94

        /** Turn rate gained per frame per steering key, in radians. */
        const val FLY_ROT_ACCEL = 0.0024

        /** Angular velocity kept per frame. */
        const val FLY_ROT_DAMPING = 0.88

        /** Speeds below this (scaled) snap to rest. */
        const val FLY_STOP_EPS = 0.02

        /** The keys flown while held, by physical code. */
        val FLY_KEY_CODES = setOf(
            "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight",
            "KeyW", "KeyS", "KeyQ", "KeyE", "KeyA", "KeyD",
            "ShiftLeft", "ShiftRight",
        )

        private fun flyRowOf(code: String): String? = when (code) {
            "KeyW", "KeyS" -> "fly-throttle"
            "KeyA", "KeyD" -> "fly-strafe"
            "ShiftLeft", "ShiftRight" -> "fly-down"
            "ArrowUp", "ArrowDown" -> "fly-pitch"
            "ArrowLeft", "ArrowRight" -> "fly-yaw"
            "KeyQ", "KeyE" -> "fly-roll"
            else -> null
        }

        /** The target marker's style; installed with `MapView`'s. */
        const val CSS = """
.lunarbor-flight-target {
    position: absolute; pointer-events: none; border-radius: 50%; display: none;
    border: 2px dashed var(--t-accent, #7aa2ff); transform: translate(-50%, -50%);
}
"""
    }
}

private fun dot(a: SpaceVec, b: SpaceVec) = a.x * b.x + a.y * b.y + a.z * b.z
private fun cross(f: SpaceVec, u: SpaceVec) = SpaceVec(f.y * u.z - f.z * u.y, f.z * u.x - f.x * u.z, f.x * u.y - f.y * u.x)
private fun norm(v: SpaceVec): SpaceVec? {
    val l = sqrt(dot(v, v))
    return if (l < 1e-9) null else SpaceVec(v.x / l, v.y / l, v.z / l)
}
