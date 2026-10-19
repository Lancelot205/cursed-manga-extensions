import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hentai2Read"
    versionCode = 20
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        id = 8314925449740051000L
        lang = "en"
        baseUrl = "https://hentai2read.com"
    }

    deeplink {
        host("hentai2read.com")
        path("/..*")
    }
}
