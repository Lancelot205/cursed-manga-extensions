package eu.kanade.tachiyomi.extension.en.hentai2read

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.textOrNull
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Hentai2Read : KeiSource() {

    override val supportsFilterFetching = true

    override suspend fun getPopularManga(page: Int): MangasPage = client.get("$baseUrl/browse/trending/page-$page").asJsoup().toMangasPage()

    override suspend fun getLatestUpdates(page: Int): MangasPage = client.get("$baseUrl/browse/latest/page-$page").asJsoup().toMangasPage()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.startsWith(PREFIX_ID_SEARCH)) {
            val path = "/" + query.removePrefix(PREFIX_ID_SEARCH).trim('/')
            return MangasPage(listOfNotNull(fetchMangaByPath(path)), false)
        }

        val tags = filters.filterIsInstance<TagGroup>().flatMap { it.state }
        val included = tags.filter { it.isIncluded() }.map { it.id }
        val excluded = tags.filter { it.isExcluded() }.map { it.id }

        val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query)
            filters.firstInstance<TypeFilter>().value.takeIf { it.isNotEmpty() }?.let { addQueryParameter("type", it) }
            filters.firstInstance<StatusFilter>().value.takeIf { it.isNotEmpty() }?.let { addQueryParameter("status", it) }
            filters.firstInstance<SortFilter>().value.takeIf { it.isNotEmpty() }?.let { addQueryParameter("sort", it) }
            if (included.isNotEmpty()) addQueryParameter("with", included.joinToString(","))
            if (excluded.isNotEmpty()) addQueryParameter("without", excluded.joinToString(","))
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()

        return client.get(url).asJsoup().toMangasPage()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val path = url.encodedPath.takeIf { it.length > 1 } ?: return null
        return fetchMangaByPath(path)
    }

    // Returns null when the page isn't a manga page (chapter, tag or search URL, unknown id).
    private suspend fun fetchMangaByPath(path: String): SManga? {
        val document = client.get("$baseUrl$path").asJsoup()
        if (document.selectFirst(TITLE_SELECTOR) == null) return null
        return parseMangaDetails(document).apply { url = path }
    }

    private fun Document.toMangasPage(): MangasPage {
        val mangas = select(MANGA_SELECTOR).map(::mangaFromElement)
        val hasNextPage = selectFirst(NEXT_PAGE_SELECTOR) != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element) = SManga.create().apply {
        val link = element.selectFirst("h3 a")!!
        setUrlWithoutDomain(link.absUrl("href"))
        title = link.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")

        element.selectFirst("a[href^=/artist/]")?.textOrNull()
            ?.takeIf { it != "Unknown" }
            ?.let {
                artist = it
                author = it
            }
    }

    // Details and chapters share one page, so both are always returned.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(resolveUrl(manga.url)).asJsoup()
        if (document.selectFirst(TITLE_SELECTOR) == null) {
            throw Exception("Manga page not found. Migrate or re-add this entry.")
        }
        return SMangaUpdate(
            manga = parseMangaDetails(document).apply { url = manga.url },
            chapters = parseChapters(document),
        )
    }

    private fun parseMangaDetails(document: Document) = SManga.create().apply {
        title = document.selectFirst(TITLE_SELECTOR)!!.text()
        thumbnail_url = document.selectFirst("img[alt^=Cover of]")?.absUrl("src")

        val artists = document.select("dl a[href^=/artist/]")
            .map { it.text() }
            .filter { it.isNotBlank() && it != "Unknown" }
            .joinToString()
            .ifEmpty { null }
        artist = artists
        author = artists

        genre = document.select("ul[aria-label=Tags] a").joinToString { it.text() }
        status = parseStatus(document.infoValue("Status").orEmpty())

        description = buildString {
            fun section(label: String, value: String?) {
                if (value.isNullOrBlank()) return
                if (isNotEmpty()) append("\n\n")
                append(label, ":\n", value)
            }

            section("Alternative Title", document.selectFirst("$TITLE_SELECTOR + p")?.text())
            section("Language", document.infoValue("Language"))
            section("Type", document.infoValue("Type"))
            section("Released", document.infoValue("Released"))
        }
    }

    // Value of a <dt>/<dd> pair in the info list (Artist, Language, Type, Status, Released)
    private fun Document.infoValue(label: String): String? = select("dl dt")
        .firstOrNull { it.text().equals(label, ignoreCase = true) }
        ?.nextElementSibling()
        ?.textOrNull()

    private fun parseStatus(status: String): Int {
        val value = status.lowercase()
        return when {
            "ongoing" in value -> SManga.ONGOING
            "completed" in value -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    // <li id="chapter-ID"> rows: number span, title link, date span ("Oct 11, 2026")
    private fun parseChapters(document: Document): List<SChapter> = document.select("li[id^=chapter-]").map { li ->
        val link = li.selectFirst("a[href^=/reader/]")!!
        val number = li.selectFirst("span")?.text().orEmpty()

        SChapter.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            name = if (number.isNotEmpty()) "Ch. $number - ${link.text()}" else link.text()
            chapter_number = number.toFloatOrNull() ?: -1f
            date_upload = dateFormat.tryParseDate(li.selectFirst("span.text-xs")?.text(), ZoneOffset.UTC)
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(resolveUrl(chapter.url)).asJsoup()
        return document.parsePageData<ReaderDto>().toPages()
    }

    override suspend fun fetchFilterData(): JsonElement = TAG_TYPES.flatMap { fetchTags(it) }.toJsonElement()

    private suspend fun fetchTags(type: String): List<TagDto> {
        val first = client.get("$baseUrl/tags/$type").asJsoup().parsePageData<TagPageDto>()
        val rest = (2..minOf(first.lastPage, MAX_TAG_PAGES)).flatMap { page ->
            client.get("$baseUrl/tags/$type/page-$page").asJsoup().parsePageData<TagPageDto>().tags
        }
        return first.tags + rest
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val tags = data?.parseAs<List<TagDto>>().orEmpty()
        return hentai2readFilters(tags)
    }

    // Inertia page JSON, embedded in <script data-page="app">.
    private inline fun <reified T> Document.parsePageData(): T {
        val script = selectFirst("script[data-page=app]")?.data()
            ?: throw Exception("Page data not found")
        return script.parseAs<T>()
    }

    private fun resolveUrl(path: String): HttpUrl {
        val base = baseUrl.toHttpUrl()
        val url = base.resolve(path)
        if (url == null || url.scheme != base.scheme || url.host != base.host) {
            throw Exception("Invalid URL: $path")
        }
        return url
    }

    companion object {
        private const val MAX_TAG_PAGES = 20
        private const val PREFIX_ID_SEARCH = "id:"

        // Listing cards: <article data-card="true" data-book="ID">. Sponsored tiles use <article data-promo>.
        private const val TITLE_SELECTOR = "h1#book-title"
        private const val MANGA_SELECTOR = "article[data-card][data-book]"
        private const val NEXT_PAGE_SELECTOR = "nav[aria-label=Pages] a[rel=next]"

        private val dateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
    }
}
