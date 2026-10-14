package eu.kanade.tachiyomi.extension.en.hentai2read

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.Serializable

@Serializable
class ReaderDto(private val props: Props) {

    @Serializable
    class Props(
        val images: List<String?>? = null,
        val avif: List<String?>? = null,
    )

    // `images` holds the webp pages; `avif` repeats the same pages, so only one list is used.
    fun toPages(): List<Page> = props.images.orEmpty().ifEmpty { props.avif.orEmpty() }.filterNotNull().mapIndexed { index, url ->
        Page(index, imageUrl = url)
    }
}
