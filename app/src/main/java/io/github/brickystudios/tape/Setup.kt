package io.github.brickystudios.tape

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                store.primed = store.primed - "stock:${f.symbol}" - "google:${f.symbol}" // re-adding it later fills quietly again
            }
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
