package io.github.brickystudios.tape

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.net.URL

@Composable
fun Setup(now: Long, onFavorites: (List<Favorite>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store(ctx) }
    var favorites by remember { mutableStateOf(store.favorites) }
    var feeds by remember { mutableStateOf(store.feeds) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Favorite>>(emptyList()) }
    var lookupNote by remember { mutableStateOf<String?>(null) }
    var feedUrl by remember { mutableStateOf("") }
    var feedNote by remember { mutableStateOf<Int?>(null) }
    var threshold by remember { mutableIntStateOf(store.threshold) }
    var words by remember { mutableStateOf(store.words) }
    var word by remember { mutableStateOf("") }
    var wordNote by remember { mutableStateOf<Int?>(null) }
    var quietNights by remember { mutableStateOf(store.quiet) }
    var squawk by remember { mutableStateOf(store.squawk) }
    fun saveWords(v: List<String>) { words = v; store.words = v }
    fun addWord() {
        val w = Alerts.cleanWord(word)
        wordNote = when {
            w == null -> R.string.word_bad
            words.size >= Alerts.MAX_WORDS -> R.string.word_full
            else -> null
        }
        if (w != null && wordNote == null) { if (words.none { it.equals(w, true) }) saveWords(words + w); word = "" }
    }
    val updateNote by Updater.note.collectAsStateWithLifecycle()
    val update by Updater.state.collectAsStateWithLifecycle()

    fun saveFavorites(v: List<Favorite>) { favorites = v; store.favorites = v; onFavorites(v) }
    fun saveFeeds(v: List<Feed>) { feeds = v; store.feeds = v }
    val offline = stringResource(R.string.offline)
    val nothing = stringResource(R.string.nothing_found)
    fun search() = scope.launch {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return@launch
        lookupNote = null
        val r = Wire.lookup(q)
        found = r.getOrDefault(emptyList()).filter { f -> favorites.none { it.symbol == f.symbol } }
        lookupNote = when { r.isFailure -> offline; found.isEmpty() -> nothing.format(q); else -> null }
    }
    fun addFeed() = scope.launch {
        val url = feedUrl.trim()
        feedNote = Wire.probeFeed(url)
        if (feedNote == null && feeds.none { it.url == url }) {
            saveFeeds(feeds + Feed(URL(url).host.removePrefix("www."), url))
            feedUrl = ""
            Wire.check(ctx)
        }
    }

    // read again on every return to the app: the user may have changed them in Android's settings
    val notifications = remember(now) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val exact = remember(now) { Tick.exact(ctx) }
    val status = remember(now) { store.status }
    val unrestricted = remember(now) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Title(stringResource(R.string.tab_setup), stringResource(R.string.setup_sub), clock = false) }

        item { Section(R.string.section_stocks) }
        item { Field(query, { query = it }, R.string.add_hint, R.string.search, KeyboardType.Text) { search() } }
        lookupNote?.let { item { Note(it) } }
        items(found, key = { "found:" + it.symbol }) { f ->
            Line(f.symbol, f.name, action = stringResource(R.string.add), accent = true) {
                saveFavorites(favorites + f)
                found = emptyList()
                query = ""
                scope.launch { Wire.check(ctx, stocksNow = true) }
            }
        }
        items(favorites, key = { "fav:" + it.symbol }) { f ->
            Line(f.symbol, f.name, action = stringResource(R.string.remove)) {
                saveFavorites(favorites - f)
                store.primed = store.primed - "stock:${f.symbol}" - "google:${f.symbol}" - "price:${f.symbol}" // re-adding it later fills quietly again
            }
        }

        item { Section(R.string.section_alerts) }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(stringResource(R.string.threshold), fontSize = 16.sp, color = Text1)
                Text(stringResource(R.string.threshold_note, threshold), style = MetaStyle)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Alerts.THRESHOLDS.forEach { t ->
                        val on = t == threshold
                        Text(
                            "±$t %",
                            Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Raised else Ink).clickable { threshold = t; store.threshold = t }.padding(horizontal = 16.dp, vertical = 8.dp),
                            fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = if (on) Text1 else Text2,
                        )
                    }
                }
            }
            HorizontalDivider(color = Hairline)
        }
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.words), fontSize = 16.sp, color = Text1)
                Text(stringResource(R.string.words_note), style = MetaStyle)
            }
        }
        items(words, key = { "word:" + it }) { w ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { saveWords(words - w) }, verticalAlignment = Alignment.CenterVertically) {
                Text(w, Modifier.weight(1f), fontSize = 16.sp, color = Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.remove), fontSize = 14.sp, color = Text3)
            }
            HorizontalDivider(color = Hairline)
        }
        item { Field(word, { word = it }, R.string.word_hint, R.string.add, KeyboardType.Text) { addWord() } }
        wordNote?.let { item { Note(stringResource(it)) } }
        if (words != Alerts.DEFAULT_WORDS) item { Status(R.string.words_restore, stringResource(R.string.words_restore_note)) { saveWords(Alerts.DEFAULT_WORDS) } }
        item {
            Toggle(R.string.quiet, stringResource(R.string.quiet_note), quietNights) { quietNights = it; store.quiet = it }
        }
        item {
            Toggle(R.string.squawk, stringResource(R.string.squawk_note), squawk) { squawk = it; store.squawk = it }
        }

        item { Section(R.string.section_feeds) }
        items(feeds, key = { "feed:" + it.url }) { f ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, fontSize = 16.sp, color = Text1)
                    Text(Uri.parse(f.url).host.orEmpty(), style = MetaStyle.copy(color = Text3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (f.url != News.FINANCIAL_JUICE) TextButton(onClick = { saveFeeds(feeds - f) }) { Text(stringResource(R.string.remove), color = Text2) }
                Switch(
                    checked = f.on,
                    onCheckedChange = { on -> saveFeeds(feeds.map { if (it.url == f.url) it.copy(on = on) else it }) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Amber, checkedThumbColor = Ink,
                        uncheckedTrackColor = Raised, uncheckedThumbColor = Text3, uncheckedBorderColor = Hairline,
                    ),
                )
            }
            HorizontalDivider(color = Hairline)
        }
        item { Field(feedUrl, { feedUrl = it }, R.string.feed_hint, R.string.add, KeyboardType.Uri) { addFeed() } }
        feedNote?.let { item { Note(stringResource(it)) } }

        item { Section(R.string.section_delivery) }
        item {
            Status(R.string.notifications, stringResource(if (notifications) R.string.on else R.string.off)) {
                go(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
        }
        if (Build.VERSION.SDK_INT >= 31) item {
            Status(R.string.exact, stringResource(if (exact) R.string.on else R.string.exact_off)) {
                go(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
            }
        }
        item {
            Status(R.string.battery, stringResource(if (unrestricted) R.string.battery_free else R.string.battery_limited)) {
                @Suppress("BatteryLife") // a news alarm is what this exemption is for; the user decides in Android's dialog
                go(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
            }
        }
        item { Note(stringResource(R.string.delivery_note)) }

        item { Section(R.string.section_sources) }
        // one line per feed plus Yahoo news, Google News and prices; a failing source shows its error here
        val names = feeds.filter { it.on }.map { it.name } + listOf("Yahoo Finance news", "Google News", Wire.PRICES)
        items(names.filter { it in status }, key = { "status:" + it }) { name ->
            val c = status.getValue(name)
            val time = clock(ctx, c.time, now)
            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp)) {
                Text(name, fontSize = 16.sp, color = Text1)
                Text(
                    if (c.error == null) stringResource(R.string.source_ok, time) else stringResource(R.string.source_error, time, c.error),
                    style = MetaStyle.copy(color = if (c.error == null) Text2 else Down), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(color = Hairline)
        }

        item { Section(R.string.section_about) }
        item {
            val u = update
            when {
                u != null -> Status(R.string.version_label, stringResource(R.string.update_ready, u.release.version)) { scope.launch { Updater.install(ctx) } }
                else -> Status(R.string.version_label, BuildConfig.VERSION_NAME + (updateNote?.let { "  ·  " + stringResource(it) } ?: "")) {
                    scope.launch { Updater.check(ctx, manual = true) }
                }
            }
        }
        item { Status(R.string.source_code, "github.com/BrickyStudios/tape") { go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BrickyStudios/tape"))) } }
        item { Note(stringResource(R.string.about_note)) }
    }
}

private fun go(ctx: Context, intent: Intent) = runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

@Composable
private fun Section(label: Int) = Text(stringResource(label), style = SectionStyle, modifier = Modifier.padding(top = 32.dp, bottom = 8.dp))

@Composable
private fun Note(text: String) = Text(text, style = MetaStyle, modifier = Modifier.padding(vertical = 12.dp))

/** A symbol in its mono column, the company, and one action on the right. */
@Composable
private fun Line(symbol: String, name: String, action: String, accent: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(symbol, Modifier.width(104.dp).padding(end = 12.dp), fontSize = 16.sp, fontFamily = FontFamily.Monospace, color = Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(name, Modifier.weight(1f), fontSize = 16.sp, color = Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        Text(action, fontSize = 14.sp, color = if (accent) Text1 else Text3)
    }
    HorizontalDivider(color = Hairline)
}

@Composable
private fun Toggle(label: Int, note: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!on) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), fontSize = 16.sp, color = Text1)
            Text(note, style = MetaStyle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Amber, checkedThumbColor = Ink, uncheckedTrackColor = Raised, uncheckedThumbColor = Text3, uncheckedBorderColor = Hairline),
        )
    }
    HorizontalDivider(color = Hairline)
}

@Composable
private fun Status(label: Int, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(stringResource(label), fontSize = 16.sp, color = Text1)
        Text(value, style = MetaStyle)
    }
    HorizontalDivider(color = Hairline)
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: Int, action: Int, type: KeyboardType, onGo: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(hint), color = Text3) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Text1, unfocusedTextColor = Text1, cursorColor = Amber,
                focusedBorderColor = Text3, unfocusedBorderColor = Hairline,
            ),
        )
        TextButton(onClick = onGo, enabled = value.isNotBlank()) { Text(stringResource(action), color = if (value.isNotBlank()) Amber else Text3) }
    }
}
