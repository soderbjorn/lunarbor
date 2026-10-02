/*
 * AppLogo.kt (jsMain)
 * -------------------
 * The "lunarbor" brand logo for the left-sidebar header slot: a small
 * status dot to the LEFT of a lowercase monospaced wordmark, visually
 * identical to termtastic's sidebar logo (`.app-logo` there). Unlike
 * termtastic — whose dot aggregates agent work state — Lunarbor ties
 * the dot to SAVE state:
 *
 *   - unsaved — at least one open document holds edits the autosave
 *               loop has not yet flushed. The dot breathes (pulses).
 *   - saved   — everything is on disk. A solid, steady dot.
 *
 * The source of truth is `DocumentRegistry.unsavedFilesFlow`
 * (commonMain), which aggregates every open `Document.dirtyFlow`;
 * [AppShell.render] collects it and calls [setAppLogoUnsaved] on each
 * transition.
 *
 * The pulse is driven by a JS `requestAnimationFrame` loop rather than
 * a CSS `@keyframes` animation, mirroring termtastic's design for the
 * same reason: the toolkit re-parents the (cached) sidebar-header
 * element on chrome rebuilds, and a re-parented element restarts any
 * CSS animation from its first keyframe — snapping the dot to full
 * brightness on every rebuild. A wall-clock-derived opacity is immune:
 * the element simply adopts the current phase on the next frame.
 *
 * jsMain only — touches the DOM. Styles live in
 * `AppShell.ensureLunarborChromeStyles` (`.app-logo*` rules).
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/** Marker class on the dot while unsaved edits exist; drives the pulse. */
private const val UNSAVED_CLASS = "state-unsaved"

/**
 * Cached logo element. Built once by [buildAppLogo] and re-served on
 * every toolkit rerender so the shell re-parents the same node — the
 * dot keeps its class/opacity state across chrome rebuilds instead of
 * being recreated blank.
 */
private var appLogoEl: HTMLElement? = null

/**
 * Cached dot element inside [appLogoEl], kept so [setAppLogoUnsaved]
 * can repaint without a DOM query (and before first attach, when a
 * `getElementById` lookup would miss).
 */
private var appLogoDotEl: HTMLElement? = null

/** Last state applied via [setAppLogoUnsaved]; replayed on (re)build. */
private var appLogoUnsaved = false

/**
 * Builds (once) the app logo — status dot + "lunarbor" wordmark — for
 * the toolkit's `sidebarHeader` slot at the top of the left sidebar.
 *
 * Invoked by `mountAppShell` on every shell rerender via the
 * `sidebarHeader` factory in [AppShell.render], but returns the cached
 * element after the first build (see [appLogoEl]).
 *
 * @return the persistent logo element for the sidebar header.
 */
internal fun buildAppLogo(): HTMLElement {
    appLogoEl?.let { return it }
    val logo = document.createElement("div") as HTMLElement
    logo.id = "app-logo"
    logo.className = "app-logo"
    logo.setAttribute("aria-hidden", "true")
    val row = document.createElement("div") as HTMLElement
    row.className = "app-logo-row"
    val dot = document.createElement("span") as HTMLElement
    dot.id = "app-logo-dot"
    dot.className = "app-logo-dot"
    val wordmark = document.createElement("span") as HTMLElement
    wordmark.className = "app-logo-wordmark"
    // Lowercase, monospaced wordmark (styled in .app-logo-wordmark).
    wordmark.textContent = "lunarbor"
    // Dot first, then wordmark: the status light sits to the LEFT of
    // the "lunarbor" text, matching termtastic's brand row.
    row.appendChild(dot)
    row.appendChild(wordmark)
    logo.appendChild(row)
    appLogoEl = logo
    appLogoDotEl = dot
    // Replay the last known save state so a logo built after the first
    // unsavedFilesFlow emission doesn't sit steady while edits pend.
    applyDotState(dot, appLogoUnsaved)
    return logo
}

/**
 * Applies the aggregate save state to the logo dot: `unsaved = true`
 * starts the breathing pulse, `false` restores the steady light.
 * Called by [AppShell.render]'s collector on every distinct transition
 * of `DocumentRegistry.unsavedFilesFlow`. Safe to call before the logo
 * exists — the state is remembered and replayed by [buildAppLogo].
 *
 * @param unsaved `true` while at least one open document has edits not
 *   yet flushed to disk.
 */
internal fun setAppLogoUnsaved(unsaved: Boolean) {
    appLogoUnsaved = unsaved
    appLogoDotEl?.let { applyDotState(it, unsaved) }
}

/** Toggles the pulse class + JS-driven opacity on the dot element. */
private fun applyDotState(dot: HTMLElement, unsaved: Boolean) {
    if (unsaved) {
        dot.classList.add(UNSAVED_CLASS)
        startPulse(dot)
    } else {
        dot.classList.remove(UNSAVED_CLASS)
        // Drop the JS-driven opacity so the base .app-logo-dot rule
        // paints a solid, fully-opaque steady light.
        dot.style.opacity = ""
    }
}

/* -------------------------------------------------------------------- */
/* Shared rAF pulse driver (termtastic's WebStateActions pattern).       */
/* -------------------------------------------------------------------- */

/** Breathing period, in milliseconds, for the unsaved pulse. */
private const val PULSE_PERIOD_MS = 2500.0

/** Live `requestAnimationFrame` handle for the pulse driver, or 0 when stopped. */
private var pulseRafHandle: Int = 0

/**
 * True when the user has asked for reduced motion; the dot then holds
 * a static full-opacity look instead of breathing.
 */
private fun prefersReducedMotion(): Boolean =
    window.matchMedia("(prefers-reduced-motion: reduce)").matches

/**
 * The breathing opacity for the current instant, derived from the wall
 * clock: 1.0 at the cycle boundaries, easing down to 0.3 at the
 * midpoint and back (smoothstep ≈ ease-in-out). Because it is a pure
 * function of time, a rebuilt dot adopts the correct phase on its
 * first frame — no snap to full brightness.
 *
 * @return an opacity in `[0.3, 1.0]`.
 */
private fun currentPulseOpacity(): Double {
    val phase = (window.performance.now() % PULSE_PERIOD_MS) / PULSE_PERIOD_MS
    val dip = kotlin.math.abs(2.0 * phase - 1.0) // 1 at boundaries, 0 at midpoint
    val eased = dip * dip * (3.0 - 2.0 * dip)    // smoothstep ≈ ease-in-out
    return 0.3 + 0.7 * eased
}

/**
 * One frame of the pulse driver: paints [currentPulseOpacity] onto the
 * dot while it carries [UNSAVED_CLASS], then reschedules itself. Stops
 * (clearing [pulseRafHandle]) once nothing is pulsing, so a fully
 * saved app burns no frames — [startPulse] restarts it on the next
 * unsaved transition.
 */
private fun pulseTick() {
    val dot = appLogoDotEl
    if (dot == null || !dot.classList.contains(UNSAVED_CLASS)) {
        pulseRafHandle = 0
        return
    }
    dot.style.opacity = currentPulseOpacity().toString()
    pulseRafHandle = window.requestAnimationFrame { pulseTick() }
}

/**
 * Seeds the dot with the current breathing opacity and ensures the rAF
 * driver is running. Under reduced motion, holds static full opacity
 * and skips the loop entirely.
 *
 * @param el the dot element that just entered the unsaved state.
 */
private fun startPulse(el: HTMLElement) {
    if (prefersReducedMotion()) {
        el.style.opacity = "1"
        return
    }
    el.style.opacity = currentPulseOpacity().toString()
    if (pulseRafHandle == 0) {
        pulseRafHandle = window.requestAnimationFrame { pulseTick() }
    }
}
