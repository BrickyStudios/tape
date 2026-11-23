package io.github.brickystudios.tape

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val tab = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notify.channels(this)
        Wire.load(this)
        Tick.arm(this)
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        tab.intValue = intent.getIntExtra(Notify.EXTRA_TAB, 0)
        // release builds only: a debug build carries another key and could never install the update
        if (!BuildConfig.DEBUG) lifecycleScope.launch { Updater.check(this@MainActivity, manual = false) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Raised, primary = Amber, onPrimary = Ink), typography = AppType) {
                Box(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) { App(tab) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(Notify.EXTRA_TAB)) tab.intValue = intent.getIntExtra(Notify.EXTRA_TAB, 0)
    }
}

@Composable
private fun App(tab: MutableIntState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by Wire.items.collectAsStateWithLifecycle()
    val checked by Wire.lastCheck.collectAsStateWithLifecycle()
    val checking by Wire.checking.collectAsStateWithLifecycle()
    val quotes by Wire.quotes.collectAsStateWithLifecycle()
    val store = remember { Store(ctx) }
    val seen = remember { mutableStateListOf(store.seen(0), store.seen(1)) }
    fun markSeen(t: Int) { if (t < 2) { val n = System.currentTimeMillis(); seen[t] = n; store.seen(t, n) } }
    var favorites by remember { mutableStateOf(Store(ctx).favorites) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pulled by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        val job = scope.launch { Squawk.say(ctx, Wire.check(ctx)) }
        onPauseOrDispose { job.cancel(); markSeen(tab.intValue) }
    }
    // leaving a tab marks what was on it as seen, so its count starts again from zero
    var shownTab by remember { mutableIntStateOf(tab.intValue) }
    LaunchedEffect(tab.intValue) { markSeen(shownTab); shownTab = tab.intValue }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } } // clocks turn from amber to grey
    fun refresh() = scope.launch { pulled = true; val say = Wire.check(ctx, stocksNow = true); pulled = false; Squawk.say(ctx, say) }

    val all = items.orEmpty()
    val watchlist = all.map { News.tag(it, favorites) }.filter { it.tickers.isNotEmpty() }
    val wire = all.filter { it.wire }.map { News.tag(it, favorites) }
    val counts = listOf(Alerts.newCount(watchlist, seen[0]), Alerts.newCount(wire, seen[1]), 0)
    Column(Modifier.fillMaxSize()) {
        UpdateBanner()
        Box(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            val sub = if (checking) stringResource(R.string.checking) else if (checked > 0) stringResource(R.string.checked, clock(ctx, checked, now)) else ""
            when (tab.intValue) {
                0 -> {
                    var only by remember { mutableStateOf<String?>(null) }
                    val mine = watchlist.filter { only == null || only in it.tickers }
                    Timeline(stringResource(R.string.tab_watchlist), sub, mine, now, pulled, { refresh() },
                        empty = stringResource(if (favorites.isEmpty()) R.string.empty_watchlist else R.string.empty_news)) {
                        if (favorites.isNotEmpty()) SymbolFilter(favorites.map { it.symbol }, quotes.associateBy { it.symbol }, only) { only = it }
                    }
                }
                1 -> Timeline(stringResource(R.string.tab_wire), sub, wire, now, pulled, { refresh() },
                    empty = stringResource(R.string.empty_news))
                else -> Setup(now, onFavorites = { favorites = it })
            }
        }
        HorizontalDivider(color = Hairline)
        TabBar(tab, counts)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Timeline(
    title: String, sub: String, items: List<Item>, now: Long, refreshing: Boolean, onRefresh: () -> Unit,
    empty: String, top: @Composable () -> Unit = {},
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Title(title, sub) }
            item { top() }
            if (items.isEmpty()) item { Text(empty, style = MetaStyle, modifier = Modifier.padding(vertical = 12.dp)) }
            items(items, key = { it.id }) { HeadlineRow(it, now) }
        }
    }
}

/** All, AMZN +1.2 %, MSFT −0.4 %, ...: one stock at a time, with today's move against yesterday's close. */
@Composable
private fun SymbolFilter(symbols: List<String>, quotes: Map<String, Quote>, selected: String?, onSelect: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (listOf<String?>(null) + symbols).forEach { s ->
            val on = s == selected
            val q = s?.let { quotes[it] }
            Text(
                buildAnnotatedString {
                    append(s ?: stringResource(R.string.all))
                    if (q != null) withStyle(SpanStyle(color = if (q.change >= 0) Up else Down)) { append(" " + percent(q.change).replace(" ", "")) }
                },
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Raised else Ink).clickable { onSelect(s) }.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 13.sp,
                fontFamily = if (s == null) FontFamily.Default else FontFamily.Monospace,
                color = if (on) Text1 else Text2,
            )
        }
    }
}

/** Three tabs; one that is not open shows how many headlines came since you last looked, in amber like a fresh clock. */
@Composable
private fun TabBar(tab: MutableIntState, counts: List<Int>) {
    Row(Modifier.fillMaxWidth().height(56.dp)) {
        listOf(R.string.tab_watchlist, R.string.tab_wire, R.string.tab_setup).forEachIndexed { i, label ->
            val on = tab.intValue == i
            val count = if (on) 0 else counts.getOrElse(i) { 0 }
            Box(Modifier.weight(1f).fillMaxSize().clickable { tab.intValue = i }, contentAlignment = Alignment.Center) {
                Text(
                    buildAnnotatedString {
                        append(stringResource(label))
                        if (count > 0) withStyle(SpanStyle(color = Amber, fontFamily = FontFamily.Monospace)) { append("  " + if (count > 99) "99+" else count.toString()) }
                    },
                    fontSize = 14.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) Text1 else Text3,
                )
            }
        }
    }
}

@Composable
private fun UpdateBanner() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val u by Updater.state.collectAsStateWithLifecycle()
    val update = u ?: return
    Row(Modifier.fillMaxWidth().background(Raised).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                update.failed -> stringResource(R.string.update_download_failed)
                update.progress != null -> stringResource(R.string.update_loading, (update.progress * 100).toInt())
                else -> stringResource(R.string.update_ready, update.release.version)
            },
            Modifier.weight(1f), style = MetaStyle.copy(color = Text1),
        )
        if (update.progress == null) {
            TextButton(onClick = Updater::later) { Text(stringResource(R.string.update_later), color = Text2) }
            TextButton(onClick = { scope.launch { Updater.install(ctx) } }) { Text(stringResource(R.string.update_install), color = Amber) }
        }
    }
}
