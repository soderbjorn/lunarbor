/*
 * OpenGesture.kt (commonMain)
 * ---------------------------
 * What a press on something that opens a place means: go there in this
 * pane, open it in a new window, or nothing (yet). One rule for every
 * link-like target a view draws — vault links and resolved wiki
 * links (in rows and in the page title), bullet dots, search-node result
 * rows, pane search results, the "Linked from" backlinks list, link
 * preview items — so they all answer the same modifiers the same way
 * (LBR-8).
 *
 * The rule:
 * - a plain primary press goes there in place;
 * - Shift-, ⌘- (Meta-) or, off the Mac, Ctrl-press opens a new window;
 * - on the Mac a Ctrl-press is a right-click: the platform fires a
 *   `contextmenu` event after it, and *that* opens the new window. The
 *   press itself must do nothing, or the pane would navigate as well;
 * - any other button is left alone (a right press is handled by the
 *   `contextmenu` that follows it).
 *
 * Pure input classification, kept in commonMain so it is tested
 * ([OpenGestureTest]) and shared by every platform view. No DOM imports.
 */

package se.soderbjorn.lunarbor.main

/**
 * What a press (or click) on a link-like target asks for.
 *
 * ### Callers
 * - web `MainScreen` (links in rows and the title, bullet dots),
 *   `OutlinePaintLoop` (search-node results, link preview items),
 *   `PaneSearchBar` (pane search results) and `BacklinksList`, each
 *   through `openGestureOf(MouseEvent)`.
 */
enum class OpenGesture {
    /** Go to the target in this pane. */
    HERE,

    /** Open the target in a new window; this pane stays where it is. */
    NEW_WINDOW,

    /**
     * Do nothing now, but swallow the press (no caret placement, no
     * drag): a `contextmenu` event follows and opens the new window.
     * The Mac's Ctrl-click.
     */
    CONTEXT_MENU,

    /** Not a press this rule acts on (middle or right button). */
    NONE;

    companion object {
        /**
         * Classifies a mouse press or click.
         *
         * @param button The DOM `MouseEvent.button` (0 = primary,
         *   1 = middle, 2 = secondary).
         * @param shift `shiftKey` of the event.
         * @param meta `metaKey` (⌘ on the Mac, the Windows key elsewhere).
         * @param ctrl `ctrlKey` of the event.
         * @param isMac `true` on macOS, where Ctrl-click is a right-click
         *   and fires `contextmenu`.
         * @return [HERE] for a plain primary press, [NEW_WINDOW] for a
         *   modified one, [CONTEXT_MENU] for the Mac's Ctrl-press,
         *   [NONE] for any other button. Alt (⌥) is ignored.
         */
        fun of(button: Int, shift: Boolean, meta: Boolean, ctrl: Boolean, isMac: Boolean): OpenGesture = when {
            button != 0 -> NONE
            ctrl && isMac -> CONTEXT_MENU
            shift || meta || ctrl -> NEW_WINDOW
            else -> HERE
        }
    }
}
