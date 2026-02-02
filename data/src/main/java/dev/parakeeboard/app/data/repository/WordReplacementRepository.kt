package dev.parakeeboard.app.data.repository

import dev.parakeeboard.app.data.dao.WordReplacementDao
import dev.parakeeboard.app.data.entity.WordReplacement
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing word replacement rules.
 */
@Singleton
class WordReplacementRepository @Inject constructor(
    private val wordReplacementDao: WordReplacementDao
) {
    
    /** Get all word replacements */
    fun getAllReplacements(): Flow<List<WordReplacement>> =
        wordReplacementDao.getAllReplacements()
    
    /** Get only enabled replacements (for transcription processing) */
    suspend fun getEnabledReplacements(): List<WordReplacement> =
        wordReplacementDao.getEnabledReplacements()
    
    /** Get a single replacement by ID */
    suspend fun getReplacement(id: UUID): WordReplacement? =
        wordReplacementDao.getReplacement(id)
    
    /** Create a new word replacement */
    suspend fun createReplacement(
        original: String,
        replacement: String,
        caseSensitive: Boolean = false,
        enabled: Boolean = true
    ): WordReplacement {
        val wordReplacement = WordReplacement(
            original = original,
            replacement = replacement,
            caseSensitive = caseSensitive,
            enabled = enabled
        )
        wordReplacementDao.insert(wordReplacement)
        return wordReplacement
    }
    
    /** Insert an existing word replacement */
    suspend fun insert(replacement: WordReplacement) =
        wordReplacementDao.insert(replacement)
    
    /** Update a word replacement */
    suspend fun update(replacement: WordReplacement) =
        wordReplacementDao.update(replacement)
    
    /** Toggle enabled state */
    suspend fun setEnabled(id: UUID, enabled: Boolean) =
        wordReplacementDao.setEnabled(id, enabled)
    
    /** Delete a word replacement */
    suspend fun delete(replacement: WordReplacement) =
        wordReplacementDao.delete(replacement)
    
    /** Delete a word replacement by ID */
    suspend fun deleteById(id: UUID) =
        wordReplacementDao.deleteById(id)
    
    /** Get count of all replacements */
    suspend fun getCount(): Int = wordReplacementDao.getCount()
    
    /** Get count of enabled replacements */
    suspend fun getEnabledCount(): Int = wordReplacementDao.getEnabledCount()
    
    /**
     * Apply all enabled word replacements to text.
     * Returns the text with replacements applied.
     */
    suspend fun applyReplacements(text: String): String {
        val replacements = getEnabledReplacements()
        var result = text
        
        for (replacement in replacements) {
            result = if (replacement.caseSensitive) {
                result.replace(replacement.original, replacement.replacement)
            } else {
                result.replace(replacement.original, replacement.replacement, ignoreCase = true)
            }
        }
        
        return result
    }
}
