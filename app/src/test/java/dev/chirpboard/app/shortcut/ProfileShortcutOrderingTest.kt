package dev.chirpboard.app.shortcut

import dev.chirpboard.app.data.entity.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Pure-JVM coverage for [ProfileShortcutManager.orderedForShortcuts]: the pinned-then-recent
 * ordering and the per-activity cap that decide which profiles become dynamic launcher shortcuts.
 */
class ProfileShortcutOrderingTest {
    private fun profile(
        name: String,
        sortOrder: Int = 0,
        pinned: Boolean = false,
    ): Profile =
        Profile(
            id = UUID.randomUUID(),
            name = name,
            sortOrder = sortOrder,
            isQuickStartPinned = pinned,
        )

    @Test
    fun `pinned profiles sort ahead of unpinned`() {
        val pinned = profile(name = "Pinned", sortOrder = 9, pinned = true)
        val unpinned = profile(name = "Unpinned", sortOrder = 0)

        val ordered =
            ProfileShortcutManager.orderedForShortcuts(
                listOf(unpinned, pinned),
                maxCount = 5,
            )

        assertEquals(listOf(pinned, unpinned), ordered)
    }

    @Test
    fun `unpinned profiles keep sortOrder then name ordering`() {
        val first = profile(name = "Alpha", sortOrder = 1)
        val second = profile(name = "Bravo", sortOrder = 2)
        val tieA = profile(name = "Aaa", sortOrder = 2)

        val ordered =
            ProfileShortcutManager.orderedForShortcuts(
                listOf(second, tieA, first),
                maxCount = 5,
            )

        // sortOrder 1 first, then the sortOrder-2 pair broken by name (Aaa before Bravo).
        assertEquals(listOf(first, tieA, second), ordered)
    }

    @Test
    fun `list is capped at maxCount keeping the highest-priority entries`() {
        val pinned = profile(name = "Pinned", sortOrder = 99, pinned = true)
        val early = profile(name = "Early", sortOrder = 0)
        val late = profile(name = "Late", sortOrder = 50)

        val ordered =
            ProfileShortcutManager.orderedForShortcuts(
                listOf(early, late, pinned),
                maxCount = 2,
            )

        assertEquals(2, ordered.size)
        assertEquals(listOf(pinned, early), ordered)
    }

    @Test
    fun `non-positive maxCount yields no shortcuts`() {
        val ordered =
            ProfileShortcutManager.orderedForShortcuts(
                listOf(profile(name = "Anything")),
                maxCount = 0,
            )

        assertTrue(ordered.isEmpty())
    }

    @Test
    fun `shortcut id is namespaced by profile uuid`() {
        val p = profile(name = "Notes")
        assertEquals("profile_${p.id}", ProfileShortcutManager.shortcutId(p))
    }
}
