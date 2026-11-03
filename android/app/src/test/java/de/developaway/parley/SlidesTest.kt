package de.developaway.parley

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Call slides (docs/CALL-SLIDES.md): the host's `slides` frame, and which one is due when. */
class SlidesTest {
    // As windows/Slides.cs writes it (nulls left out).
    private val frame = Frames.parse("""{"t":"slides","items":[
        {"id":"slide-ab-0","at":0.4,"kind":"metric","title":"Heute","metric":"18","note":"Commits"},
        {"id":"slide-ab-1","at":3.2,"kind":"chart","chart":"pie","labels":["Fixes","Features"],"values":[12,5]},
        {"kind":"text","at":1},
        {"id":"slide-ab-2","at":6.85,"kind":"text","text":"Sprache, WSL"}]}""")

    @Test fun parses() {
        val items = (frame as Incoming.Slides).items
        assertEquals(listOf("slide-ab-0", "slide-ab-1", "slide-ab-2"), items.map { it.id })   // the one without an id is dropped
        assertEquals(Slide("slide-ab-1", 3.2, "chart", chart = "pie", labels = listOf("Fixes", "Features"), values = listOf(12.0, 5.0)), items[1])
        assertEquals("Commits", items[0].note)
    }

    @Test fun theLastOneWhoseTimeHasComeIsDue() {
        val items = (frame as Incoming.Slides).items
        assertNull(Slide.current(items, 0.2))
        assertEquals("slide-ab-0", Slide.current(items, 0.4)?.id)
        assertEquals("slide-ab-1", Slide.current(items, 5.0)?.id)
        assertEquals("slide-ab-2", Slide.current(items, 60.0)?.id)
    }

    @Test fun aSwipedToSlideStaysForTenSeconds() {
        val items = (frame as Incoming.Slides).items
        val due = items[2]
        assertEquals("slide-ab-2", Slide.shown(items, due, null, 0, 50_000).id)            // nothing picked: the voice's
        assertEquals("slide-ab-0", Slide.shown(items, due, "slide-ab-0", 40_000, 49_999).id) // swiped back 9.999 s ago
        assertEquals("slide-ab-2", Slide.shown(items, due, "slide-ab-0", 40_000, 50_000).id) // 10 s on: follows the voice
        assertEquals("slide-ab-2", Slide.shown(items, due, "gone", 49_000, 50_000).id)       // a slide of an older line
    }

    @Test fun onlyTheCallsLineGetsThem() {
        assertEquals(true, CallLine.step(frame, onLine = true, share = false, canRing = true, idle = false) is CallStep.Slides)
        assertNull(CallLine.step(frame, onLine = false, share = true, canRing = true, idle = false))
    }
}
