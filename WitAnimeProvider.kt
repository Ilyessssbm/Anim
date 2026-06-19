package com.witanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class WitAnimeProvider : MainAPI() {

    override var mainUrl  = "https://witanime.you"
    override var name     = "WitAnime"
    override val hasMainPage         = true
    override var lang                = "ar"
    override val hasQuickSearch      = false
    override val hasDownloadSupport  = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    // ─── Main-page sections ──────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/"                                                         to "الرئيسية",
        "$mainUrl/episode/"                                                 to "آخر الحلقات",
        "$mainUrl/anime-type/movie/"                                        to "أفلام الأنمي",
        "$mainUrl/anime-status/%d9%8a%d8%b9%d8%b1%d8%b6-%d8%a7%d9%84%d8%a7%d9%86/" to "يعرض الآن",
        "$mainUrl/anime-type/tv/"                                           to "مسلسلات TV",
        "$mainUrl/anime-type/ova/"                                          to "OVA / ONA",
        "$mainUrl/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d8%a7%d9%86%d9%85%d9%8a/" to "قائمة الأنمي",
    )

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")

    /** Map Arabic / English type strings to CloudStream TvType. */
    private fun inferType(raw: String?): TvType = when {
        raw == null -> TvType.Anime
        raw.contains("movie", ignoreCase = true) ||
                raw.contains("فيلم") -> TvType.AnimeMovie
        raw.contains("OVA", ignoreCase = true) ||
                raw.contains("ONA", ignoreCase = true) ||
                raw.contains("Special", ignoreCase = true) -> TvType.OVA
        else -> TvType.Anime
    }

    /** Best-effort extraction of an img URL, respecting lazy-load attributes. */
    private fun Element.imgSrc(): String? {
        val el = selectFirst("img") ?: return null
        return el.attr("src").takeIf { it.startsWith("http") }
            ?: el.attr("data-src").takeIf { it.startsWith("http") }
            ?: el.attr("data-lazy-src").takeIf { it.startsWith("http") }
    }

    /** Convert a card element (anime or episode card) to a search result. */
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        // Prefer links pointing to /anime/
        val link = selectFirst("a[href*='/anime/']")
            ?: selectFirst("a[href]")
            ?: return null

        val href  = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst("h3")?.text()
            ?: selectFirst(".card-title, .anime-name, .anime-title")?.text()
            ?: link.text()
            ?: return null

        val poster  = imgSrc()
        val typeStr = selectFirst("a[href*='anime-type'], .type-badge, .anime-card-type")?.text()

        return newAnimeSearchResponse(title.trim(), href, inferType(typeStr)) {
            posterUrl = poster
        }
    }

    // ─── getMainPage ─────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            page == 1            -> request.data
            request.data.last() == '/' -> "${request.data}page/$page/"
            else                 -> "${request.data}/page/$page/"
        }

        val doc   = app.get(url).document
        val items = mutableListOf<SearchResponse>()

        // ① Anime cards
        doc.select("div.anime-card").forEach { card ->
            card.toSearchResponse()?.let { items.add(it) }
        }

        // ② Fallback: episode cards (e.g. on /episode/ listing)
        if (items.isEmpty()) {
            doc.select("div.episode-card, div.ep-card, div.episodes-card").forEach { card ->
                // Episode cards link to /episode/, but we want to show anime poster
                val href      = card.selectFirst("a")?.attr("href") ?: return@forEach
                val titleText = card.selectFirst("h3, .episode-title, .anime-name, .card-title")
                    ?.text() ?: ""
                val poster    = card.imgSrc()

                items.add(newAnimeSearchResponse(titleText, href, TvType.Anime) {
                    posterUrl = poster
                })
            }
        }

        // ③ Last resort: any /anime/ link on the page
        if (items.isEmpty()) {
            doc.select("a[href*='/anime/']").distinctBy { it.attr("href") }.forEach { a ->
                val href  = a.attr("href")
                val title = a.text().takeIf { it.isNotBlank() } ?: return@forEach
                items.add(newAnimeSearchResponse(title, href, TvType.Anime))
            }
        }

        return newHomePageResponse(request.name, items)
    }

    // ─── search ──────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc     = app.get("$mainUrl/?s=${query.urlEncode()}").document
        val results = mutableListOf<SearchResponse>()

        doc.select("div.anime-card, div.search-result").forEach { card ->
            card.toSearchResponse()?.let { results.add(it) }
        }

        // Some themes wrap results in <article>
        if (results.isEmpty()) {
            doc.select("article").forEach { card ->
                card.toSearchResponse()?.let { results.add(it) }
            }
        }

        return results
    }

    // ─── load ────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        return if (url.contains("/episode/")) {
            // Navigate to the parent anime page via breadcrumb / back-link
            val animeUrl = doc.selectFirst(
                "a[href*='/anime/']:not([href='$mainUrl/']), " +
                "div.breadcrumb a[href*='/anime/'], " +
                ".back-link a"
            )?.attr("href")

            if (animeUrl != null) load(animeUrl) else quickEpisodeResponse(url, doc)
        } else {
            loadAnimePage(url, doc)
        }
    }

    /** Minimal response when we can't find the parent anime page. */
    private fun quickEpisodeResponse(episodeUrl: String, doc: Document): LoadResponse? {
        val title  = doc.selectFirst("h1, h2, .episode-anime-title")?.text() ?: return null
        val epNum  = Regex("الحلقة[- ]?([\\d.]+)").find(episodeUrl)
            ?.groupValues?.get(1)?.toFloatOrNull()?.toInt() ?: 1

        return newAnimeLoadResponse(title, episodeUrl, TvType.Anime) {
            addEpisodes(DubStatus.Subbed, listOf(
                newEpisode(episodeUrl) { episode = epNum; name = "الحلقة $epNum" }
            ))
        }
    }

    // ─── Anime-page loader ───────────────────────────────────────────────────

    private fun loadAnimePage(url: String, doc: Document): LoadResponse {
        val title = doc.selectFirst(
            "h1.anime-title, .anime-details h1, .anime-title, h1"
        )?.text()?.trim() ?: ""

        val posterUrl = doc.selectFirst(
            ".anime-poster img, .anime-image img, .anime-thumbnail img, .anime-cover img"
        )?.run {
            attr("src").takeIf { it.startsWith("http") }
                ?: attr("data-src").takeIf { it.startsWith("http") }
        } ?: doc.imgSrc()

        val description = doc.selectFirst(
            ".anime-story, .story, .anime-description, .description, p.summary"
        )?.text()?.trim()

        val genres  = doc.select("a[href*='anime-genre']").map { it.text().trim() }
        val typeStr = doc.selectFirst("a[href*='anime-type'], .anime-type")?.text()
        val type    = inferType(typeStr)

        val status = doc.selectFirst("a[href*='anime-status'], .anime-status")
            ?.text()?.let { s ->
                when {
                    "يعرض" in s  -> ShowStatus.Ongoing
                    "مكتمل" in s -> ShowStatus.Completed
                    else         -> null
                }
            }

        // Year: grab first 4-digit year from info text
        val year = Regex("\\b(19|20)\\d{2}\\b")
            .find(doc.selectFirst(".anime-info, .info-list, .anime-details")?.text() ?: "")
            ?.value?.toIntOrNull()

        // Anime slug = last segment of the URL
        val slug     = url.trimEnd('/').substringAfterLast('/')
        val episodes = buildEpisodeList(slug, doc)

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = posterUrl
            plot(description)
            showStatus(status)
            tags(genres)
            year?.let { this.year = it }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ─── Episode-list builder ─────────────────────────────────────────────────

    private fun buildEpisodeList(animeSlug: String, doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // ── Strategy 1: links that contain the anime slug ─────────────────
        val slugLinks = doc.select("a[href*='/episode/$animeSlug']")
        if (slugLinks.isNotEmpty()) {
            slugLinks.forEach { a ->
                val href   = a.attr("href")
                val text   = a.text().trim()
                val epNum  = extractEpNumber(href + " " + text)
                episodes += newEpisode(href) {
                    name    = text.ifBlank { null }
                    episode = epNum
                }
            }
            return episodes.sortedBy { it.episode ?: Int.MAX_VALUE }
        }

        // ── Strategy 2: any /episode/ link on the page ────────────────────
        val anyEpLinks = doc.select("a[href*='/episode/']")
            .filter { el ->
                el.attr("href").contains(animeSlug, ignoreCase = true)
            }
        if (anyEpLinks.isNotEmpty()) {
            anyEpLinks.forEach { a ->
                val href  = a.attr("href")
                val text  = a.text().trim()
                val epNum = extractEpNumber(href + " " + text)
                episodes += newEpisode(href) {
                    name    = text.ifBlank { null }
                    episode = epNum
                }
            }
            return episodes.sortedBy { it.episode ?: Int.MAX_VALUE }
        }

        // ── Strategy 3: construct URLs from episode count ─────────────────
        // Look for the total-episode count in the info block
        val infoText = doc.selectFirst(".anime-info, .info-list, .order")?.text() ?: ""
        val epCount  = Regex("عدد\\s*الحلقات\\D*(\\d+)").find(infoText)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(\\d+)\\s*حلقة").find(infoText)?.groupValues?.get(1)?.toIntOrNull()

        if (epCount != null && epCount in 1..3000) {
            for (i in 1..epCount) {
                // Arabic "الحلقة" = "episode"
                episodes += newEpisode("$mainUrl/episode/$animeSlug-\u0627\u0644\u062d\u0644\u0642\u0629-$i/") {
                    name    = "\u0627\u0644\u062d\u0644\u0642\u0629 $i"
                    episode = i
                }
            }
        }

        return episodes
    }

    /** Pull a float-compatible episode number out of a string like "الحلقة 11" or "ep-11". */
    private fun extractEpNumber(text: String): Int? =
        Regex("""الحلقة[- ]?([0-9]+(?:\.[0-9]+)?)""").find(text)
            ?.groupValues?.get(1)?.toFloatOrNull()?.toInt()
            ?: Regex("""[Ee]p(?:isode)?[- ]?([0-9]+)""").find(text)
                ?.groupValues?.get(1)?.toIntOrNull()

    // ─── loadLinks ───────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // ── 1. data-embed / data-src attributes on server buttons ─────────
        doc.select("[data-embed],[data-src],[data-url],[data-link]").forEach { el ->
            val embedUrl = listOf("data-embed", "data-src", "data-url", "data-link")
                .map { el.attr(it) }
                .firstOrNull { it.startsWith("http") }
                ?: return@forEach
            safeLoadExtractor(embedUrl, data, subtitleCallback, callback)
        }

        // ── 2. Iframes already in the HTML ────────────────────────────────
        doc.select("iframe[src],iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.startsWith("http") }
                ?: iframe.attr("data-src").takeIf { it.startsWith("http") }
                ?: return@forEach
            safeLoadExtractor(src, data, subtitleCallback, callback)
        }

        // ── 3. Scan <script> tags for embedded URLs ───────────────────────
        doc.select("script").forEach { script ->
            val js = script.data().takeIf { it.isNotBlank() } ?: return@forEach

            // Pattern A – {"embed":"https://..."} style
            Regex(""""(?:embed|src|url|link|file)"\s*:\s*"(https?://[^"]+)"""")
                .findAll(js)
                .map { it.groupValues[1] }
                .forEach { safeLoadExtractor(it, data, subtitleCallback, callback) }

            // Pattern B – JS array of server objects
            Regex("""(?:var|const|let)\s+\w+\s*=\s*(\[[\s\S]+?]);""")
                .findAll(js)
                .forEach { match ->
                    tryParseJson<List<Map<String, String>>>(match.groupValues[1])
                        ?.forEach inner@{ srv ->
                            val foundUrl = srv["embed"] ?: srv["url"]
                                ?: srv["src"] ?: srv["link"] ?: return@inner
                            if (foundUrl.startsWith("http"))
                                safeLoadExtractor(foundUrl, data, subtitleCallback, callback)
                        }
                }

            // Pattern C – bare strings that look like embed URLs
            Regex("""(https?://(?:ok\.ru|streamwish\.\w+|dailymotion\.com|videa\.hu|yonaplay\.\w+)/[^\s"'<>]+)""")
                .findAll(js)
                .map { it.groupValues[1] }
                .forEach { safeLoadExtractor(it, data, subtitleCallback, callback) }
        }

        return true
    }

    /** Wraps loadExtractor to swallow individual extractor errors gracefully. */
    private suspend fun safeLoadExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            loadExtractor(url, referer, subtitleCallback, callback)
        } catch (_: Exception) {
            // Skip unavailable or unrecognised extractors silently
        }
    }
}
