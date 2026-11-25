package io.github.brickystudios.tape

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** What was typed and found; lives in the app, so coming back from a stock's page shows the same results. */
class SearchState {
    var query by mutableStateOf("")
    var found by mutableStateOf<List<Favorite>>(emptyList())
    var failed by mutableStateOf(false)
    /** Focus the field only the first time, not on every return from a result. */
    var fresh = true
}

/** Any stock or ETF on Yahoo by name, symbol or ISIN; results come while typing. Tapping one opens its page. */
@Composable
fun Search(s: SearchState, favorites: List<Favorite>, onOpen: (Favorite) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (s.fresh) { s.fresh = false; focus.requestFocus() } }
    LaunchedEffect(s.query) {
        val q = s.query.trim()
        if (q.isEmpty()) { s.found = emptyList(); s.failed = false; return@LaunchedEffect }
        delay(300) // one request when typing pauses, not one per letter
        searching = true
        val r = Wire.lookup(q)
        searching = false
        s.failed = r.isFailure
        r.onSuccess { s.found = it }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.search_close), tint = Text1) }
            OutlinedTextField(
                value = s.query,
                onValueChange = { s.query = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(stringResource(R.string.search_hint), color = Text3) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Text1, unfocusedTextColor = Text1, cursorColor = Amber,
                    focusedContainerColor = Raised, unfocusedContainerColor = Raised,
                    focusedBorderColor = Hairline, unfocusedBorderColor = Hairline,
                ),
            )
        }
        val q = s.query.trim()
        val note = when {
            q.isEmpty() -> stringResource(R.string.search_note)
            s.failed -> stringResource(R.string.offline)
            !searching && s.found.isEmpty() -> stringResource(R.string.nothing_found, q)
            else -> null
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            if (note != null) item { Text(note, style = MetaStyle, modifier = Modifier.padding(vertical = 12.dp)) }
            if (q.isNotEmpty()) items(s.found, key = { "s:" + it.symbol }) { f ->
                val mine = favorites.firstOrNull { it.symbol == f.symbol }
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { keyboard?.hide(); onOpen(f) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(f.symbol, Modifier.width(104.dp).padding(end = 12.dp), fontSize = 16.sp, fontFamily = FontFamily.Monospace, color = Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontSize = 16.sp, color = Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (f.exchange.isNotEmpty()) Text(f.exchange, style = MetaStyle, maxLines = 1)
                    }
                    if (mine != null) {
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(if (mine.own) R.string.follow_owned else R.string.follow_watching), style = MetaStyle.copy(color = Text3))
                    }
                }
                HorizontalDivider(color = Hairline)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
