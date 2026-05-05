/*
 * HotkeysModal.kt (jsMain)
 * ------------------------
 * Read-only popup that lists every keyboard shortcut the app responds to,
 * grouped by area (editor / navigation / window chrome / app). Opened
 * from the macOS application menu (Notegrow → Hotkeys…) via the
 * `notegrow:show-hotkeys` IPC channel exposed by the Electron preload as
 * `globalThis.darknessApi.onShowHotkeys`. A single instance lives on the
 * [AppShell] and is reused across opens.
 *
 * Visual style mirrors [StarredModal] — same backdrop / panel chrome /
 * close button / ESC dismiss — so the two modals feel like part of the
 * same family. Each row pairs a small leading icon with a chord glyph
 * (rendered with stylised key caps) and a one-line description.
 *
 * Source of truth: the chord list in [HOTKEY_GROUPS] is a hand-curated
 * snapshot of what's actually wired up in [MainScreen.handleKey],
 * [AppShell.installPaletteShortcut], and the darkness-toolkit
 * `StandardHotkeys` registrations (LayoutRenderer / TabBar). When you
 * add or rename a shortcut in those files, update this list too — the
 * modal does not introspect bindings at runtime.
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * Single-instance, lazily-shown hotkeys cheatsheet.
 *
 * Lifetime: created once on first open and kept around for re-opens —
 * the panel rebuilds its DOM on each [open] so layout adapts to any
 * theme/font change between opens.
 */
internal class HotkeysModal {
    private var backdropEl: HTMLElement? = null
    private var documentKeyDownHandler: ((Event) -> Unit)? = null

    /** Open the modal. If already open, the previous instance is closed
     *  first so a re-trigger from the menu always lands on a fresh panel. */
    fun open() {
        if (backdropEl != null) closeInternal()

        val backdrop = buildBackdrop()
        val panel = buildPanel()
        backdrop.appendChild(panel)
        document.body?.appendChild(backdrop)
        backdropEl = backdrop

        attachEscDismiss()
    }

    /** Close the modal. Idempotent. */
    fun close() {
        closeInternal()
    }

    // ----------------------------------------------------------------- DOM

    private fun buildBackdrop(): HTMLElement {
        val b = document.createElement("div") as HTMLElement
        b.className = "notegrow-hotkeys-backdrop"
        b.addEventListener("mousedown", { e ->
            if ((e as MouseEvent).target === b) {
                e.preventDefault()
                e.stopPropagation()
                closeInternal()
            }
        })
        return b
    }

    private fun buildPanel(): HTMLElement {
        val panel = document.createElement("div") as HTMLElement
        panel.className = "notegrow-hotkeys-panel"
        panel.setAttribute("role", "dialog")
        panel.setAttribute("aria-modal", "true")
        panel.setAttribute("aria-label", "Keyboard shortcuts")

        panel.appendChild(buildHeader())
        panel.appendChild(buildBody())

        return panel
    }

    private fun buildHeader(): HTMLElement {
        val header = document.createElement("div") as HTMLElement
        header.className = "notegrow-hotkeys-header"

        val title = document.createElement("div") as HTMLElement
        title.className = "notegrow-hotkeys-title"
        title.textContent = "Keyboard shortcuts"
        header.appendChild(title)

        val closeBtn = document.createElement("button") as HTMLElement
        closeBtn.className = "notegrow-hotkeys-close"
        closeBtn.setAttribute("type", "button")
        closeBtn.title = "Close"
        closeBtn.setAttribute("aria-label", "Close")
        closeBtn.innerHTML = "&times;"
        closeBtn.addEventListener("click", { e ->
            (e as MouseEvent).preventDefault()
            e.stopPropagation()
            closeInternal()
        })
        header.appendChild(closeBtn)

        return header
    }

    private fun buildBody(): HTMLElement {
        val body = document.createElement("div") as HTMLElement
        body.className = "notegrow-hotkeys-body"

        for (group in HOTKEY_GROUPS) {
            val section = document.createElement("section") as HTMLElement
            section.className = "notegrow-hotkeys-group"

            val groupTitle = document.createElement("div") as HTMLElement
            groupTitle.className = "notegrow-hotkeys-group-title"
            groupTitle.textContent = group.title
            section.appendChild(groupTitle)

            val list = document.createElement("div") as HTMLElement
            list.className = "notegrow-hotkeys-list"
            for (entry in group.entries) {
                list.appendChild(buildEntryRow(entry))
            }
            section.appendChild(list)

            body.appendChild(section)
        }
        return body
    }

    private fun buildEntryRow(entry: HotkeyEntry): HTMLElement {
        val row = document.createElement("div") as HTMLElement
        row.className = "notegrow-hotkeys-row"

        val icon = document.createElement("span") as HTMLElement
        icon.className = "notegrow-hotkeys-icon"
        icon.innerHTML = entry.iconSvg
        row.appendChild(icon)

        val label = document.createElement("span") as HTMLElement
        label.className = "notegrow-hotkeys-label"
        label.textContent = entry.label
        row.appendChild(label)

        val chord = document.createElement("span") as HTMLElement
        chord.className = "notegrow-hotkeys-chord"
        for (cap in entry.chord) {
            val capEl = document.createElement("kbd") as HTMLElement
            capEl.className = "notegrow-hotkeys-kbd"
            capEl.textContent = cap
            chord.appendChild(capEl)
        }
        row.appendChild(chord)

        return row
    }

    // ----------------------------------------------------------- lifecycle

    private fun closeInternal() {
        backdropEl?.parentNode?.removeChild(backdropEl!!)
        backdropEl = null
        detachEscDismiss()
    }

    private fun attachEscDismiss() {
        val handler: (Event) -> Unit = lambda@ { e ->
            val ke = e as? KeyboardEvent ?: return@lambda
            if (ke.key == "Escape") {
                e.preventDefault()
                e.stopPropagation()
                closeInternal()
            }
        }
        documentKeyDownHandler = handler
        document.addEventListener("keydown", handler, /* capture = */ true)
    }

    private fun detachEscDismiss() {
        documentKeyDownHandler?.let {
            document.removeEventListener("keydown", it, /* capture = */ true)
        }
        documentKeyDownHandler = null
    }

    // -------------------------------------------------------- data model

    /**
     * One row in the modal: a small leading icon, a human-readable label,
     * and the chord rendered as a sequence of stylised key caps (each
     * `chord` element becomes its own `<kbd>`).
     */
    private data class HotkeyEntry(
        val label: String,
        val chord: List<String>,
        val iconSvg: String,
    )

    /** Heading + list of entries, rendered as one section. */
    private data class HotkeyGroup(
        val title: String,
        val entries: List<HotkeyEntry>,
    )

    companion object {
        /**
         * Stylesheet block injected by [AppShell.ensureNotegrowChromeStyles]
         * so the modal's chrome variables stay in lockstep with
         * [StarredModal]'s. Defined as a const here so the layout can be
         * eyeballed alongside the DOM that consumes it.
         */
        internal const val STYLESHEET: String = """
            .notegrow-hotkeys-backdrop {
                position: fixed;
                inset: 0;
                background: rgba(0, 0, 0, 0.45);
                z-index: 2147483640;
                display: flex;
                align-items: center;
                justify-content: center;
            }
            .notegrow-hotkeys-panel {
                width: min(640px, 92vw);
                max-height: 85vh;
                display: flex;
                flex-direction: column;
                background: var(--t-terminal-bg, #1e1e1e);
                color: var(--t-terminal-fg, #e6e6e6);
                border: 3px solid var(--t-accent-primary, #5ab0ff);
                border-radius: 14px;
                box-shadow:
                    0 0 0 1px rgba(0, 0, 0, 0.65),
                    0 0 0 6px color-mix(in srgb, var(--t-accent-primary, #5ab0ff) 22%, transparent),
                    0 1px 0 rgba(255, 255, 255, 0.06) inset,
                    0 28px 72px rgba(0, 0, 0, 0.65),
                    0 10px 24px rgba(0, 0, 0, 0.45);
                overflow: hidden;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            }
            .notegrow-hotkeys-header {
                display: flex;
                align-items: center;
                gap: 12px;
                padding: 10px 12px;
                border-bottom: 1px solid var(--t-border, rgba(255, 255, 255, 0.08));
            }
            .notegrow-hotkeys-title {
                font-size: 14px;
                font-weight: 600;
                opacity: 0.85;
                margin-right: auto;
            }
            .notegrow-hotkeys-close {
                background: transparent;
                border: none;
                color: inherit;
                font-size: 22px;
                line-height: 1;
                padding: 0 4px;
                cursor: pointer;
                opacity: 0.7;
            }
            .notegrow-hotkeys-close:hover { opacity: 1; }
            .notegrow-hotkeys-body {
                flex: 1 1 auto;
                min-height: 0;
                overflow-y: auto;
                padding: 8px 4px 14px;
            }
            .notegrow-hotkeys-group {
                padding: 6px 14px 4px;
            }
            .notegrow-hotkeys-group-title {
                font-size: 11px;
                font-weight: 600;
                letter-spacing: 0.08em;
                text-transform: uppercase;
                opacity: 0.55;
                padding: 10px 4px 6px;
            }
            .notegrow-hotkeys-list {
                display: flex;
                flex-direction: column;
            }
            .notegrow-hotkeys-row {
                display: grid;
                grid-template-columns: 22px 1fr auto;
                align-items: center;
                gap: 12px;
                padding: 7px 6px;
                border-radius: 6px;
            }
            .notegrow-hotkeys-row:hover {
                background: rgba(255, 255, 255, 0.04);
            }
            .notegrow-hotkeys-icon {
                display: inline-flex;
                width: 16px;
                height: 16px;
                opacity: 0.78;
                justify-content: center;
            }
            .notegrow-hotkeys-label {
                font-size: 13px;
                opacity: 0.92;
            }
            .notegrow-hotkeys-chord {
                display: inline-flex;
                gap: 4px;
                align-items: center;
            }
            .notegrow-hotkeys-kbd {
                display: inline-flex;
                align-items: center;
                justify-content: center;
                min-width: 22px;
                padding: 2px 6px;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                font-size: 11px;
                line-height: 1;
                color: var(--t-text-primary, #e6e6e6);
                background: var(--t-surface-overlay, rgba(255, 255, 255, 0.08));
                border: 1px solid var(--t-border, rgba(255, 255, 255, 0.18));
                border-bottom-width: 2px;
                border-radius: 4px;
            }
        """

        /**
         * Resolves the right modifier glyphs for the current platform on
         * read. Macs use the conventional Unicode glyphs (⌘ ⌥ ⌃ ⇧); on
         * Windows/Linux we spell out `Ctrl` / `Alt` / `Shift` / `Win` so
         * users see what their keyboard actually has.
         *
         * The toolkit's `StandardHotkeys` use *physical* modifier flags
         * (`ctrl/alt`), so on macOS we render those as `⌃ ⌥` — not as
         * `⌘ ⌥` — to stay accurate to what a user has to actually press.
         */
        private val IS_MAC: Boolean = run {
            val ua = js("(typeof navigator !== 'undefined' && navigator.userAgent) || ''") as String
            ua.contains("Mac") || ua.contains("iPhone") || ua.contains("iPad")
        }
        private val CMD: String = if (IS_MAC) "⌘" else "Win"
        private val OPT: String = if (IS_MAC) "⌥" else "Alt"
        private val CTRL: String = if (IS_MAC) "⌃" else "Ctrl"
        private val SHIFT: String = if (IS_MAC) "⇧" else "Shift"

        // Small, theme-aware leading icons (16px). Stroked so currentColor
        // picks up the surrounding text color.
        private const val ICON_ZOOM_IN: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><circle cx=\"11\" cy=\"11\" r=\"7\"/>" +
                "<line x1=\"11\" y1=\"8\" x2=\"11\" y2=\"14\"/>" +
                "<line x1=\"8\" y1=\"11\" x2=\"14\" y2=\"11\"/>" +
                "<line x1=\"20\" y1=\"20\" x2=\"16.5\" y2=\"16.5\"/></svg>"
        private const val ICON_ZOOM_OUT: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><circle cx=\"11\" cy=\"11\" r=\"7\"/>" +
                "<line x1=\"8\" y1=\"11\" x2=\"14\" y2=\"11\"/>" +
                "<line x1=\"20\" y1=\"20\" x2=\"16.5\" y2=\"16.5\"/></svg>"
        private const val ICON_ARROW_LEFT: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><line x1=\"19\" y1=\"12\" x2=\"5\" y2=\"12\"/>" +
                "<polyline points=\"12 19 5 12 12 5\"/></svg>"
        private const val ICON_ARROW_RIGHT: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><line x1=\"5\" y1=\"12\" x2=\"19\" y2=\"12\"/>" +
                "<polyline points=\"12 5 19 12 12 19\"/></svg>"
        private const val ICON_ARROW_UP: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><line x1=\"12\" y1=\"19\" x2=\"12\" y2=\"5\"/>" +
                "<polyline points=\"19 12 12 5 5 12\"/></svg>"
        private const val ICON_ESC: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><rect x=\"3\" y=\"7\" width=\"18\" height=\"10\" rx=\"2\"/>" +
                "<line x1=\"8\" y1=\"11\" x2=\"8\" y2=\"13\"/>" +
                "<line x1=\"12\" y1=\"11\" x2=\"12\" y2=\"13\"/>" +
                "<line x1=\"16\" y1=\"11\" x2=\"16\" y2=\"13\"/></svg>"
        private const val ICON_BOLD: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><path d=\"M7 5h6a3.5 3.5 0 0 1 0 7H7z\"/>" +
                "<path d=\"M7 12h7a3.5 3.5 0 0 1 0 7H7z\"/></svg>"
        private const val ICON_ITALIC: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><line x1=\"19\" y1=\"5\" x2=\"11\" y2=\"5\"/>" +
                "<line x1=\"13\" y1=\"19\" x2=\"5\" y2=\"19\"/>" +
                "<line x1=\"15\" y1=\"5\" x2=\"9\" y2=\"19\"/></svg>"
        private const val ICON_INDENT: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><line x1=\"3\" y1=\"6\" x2=\"21\" y2=\"6\"/>" +
                "<line x1=\"9\" y1=\"12\" x2=\"21\" y2=\"12\"/>" +
                "<line x1=\"9\" y1=\"18\" x2=\"21\" y2=\"18\"/>" +
                "<polyline points=\"3 9 6 12 3 15\"/></svg>"
        private const val ICON_SELECT_ALL: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><rect x=\"4\" y=\"4\" width=\"16\" height=\"16\" rx=\"2\" " +
                "stroke-dasharray=\"3 3\"/><rect x=\"8\" y=\"8\" width=\"8\" height=\"8\" rx=\"1\"/></svg>"
        private const val ICON_UNDO: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><polyline points=\"4 9 9 9 9 4\"/>" +
                "<path d=\"M4 9l4-4a8 8 0 1 1-2 13\"/></svg>"
        private const val ICON_REDO: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><polyline points=\"20 9 15 9 15 4\"/>" +
                "<path d=\"M20 9l-4-4a8 8 0 1 0 2 13\"/></svg>"
        private const val ICON_PALETTE: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"9\"/>" +
                "<circle cx=\"7.5\" cy=\"10.5\" r=\"1.2\" fill=\"currentColor\" stroke=\"none\"/>" +
                "<circle cx=\"12\" cy=\"7\" r=\"1.2\" fill=\"currentColor\" stroke=\"none\"/>" +
                "<circle cx=\"16.5\" cy=\"10.5\" r=\"1.2\" fill=\"currentColor\" stroke=\"none\"/>" +
                "<circle cx=\"15\" cy=\"15\" r=\"1.2\" fill=\"currentColor\" stroke=\"none\"/></svg>"
        private const val ICON_PANE: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><rect x=\"3\" y=\"4\" width=\"8\" height=\"16\" rx=\"1\"/>" +
                "<rect x=\"13\" y=\"4\" width=\"8\" height=\"16\" rx=\"1\"/></svg>"
        private const val ICON_TAB: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><path d=\"M3 8h6l2-3h10v14H3z\"/></svg>"
        private const val ICON_STARRED: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><polygon points=\"12 2 15 9 22 9.5 17 14.5 " +
                "18.5 21.5 12 18 5.5 21.5 7 14.5 2 9.5 9 9\"/></svg>"
        private const val ICON_NAVIGATE: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\"><circle cx=\"11\" cy=\"11\" r=\"6\"/>" +
                "<line x1=\"15.5\" y1=\"15.5\" x2=\"20\" y2=\"20\"/></svg>"

        /**
         * Curated chord list, grouped for display. Groups render in this
         * order. Reflects what's actually wired in the codebase:
         *
         * - Editor: [MainScreen.handleKey] inline-style + Tab/Esc/Cmd-A/Cmd-Z/Cmd-Y.
         * - Outline navigation: [MainScreen.handleKey] Opt-Cmd zoom set.
         * - Window chrome: darkness-toolkit `StandardHotkeys`
         *   (LayoutRenderer / TabBar registrations).
         * - App: [AppShell.installPaletteShortcut],
         *   [AppShell.installNavigateToShortcut],
         *   [AppShell.installStarredShortcut],
         *   [AppShell.installHotkeysShortcut] + the Hotkeys menu item.
         */
        private val HOTKEY_GROUPS: List<HotkeyGroup> = listOf(
            HotkeyGroup(
                title = "Outline navigation",
                entries = listOf(
                    HotkeyEntry(
                        label = "Zoom into bullet (or open linked page)",
                        chord = listOf(OPT, CMD, "⏎"),
                        iconSvg = ICON_ZOOM_IN,
                    ),
                    HotkeyEntry(
                        label = "Zoom out one level",
                        chord = listOf(OPT, CMD, "↑"),
                        iconSvg = ICON_ARROW_UP,
                    ),
                    HotkeyEntry(
                        label = "Clear zoom (back to root)",
                        chord = listOf("Esc"),
                        iconSvg = ICON_ESC,
                    ),
                    HotkeyEntry(
                        label = "Back through zoom history",
                        chord = listOf(OPT, CMD, "←"),
                        iconSvg = ICON_ARROW_LEFT,
                    ),
                    HotkeyEntry(
                        label = "Forward through zoom history",
                        chord = listOf(OPT, CMD, "→"),
                        iconSvg = ICON_ARROW_RIGHT,
                    ),
                ),
            ),
            HotkeyGroup(
                title = "Editor",
                entries = listOf(
                    HotkeyEntry(
                        label = "Bold",
                        chord = listOf(CMD, "B"),
                        iconSvg = ICON_BOLD,
                    ),
                    HotkeyEntry(
                        label = "Italic",
                        chord = listOf(CMD, "I"),
                        iconSvg = ICON_ITALIC,
                    ),
                    HotkeyEntry(
                        label = "Indent bullet",
                        chord = listOf("Tab"),
                        iconSvg = ICON_INDENT,
                    ),
                    HotkeyEntry(
                        label = "Outdent bullet",
                        chord = listOf(SHIFT, "Tab"),
                        iconSvg = ICON_INDENT,
                    ),
                    HotkeyEntry(
                        label = "Select all (clamped to zoom)",
                        chord = listOf(CMD, "A"),
                        iconSvg = ICON_SELECT_ALL,
                    ),
                    HotkeyEntry(
                        label = "Undo",
                        chord = listOf(CMD, "Z"),
                        iconSvg = ICON_UNDO,
                    ),
                    HotkeyEntry(
                        label = "Redo",
                        chord = listOf(SHIFT, CMD, "Z"),
                        iconSvg = ICON_REDO,
                    ),
                ),
            ),
            HotkeyGroup(
                title = "Panes & tabs",
                entries = listOf(
                    HotkeyEntry(
                        label = "Previous pane",
                        chord = listOf(CTRL, OPT, "←"),
                        iconSvg = ICON_PANE,
                    ),
                    HotkeyEntry(
                        label = "Next pane",
                        chord = listOf(CTRL, OPT, "→"),
                        iconSvg = ICON_PANE,
                    ),
                    HotkeyEntry(
                        label = "Previous tab",
                        chord = listOf(CTRL, OPT, SHIFT, "←"),
                        iconSvg = ICON_TAB,
                    ),
                    HotkeyEntry(
                        label = "Next tab",
                        chord = listOf(CTRL, OPT, SHIFT, "→"),
                        iconSvg = ICON_TAB,
                    ),
                ),
            ),
            HotkeyGroup(
                title = "App",
                entries = listOf(
                    HotkeyEntry(
                        label = "Open command palette",
                        chord = listOf(CMD, "P"),
                        iconSvg = ICON_PALETTE,
                    ),
                    HotkeyEntry(
                        label = "Navigate to file",
                        chord = listOf(CMD, "O"),
                        iconSvg = ICON_NAVIGATE,
                    ),
                    HotkeyEntry(
                        label = "Open Starred",
                        chord = listOf(CMD, "S"),
                        iconSvg = ICON_STARRED,
                    ),
                    HotkeyEntry(
                        label = "Show this hotkeys cheatsheet",
                        chord = listOf(CMD, "/"),
                        iconSvg = ICON_ZOOM_OUT,
                    ),
                ),
            ),
        )
    }
}
