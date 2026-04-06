package dev.chirpboard.app.data.repository

import dev.chirpboard.app.data.dao.WordReplacementDao
import dev.chirpboard.app.data.entity.WordReplacement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing word replacement rules.
 */
@Singleton
class WordReplacementRepository
    @Inject
    constructor(
        private val wordReplacementDao: WordReplacementDao,
    ) {
        fun getAllReplacements(): Flow<List<WordReplacement>> = wordReplacementDao.getAllReplacements()

        suspend fun getEnabledReplacements(): List<WordReplacement> = wordReplacementDao.getEnabledReplacements()

        suspend fun getReplacement(id: UUID): WordReplacement? = wordReplacementDao.getReplacement(id)

        suspend fun createReplacement(
            original: String,
            replacement: String,
            caseSensitive: Boolean = false,
            enabled: Boolean = true,
        ): WordReplacement {
            val wordReplacement =
                WordReplacement(
                    original = original,
                    replacement = replacement,
                    caseSensitive = caseSensitive,
                    enabled = enabled,
                )
            wordReplacementDao.insert(wordReplacement)
            return wordReplacement
        }

        suspend fun insert(replacement: WordReplacement) = wordReplacementDao.insert(replacement)

        suspend fun update(replacement: WordReplacement) = wordReplacementDao.update(replacement)

        suspend fun setEnabled(
            id: UUID,
            enabled: Boolean,
        ) = wordReplacementDao.setEnabled(id, enabled)

        suspend fun delete(replacement: WordReplacement) = wordReplacementDao.delete(replacement)

        suspend fun deleteById(id: UUID) = wordReplacementDao.deleteById(id)

        suspend fun getCount(): Int = wordReplacementDao.getCount()

        suspend fun getEnabledCount(): Int = wordReplacementDao.getEnabledCount()

        /**
         * Apply all enabled word replacements to text.
         * Returns the text with replacements applied.
         */
        suspend fun applyReplacements(text: String): String = withContext(Dispatchers.Default) {
            val replacements = getEnabledReplacements()
            var result = text

            for (replacement in replacements) {
                result =
                    if (replacement.caseSensitive) {
                        result.replace(replacement.original, replacement.replacement)
                    } else {
                        result.replace(replacement.original, replacement.replacement, ignoreCase = true)
                    }
            }

            result
        }
    }
