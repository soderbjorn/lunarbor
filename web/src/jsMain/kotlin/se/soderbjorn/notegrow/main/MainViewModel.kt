package se.soderbjorn.notegrow.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Platform-layer ViewModel. A thin facade over [DocumentViewBackingViewModel]:
 * re-emits backing state through a stable envelope and delegates every intent
 * one-for-one. Keep this class boring — all logic lives in the backing VM.
 */
class MainViewModel(
    scope: CoroutineScope,
    private val backingViewModel: DocumentViewBackingViewModel
) {
    data class State(val backingState: DocumentViewBackingViewModel.State? = null)

    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    init {
        scope.launch {
            backingViewModel.stateFlow.collect { backing ->
                _stateFlow.value = State(backingState = backing)
            }
        }
    }

    fun insertChar(char: Char) = backingViewModel.insertChar(char)
    fun insertNewline() = backingViewModel.insertNewline()
    fun insertText(text: String) = backingViewModel.insertText(text)
    fun backspace() = backingViewModel.backspace()

    fun moveLeft(extend: Boolean = false) = backingViewModel.moveLeft(extend)
    fun moveRight(extend: Boolean = false) = backingViewModel.moveRight(extend)
    fun moveUp(extend: Boolean = false) = backingViewModel.moveUp(extend)
    fun moveDown(extend: Boolean = false) = backingViewModel.moveDown(extend)
    fun moveTo(row: Int, col: Int, extend: Boolean = false) = backingViewModel.moveTo(row, col, extend)
    fun moveLineStart(extend: Boolean = false) = backingViewModel.moveLineStart(extend)
    fun moveLineEnd(extend: Boolean = false) = backingViewModel.moveLineEnd(extend)
    fun moveWordLeft(extend: Boolean = false) = backingViewModel.moveWordLeft(extend)
    fun moveWordRight(extend: Boolean = false) = backingViewModel.moveWordRight(extend)
    fun moveDocStart(extend: Boolean = false) = backingViewModel.moveDocStart(extend)
    fun moveDocEnd(extend: Boolean = false) = backingViewModel.moveDocEnd(extend)

    fun indentLine(amount: Int = 2) = backingViewModel.indentLine(amount)
    fun outdentLine(amount: Int = 2) = backingViewModel.outdentLine(amount)
    fun isBulletLine(): Boolean = backingViewModel.isBulletLine()

    fun selectAll() = backingViewModel.selectAll()
    fun selectWord(row: Int, col: Int) = backingViewModel.selectWord(row, col)
    fun selectLine(row: Int) = backingViewModel.selectLine(row)
    fun clearSelection() = backingViewModel.clearSelection()
    fun deleteSelectionIfAny() = backingViewModel.deleteSelectionIfAny()
    fun getSelectedText(): String? = backingViewModel.getSelectedText()
    fun onCutRequested(): String? = backingViewModel.onCutRequested()
}
