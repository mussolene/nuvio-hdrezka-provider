package local.hdrezka

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HDRezkaSearchParserTest {
    @Test
    fun parsesMovieTitleAndYear() {
        val document = Jsoup.parse(
            """
            <div class="b-content__inline_item">
              <a href="https://hdrzk.org/films/horror/88785.html"><img src="poster.jpg"></a>
              <div class="b-content__inline_item-link">
                <a href="https://hdrzk.org/films/horror/88785.html">Закулисье реальности</a>
                <div>2026, США, Ужасы</div>
              </div>
            </div>
            """.trimIndent()
        )

        val card = HDRezkaSearchParser.card(document.selectFirst(".b-content__inline_item")!!)

        assertEquals("Закулисье реальности", card?.title)
        assertEquals(2026, card?.year)
        assertEquals(HDRezkaSearchType.MOVIE, card?.type)
        assertEquals("poster.jpg", card?.posterUrl)
    }

    @Test
    fun parsesSeriesAndOriginalTitle() {
        val search = Jsoup.parse(
            """
            <div class="b-content__inline_item">
              <a href="https://hdrzk.org/series/fiction/89634.html"></a>
              <div class="b-content__inline_item-link">
                <a href="https://hdrzk.org/series/fiction/89634.html">Фонари</a>
                <div>2026, США, Фантастика <span class="info">1 сезон, 2 серия</span></div>
              </div>
            </div>
            """.trimIndent()
        )
        val detail = Jsoup.parse("<div class='b-post__origtitle'>Lanterns</div>")

        val card = HDRezkaSearchParser.card(search.selectFirst(".b-content__inline_item")!!)

        assertEquals(HDRezkaSearchType.SERIES, card?.type)
        assertEquals(2, card?.episode)
        assertEquals("Lanterns", HDRezkaSearchParser.originalTitle(detail))
    }

    @Test
    fun ignoresIncompleteCardsAndChecksLatinQueries() {
        val empty = Jsoup.parse("<div class='b-content__inline_item'></div>")

        assertNull(HDRezkaSearchParser.card(empty.selectFirst("div")!!))
        assertTrue(HDRezkaSearchParser.needsOriginalTitle("Backrooms"))
        assertFalse(HDRezkaSearchParser.needsOriginalTitle("Фонари"))
    }
}
