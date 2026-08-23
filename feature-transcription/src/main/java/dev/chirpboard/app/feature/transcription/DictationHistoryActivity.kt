package dev.chirpboard.app.feature.transcription

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.core.ui.theme.ChirpTheme
import dev.chirpboard.app.core.ui.theme.DynamicColorPreference
import dev.chirpboard.app.core.util.formatRelative
import dev.chirpboard.app.data.dao.DictationHistoryDao
import dev.chirpboard.app.data.entity.DictationHistoryEntry
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dev.chirpboard.app.core.contracts.R as ContractsR

private const val TAG = "DictationHistory"

/**
 * HIST-1: browsing surface for the capped dictation-history table. Reached from the
 * quick-input notification's History action and from keyboard settings; exists so a
 * dictation dropped by the target app (or a timed-out notification) stays recoverable.
 * Tap copies an entry; entries can be deleted one at a time or cleared together.
 */
@AndroidEntryPoint
class DictationHistoryActivity : ComponentActivity() {
    @Inject lateinit var dynamicColorPreference: DynamicColorPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val useDynamicColor by dynamicColorPreference.useDynamicColor
                .collectAsStateWithLifecycle(initialValue = DynamicColorPreference.DEFAULT_USE_DYNAMIC_COLOR)
            ChirpTheme(dynamicColor = useDynamicColor) {
                DictationHistoryScreen(onBack = { finish() })
            }
        }
    }
}

@HiltViewModel
class DictationHistoryViewModel
    @Inject
    constructor(
        private val dao: DictationHistoryDao,
    ) : ViewModel() {
        /** null while the first query is in flight so the empty state doesn't flash. */
        val entries: StateFlow<List<DictationHistoryEntry>?> =
            dao
                .observeAll()
                .map<List<DictationHistoryEntry>, List<DictationHistoryEntry>?> { it }
                // A disk-level read failure degrades to the empty list instead of an
                // uncaught SQLiteException tearing the activity down.
                .catch { error ->
                    Log.e(TAG, "Could not load dictation history", error)
                    emit(emptyList())
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        fun delete(id: Long) {
            viewModelScope.launch {
                runCatching { dao.deleteById(id) }
                    .onFailure { error -> Log.e(TAG, "Could not delete history entry", error) }
            }
        }

        fun clearAll() {
            viewModelScope.launch {
                runCatching { dao.deleteAll() }
                    .onFailure { error -> Log.e(TAG, "Could not clear dictation history", error) }
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictationHistoryScreen(
    onBack: () -> Unit,
    viewModel: DictationHistoryViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dictation_history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.dictation_history_back),
                        )
                    }
                },
                actions = {
                    if (!entries.isNullOrEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.dictation_history_clear_all),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { innerPadding ->
        val current = entries
        when {
            current == null -> Box(modifier = Modifier.fillMaxSize().padding(innerPadding))

            current.isEmpty() ->
                DictationHistoryEmptyState(modifier = Modifier.fillMaxSize().padding(innerPadding))

            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            start = ChirpSpacing.Large,
                            end = ChirpSpacing.Large,
                            top = innerPadding.calculateTopPadding() + ChirpSpacing.Small,
                            bottom = innerPadding.calculateBottomPadding() + ChirpSpacing.Large,
                        ),
                    verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Medium),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.dictation_history_tap_to_copy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = ChirpSpacing.ExtraSmall),
                        )
                    }
                    items(current, key = { it.id }) { entry ->
                        DictationHistoryCard(
                            entry = entry,
                            onCopy = { text -> copyDictationText(context, text) },
                            onDelete = { viewModel.delete(entry.id) },
                        )
                    }
                }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.dictation_history_clear_confirm_title)) },
            text = { Text(stringResource(R.string.dictation_history_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clearAll()
                    },
                ) {
                    Text(stringResource(R.string.dictation_history_clear_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.dictation_history_clear_cancel))
                }
            },
        )
    }
}

@Composable
private fun DictationHistoryEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = ChirpSpacing.ExtraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(ChirpSpacing.Large))
        Text(
            text = stringResource(R.string.dictation_history_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(ChirpSpacing.Small))
        Text(
            text = stringResource(R.string.dictation_history_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DictationHistoryCard(
    entry: DictationHistoryEntry,
    onCopy: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val preferredText = entry.processedText ?: entry.rawText
    val showRawSection = entry.processedText != null && entry.rawText != entry.processedText
    Card(
        onClick = { onCopy(preferredText) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = ChirpSpacing.Large,
                        end = ChirpSpacing.Small,
                        top = ChirpSpacing.Medium,
                        bottom = ChirpSpacing.Medium,
                    ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dictationHistoryTimestamp(entry.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (showRawSection) {
                    IconButton(onClick = { onCopy(entry.rawText) }) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.dictation_history_copy_original),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.dictation_history_delete_entry),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = preferredText,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (showRawSection) {
                Spacer(modifier = Modifier.height(ChirpSpacing.Small))
                Text(
                    text = stringResource(R.string.quick_input_result_raw_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(ChirpSpacing.ExtraSmall))
                Text(
                    text = entry.rawText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Today · 2:41 PM" style label; day resolution from the shared [formatRelative] helper. */
@Composable
private fun dictationHistoryTimestamp(createdAt: Date): String {
    val context = LocalContext.current
    val today = stringResource(ContractsR.string.date_today)
    val yesterday = stringResource(ContractsR.string.date_yesterday)
    return remember(createdAt, today, yesterday) {
        val day = createdAt.formatRelative(today, yesterday)
        val time = android.text.format.DateFormat.getTimeFormat(context).format(createdAt)
        "$day · $time"
    }
}

/**
 * Direct clipboard write: unlike the notification actions (which need
 * [QuickInputCopyActivity] to gain focus first), this runs inside an already-focused
 * activity, so the write is honored and Android's own confirmation overlay shows.
 */
private fun copyDictationText(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(context.getString(R.string.dictation_history_title), text)
    clip.description.extras =
        PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    try {
        clipboard.setPrimaryClip(clip)
    } catch (error: RuntimeException) {
        Log.e(TAG, "Could not copy history entry", error)
    }
}
