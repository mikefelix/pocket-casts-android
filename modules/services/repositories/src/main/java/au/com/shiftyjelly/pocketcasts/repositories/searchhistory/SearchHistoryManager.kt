package au.com.shiftyjelly.pocketcasts.repositories.searchhistory

import au.com.shiftyjelly.pocketcasts.models.to.SearchHistoryEntry

interface SearchHistoryManager {
    suspend fun findAll(showFolders: Boolean, limit: Int): List<SearchHistoryEntry>
    suspend fun add(entry: SearchHistoryEntry)
    suspend fun remove(entry: SearchHistoryEntry)
    suspend fun clearAll()
}
