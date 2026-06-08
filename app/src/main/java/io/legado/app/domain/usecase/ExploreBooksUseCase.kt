package io.legado.app.domain.usecase

import io.legado.app.data.entities.SearchBook
import io.legado.app.data.repository.ExploreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExploreBooksUseCase(
    private val exploreRepository: ExploreRepository,
) {
    companion object {
        /** 排名类模块自动加载的最大书本数 */
        const val MAX_RANKING_BOOKS = 20

        /** 排名类模块自动加载的最大页数 */
        const val MAX_RANKING_PAGES = 3
    }

    suspend fun execute(
        sourceUrl: String,
        moduleUrl: String?,
        args: String?,
        page: Int = 1
    ): ExploreResult = withContext(Dispatchers.IO) {
        val base = exploreRepository.getBookSource(sourceUrl)
            ?: throw SourceNotFound(sourceUrl)
        val source = args?.let { base.copy().also { s -> s.setVariable(it) } } ?: base
        val resolvedUrl = moduleUrl ?: source.exploreUrl
        ?: throw NoExploreUrl(sourceUrl)
        val result = exploreRepository.exploreBook(source, resolvedUrl, page)
        val books = result.getOrThrow()
        ExploreResult(resolvedUrl, books)
    }

    suspend fun executeForRanking(
        sourceUrl: String,
        moduleUrl: String?,
        args: String?
    ): List<SearchBook> = withContext(Dispatchers.IO) {
        val result = execute(sourceUrl, moduleUrl, args)
        var books = result.books
        var page = 1
        while (books.size < MAX_RANKING_BOOKS && page < MAX_RANKING_PAGES) {
            page++
            val next = try {
                val source = exploreRepository.getBookSource(sourceUrl)
                    ?.let { s -> args?.let { s.copy().also { x -> x.setVariable(it) } } ?: s }
                    ?: return@withContext books.take(MAX_RANKING_BOOKS)
                val r = exploreRepository.exploreBook(source, result.resolvedUrl, page)
                r.getOrThrow()
            } catch (_: Exception) {
                emptyList()
            }
            if (next.isEmpty()) break
            books = (books + next)
        }
        books.take(MAX_RANKING_BOOKS)
    }

    data class ExploreResult(val resolvedUrl: String, val books: List<SearchBook>)

    class SourceNotFound(url: String) : Exception("Source not found: ${url.take(60)}")
    class NoExploreUrl(url: String) : Exception("No explore URL for source: ${url.take(60)}")
}
