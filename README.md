# WitAnime CloudStream Plugin

A CloudStream 3 extension for [WitAnime (witanime.you)](https://witanime.you) –
an Arabic anime streaming site with Arabic subtitles.

## Features

| Feature | Supported |
|---------|-----------|
| Arabic UI | ✅ |
| Browse home page | ✅ |
| Latest episodes | ✅ |
| Currently airing | ✅ |
| Movies | ✅ |
| OVA / ONA / Specials | ✅ |
| Anime list | ✅ |
| Search | ✅ |
| Episode list | ✅ |
| Video servers | ok.ru · Streamwish · Dailymotion · Videa · yonaplay |

## Video Servers Handled

The site uses the following embed servers (all supported via CloudStream's built-in extractors):

- **ok.ru** — Odnoklassniki video
- **streamwish** — StreamWish player
- **dailymotion** — Dailymotion embed
- **videa** — Videa.hu
- **yonaplay** — Multi-source player (URLs extracted from page source)

## File Structure

```
WitAnime/
├── build.gradle.kts                          ← Plugin metadata
└── src/main/kotlin/com/witanime/
    ├── WitAnimePlugin.kt                     ← Plugin entry point
    └── WitAnimeProvider.kt                   ← Main scraper
```

## How to Build

1. Clone the [CloudStream-Extensions template](https://github.com/recloudstream/cloudstream-extensions).
2. Drop the `WitAnime/` folder into the extensions root.
3. Add `':WitAnime'` to `settings.gradle`.
4. Run:
   ```bash
   ./gradlew WitAnime:make
   ./gradlew WitAnime:deployWithAdb   # optional: push directly to phone
   ```

## How to Install (pre-built)

1. Open CloudStream → **Settings → Extensions → Add repo**.
2. Enter your repo URL, or sideload the generated `.cs3` file directly.

## Notes

- The site is Arabic-only (`lang = "ar"`).
- Episode list construction uses three strategies in order:
  1. Direct `<a href="/episode/…">` links found in the anime page HTML.
  2. Any `/episode/` link containing the anime slug.
  3. Constructing URLs from the episode count (e.g. `/episode/one-piece-الحلقة-1/`).
- If an anime has no episode count visible and the JS-rendered episode list
  is not in the raw HTML, only aired episodes that appear as links will load.
- Video URLs are scraped from `data-embed` attributes, `<iframe>` tags, and
  inline `<script>` JSON – whichever the page uses.

## Disclaimer

This plugin is for personal/educational use only.
Always respect the site's terms of service.
