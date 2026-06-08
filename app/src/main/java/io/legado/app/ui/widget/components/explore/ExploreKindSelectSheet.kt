package io.legado.app.ui.widget.components.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.repository.ExploreRepository
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.button.SmallIconButton
import io.legado.app.ui.widget.components.card.SelectionItemCard
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreKindSelectSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    sourceUrl: String?,
    onSelected: (List<ExploreKind>) -> Unit,
    multiple: Boolean = false,
    initialSelectedTitles: List<String> = emptyList(),
    repository: ExploreRepository = koinInject()
) {
    var kinds by remember { mutableStateOf<List<ExploreKind>>(emptyList()) }
    var selectedTitles by remember(initialSelectedTitles, show) {
        mutableStateOf(initialSelectedTitles.toSet())
    }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(show, sourceUrl) {
        if (show && !sourceUrl.isNullOrBlank()) {
            kinds = repository.getSourceExploreKinds(sourceUrl)
        }
    }

    val filteredKinds = remember(query, kinds) {
        if (query.isBlank()) kinds
        else kinds.filter { kind ->
            kind.title.contains(query, ignoreCase = true)
                || (kind.url?.contains(query, ignoreCase = true) == true)
        }
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        endAction = {
            if (multiple && selectedTitles.isNotEmpty()) {
                SmallIconButton(
                    onClick = {
                        val selectedKinds = kinds.filter { it.title in selectedTitles }
                        onSelected(selectedKinds)
                        onDismissRequest()
                    },
                    imageVector = Icons.Default.Check
                )
            }
        }
    ) {
        Column {
            SearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "选择或搜索分类",
                backgroundColor = LegadoTheme.colorScheme.onSheetContent
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredKinds, key = { it.title }) { kind ->
                    val isSelected = kind.title in selectedTitles
                    SelectionItemCard(
                        title = kind.title,
                        isSelected = isSelected,
                        inSelectionMode = true,
                        containerColor = LegadoTheme.colorScheme.onSheetContent,
                        onToggleSelection = {
                            if (multiple) {
                                selectedTitles = if (isSelected) {
                                    selectedTitles - kind.title
                                } else {
                                    selectedTitles + kind.title
                                }
                            } else {
                                onSelected(listOf(kind))
                                onDismissRequest()
                            }
                        }
                    )
                }
            }
        }
    }
}
