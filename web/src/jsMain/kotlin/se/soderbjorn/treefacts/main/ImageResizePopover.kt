/*
 * ImageResizePopover.kt (jsMain)
 * ------------------------------
 * Small floating popover anchored next to a clicked inline image. Shows
 * the image's current width (or a placeholder `auto`) and lets the user
 * type a new pixel value; on Apply / Enter, calls back into the host so
 * the model can patch the source `![alt|N](…)` syntax.
 *
 * Deliberately lightweight — no portals, no focus traps, no chrome
 * library: a single backdrop-less div positioned near the anchor with
 * the same styling vocabulary the rest of the modal palette already
 * uses (input + buttons). One instance per editor is reused across
 * clicks; calling [open] on a still-open popover swaps the anchor.
 */

package se.soderbjorn.treefacts.main

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * @param onApply Receives the new width in CSS pixels. `null` means the
 *   user cleared the field (width should default back to natural). The
 *   host is responsible for the actual model edit.
 */
internal class ImageResizePopover(
    private val onApply: (Int?) -> Unit,
) {

    private var rootEl: HTMLElement? = null
    private var inputEl: HTMLInputElement? = null
    private var outsideClickHandler: ((Event) -> Unit)? = null
    private var keyHandler: ((Event) -> Unit)? = null

    /**
     * Open the popover anchored next to [anchor]. [currentWidth] populates
     * the number input as the starting value; `null` shows the input
     * empty so the placeholder "auto" hints at the unset state.
     */
    fun open(anchor: HTMLElement, currentWidth: Int?) {
        close()
        val root = document.createElement("div") as HTMLElement
        root.className = "treefacts-image-popover"
        // Position next to the image, just below its top-left. The
        // popover's own CSS pins it via fixed positioning so it survives
        // editor scroll between layout and apply.
        val rect = anchor.getBoundingClientRect()
        root.style.top = "${rect.bottom + 6}px"
        root.style.left = "${rect.left}px"

        val label = document.createElement("label") as HTMLElement
        label.className = "treefacts-image-popover-label"
        label.textContent = "Width:"

        val input = document.createElement("input") as HTMLInputElement
        input.type = "number"
        input.min = "10"
        input.max = "4000"
        input.step = "10"
        input.placeholder = "auto"
        input.className = "treefacts-image-popover-input"
        currentWidth?.let { input.value = it.toString() }

        val apply = document.createElement("button") as HTMLButtonElement
        apply.type = "button"
        apply.className = "treefacts-image-popover-apply"
        apply.textContent = "Apply"
        apply.addEventListener("click", { _ -> commit() })

        val clear = document.createElement("button") as HTMLButtonElement
        clear.type = "button"
        clear.className = "treefacts-image-popover-clear"
        clear.textContent = "Clear"
        clear.addEventListener("click", { _ ->
            input.value = ""
            commit()
        })

        root.appendChild(label)
        root.appendChild(input)
        root.appendChild(apply)
        root.appendChild(clear)
        document.body?.appendChild(root)

        rootEl = root
        inputEl = input

        // Enter on the input commits; Escape closes without applying.
        val onKey: (Event) -> Unit = lambda@{ e ->
            val ke = e as? KeyboardEvent ?: return@lambda
            when (ke.key) {
                "Enter" -> { ke.preventDefault(); commit() }
                "Escape" -> { ke.preventDefault(); close() }
            }
        }
        input.addEventListener("keydown", onKey)
        keyHandler = onKey

        // Click anywhere outside the popover closes it without applying.
        val onOutside: (Event) -> Unit = lambda@{ e ->
            val target = e.target as? org.w3c.dom.Node ?: return@lambda
            val r = rootEl ?: return@lambda
            if (!r.contains(target)) close()
        }
        outsideClickHandler = onOutside
        // `setTimeout(0)` so this same click doesn't immediately fire
        // the outside-click handler.
        js("setTimeout(function() { document.addEventListener('mousedown', onOutside, true); }, 0)")

        input.focus()
        input.select()
    }

    private fun commit() {
        val raw = inputEl?.value?.trim()
        val parsed = if (raw.isNullOrEmpty()) null else raw.toIntOrNull()?.takeIf { it > 0 }
        close()
        onApply(parsed)
    }

    fun close() {
        outsideClickHandler?.let {
            document.removeEventListener("mousedown", it, /* capture = */ true)
        }
        outsideClickHandler = null
        keyHandler = null
        rootEl?.let { it.parentNode?.removeChild(it) }
        rootEl = null
        inputEl = null
    }
}
