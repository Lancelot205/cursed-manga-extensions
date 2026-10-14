package eu.kanade.tachiyomi.extension.en.hentai2read

import kotlinx.serialization.Serializable

// Inertia payload of /tags/{type} pages. The tag list is in props.tags.
@Serializable
class TagPageDto(private val props: Props) {

    @Serializable
    class Props(val tags: Tags)

    @Serializable
    class Tags(
        val data: List<TagDto>,
        val lastPage: Int,
    )

    val tags get() = props.tags.data
    val lastPage get() = props.tags.lastPage
}

// `type` is the URL segment (category, content).
@Serializable
class TagDto(
    private val id: Int,
    private val name: String,
    val type: String,
) {
    internal fun toFilter() = TagFilter(id, name)
}
