package dev.chirpboard.app.feature.studio

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Debounce for handing buffered transcript keystrokes to the ViewModel. It bounds how much
 * typing an unannounced process kill can cost, so it stays well under a second; every ordinary
 * exit from edit mode (save, close, editor disposal, app pause) flushes synchronously instead.
 */
const val TRANSCRIPT_DRAFT_PUSH_DEBOUNCE_MS = 400L

/**
 * Holds transcript keystrokes in Compose state so typing does not round-trip the whole
 * transcript through the ViewModel — a state emission, a screen-wide recomposition, a
 * SavedStateHandle mirror and an oversized-draft job restart — on every character.
 *
 * Only the ViewModel survives process death, so the buffer must never be the sole owner of
 * text for long: [flush] is synchronous and has to run at every point that reads the ViewModel
 * draft (save, close, editor disposal, lifecycle pause), and the editor pushes on a
 * [TRANSCRIPT_DRAFT_PUSH_DEBOUNCE_MS] debounce otherwise. [latestDraft] lets a reader that
 * cannot flush (the dirty check behind the discard prompt) still see the buffered text.
 *
 * [pending] deliberately survives a push: the ViewModel's echo arrives a frame later, so
 * clearing it on flush would render the pre-push text for one frame and jump the cursor.
 */
@Stable
class TranscriptDraftBuffer(private val push: (String) -> Unit) {
    /** Buffered text, or null when the ViewModel's draft is authoritative. */
    private var pending by mutableStateOf<String?>(null)

    private var pushed: String? = null

    fun onTextChanged(next: String) {
        pending = next
    }

    /** The draft as the user sees it: buffered keystrokes outrank the ViewModel's copy. */
    fun latestDraft(viewModelDraft: String): String = pending ?: viewModelDraft

    fun flush() {
        val text = pending ?: return
        if (text == pushed) return
        pushed = text
        push(text)
    }

    /**
     * Drops buffered text without pushing it. Required on the discard path: a later flush
     * would otherwise re-enter edit state in the ViewModel's saved-state mirror.
     */
    fun discard() {
        pending = null
        pushed = null
    }
}
