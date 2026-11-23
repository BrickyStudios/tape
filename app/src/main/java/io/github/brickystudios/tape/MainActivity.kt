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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val WATCHLIST = 0
private const val MARKET_TAB = 1
private const val SETUP = 2

class MainActivity : ComponentActivity() {
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val tab = mutableIntStateOf(WATCHLIST)
    private val opened = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notify.channels(this)
        Wire.load(this)
        Tick.arm(this)
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        follow(intent)
        // release builds only: a debug build carries another key and could never install the update
        if (!BuildConfig.DEBUG) lifecycleScope.launch { Updater.check(this@MainActivity, manual = false) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Raised, primary = Amber, onPrimary = Ink), typography = AppType) {
                Box(Modifier.fillMaxSize().background(Ink).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                    // phones fill the width; tablets keep a readable column
                    Box(Modifier.widthIn(max = 600.dp).fillMaxSize()) { App(tab, opened) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        follow(intent)
    }

    /** A push says where to go: a stock's detail page, or a tab. */
    private fun follow(intent: Intent) {
        if (intent.hasExtra(Notify.EXTRA_TAB)) tab.intValue = intent.getIntExtra(Notify.EXTRA_TAB, WATCHLIST)
        intent.getStringExtra(Notify.EXTRA_SYMBOL)?.let { opened.value = it }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(tab: MutableIntState, opened: MutableState<String?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by Wire.items.collectAsStateWithLifecycle()
    val checked by Wire.lastCheck.collectAsStateWithLifecycle()
    val checking by Wire.checking.collectAsStateWithLifecycle()
    val quotes by Wire.quotes.collectAsStateWithLifecycle()
    val market by Wire.market.collectAsStateWithLifecycle()
    val points by Wire.points.collectAsStateWithLifecycle()
    val store = remember { Store(ctx) }
    var favorites by remember { mutableStateOf(store.favorites) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pulled by remember { mutableStateOf(false) }
    val seen = remember { mutableStateListOf(store.seen(0), store.seen(1)) }
    fun markSeen(t: Int) { if (t < 2) { val n = System.currentTimeMillis(); seen[t] = n; store.seen(t, n) } }

    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        favorites = store.favorites
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
    val byQuote = (quotes + market).associateBy { it.symbol }
    val pricesError = remember(checked) { store.status[Wire.PRICES]?.error }
    val status = statusLine(now, checked, checking)
    val names = marketNames()
    val subs = marketSubs()
    val words = remember(checked) { store.words }
    val wordTag = stringResource(R.string.alert_word_tag)

    val detail = opened.value
    if (detail != null) {
        val fav = favorites.firstOrNull { it.symbol == detail }
        Detail(
            symbol = detail,
            name = fav?.name ?: names[detail].orEmpty(),
            quote = byQuote[detail],
            plain = detail in MARKET,
            news = if (fav != null) watchlist.filter { detail in it.tickers } else emptyList(),
            now = now,
            threshold = store.threshold,
        ) { opened.value = null }
        return
    }

    Column(Modifier.fillMaxSize()) {
        UpdateBanner()
        Box(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            when (tab.intValue) {
                WATCHLIST -> Page(stringResource(R.string.tab_watchlist), status, pulled, { refresh() }) {
                    if (pricesError != null) item { ErrorLine(pricesError) { tab.intValue = SETUP } }
                    if (favorites.isEmpty()) item { Text(stringResource(R.string.empty_watchlist), style = MetaStyle, modifier = Modifier.padding(vertical = 12.dp)) }
                    items(favorites, key = { "q:" + it.symbol }) { f ->
                        QuoteRow(f.symbol, f.name, byQuote[f.symbol], points[f.symbol], plain = false, mono = true, stale = stale(byQuote[f.symbol], now)) { opened.value = f.symbol }
                    }
                    item {
                        Text(
                            "+  " + stringResource(R.string.add_stock),
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { tab.intValue = SETUP }.padding(vertical = 16.dp),
                            fontSize = 16.sp, color = Amber,
                        )
                        HorizontalDivider(color = Hairline)
                    }
                    val important = watchlist.filter { Alerts.kind(it.title, it.source) != null }.take(10)
                    if (important.isNotEmpty()) {
                        item { Section(R.string.important_today) }
                        items(important, key = { "i:" + it.id }) { HeadlineRow(it, now) }
                    }
                    val more = watchlist.filter { it !in important }.take(20)
                    if (more.isNotEmpty()) {
                        item { Section(R.string.more_news) }
                        items(more, key = { "m:" + it.id }) { HeadlineRow(it, now) }
                    }
                }
                MARKET_TAB -> Page(stringResource(R.string.tab_market), status, pulled, { refresh() }) {
                    if (pricesError != null) item { ErrorLine(pricesError) { tab.intValue = SETUP } }
                    items(MARKET, key = { "mk:" + it }) { s ->
                        QuoteRow(names[s].orEmpty(), subs[s].orEmpty(), byQuote[s], points[s], plain = true, mono = false, stale = stale(byQuote[s], now)) { opened.value = s }
                    }
                    item { Section(R.string.ticker) }
                    if (wire.isEmpty()) item { Text(stringResource(R.string.empty_news), style = MetaStyle, modifier = Modifier.padding(vertical = 12.dp)) }
                    items(wire.take(60), key = { "w:" + it.id }) { i -> HeadlineRow(i, now, Alerts.alertWord(i.title, words)?.let { "$wordTag $it" }) }
                }
                else -> Setup(now, onFavorites = { favorites = it })
            }
        }
        HorizontalDivider(color = Hairline)
        TabBar(tab, counts)
    }
}

/** Page title, the market/check line, then the content; pull down to check everything now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, status: String, refreshing: Boolean, onRefresh: () -> Unit, content: LazyListScope.() -> Unit) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Title(title, status) }
            content()
        }
    }
}

@Composable
private fun Section(label: Int) = Text(stringResource(label), style = SectionStyle, modifier = Modifier.padding(top = 32.dp, bottom = 8.dp))

/** Prices could not be fetched: say why in one line; tapping it opens Setup, where Sources shows each source. */
@Composable
private fun ErrorLine(error: String, onClick: () -> Unit) {
    Text(
        stringResource(R.string.prices_stale, error),
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 12.dp),
        style = MetaStyle.copy(color = Down), maxLines = 2,
    )
}

/** "Price from 14:02" when the market is open but the price is over 15 minutes old. */
@Composable
private fun stale(q: Quote?, now: Long): String? {
    if (q == null || Market.status(now) != MarketStatus.OPEN || now - q.time < 15 * MINUTE) return null
    return stringResource(R.string.price_from, clock(LocalContext.current, q.time, now))
}

@Composable
private fun marketNames(): Map<String, String> = mapOf(
    "^GSPC" to stringResource(R.string.m_sp500), "^IXIC" to stringResource(R.string.m_nasdaq), "^DJI" to stringResource(R.string.m_dow),
    "^GDAXI" to stringResource(R.string.m_dax), "GC=F" to stringResource(R.string.m_gold), "CL=F" to stringResource(R.string.m_oil),
    "BTC-USD" to stringResource(R.string.m_bitcoin), "EURUSD=X" to stringResource(R.string.m_eurusd),
)

/** What each market row measures, in plain words instead of Yahoo's codes. */
@Composable
private fun marketSubs(): Map<String, String> = mapOf(
    "^GSPC" to stringResource(R.string.m_sp500_sub), "^IXIC" to stringResource(R.string.m_nasdaq_sub), "^DJI" to stringResource(R.string.m_dow_sub),
    "^GDAXI" to stringResource(R.string.m_dax_sub), "GC=F" to stringResource(R.string.m_gold_sub), "CL=F" to stringResource(R.string.m_oil_sub),
    "BTC-USD" to stringResource(R.string.m_bitcoin_sub), "EURUSD=X" to stringResource(R.string.m_eurusd_sub),
)

/** Three tabs; one that is not open shows how many headlines came since you last looked, in amber like a fresh clock. */
@Composable
private fun TabBar(tab: MutableIntState, counts: List<Int>) {
    Row(Modifier.fillMaxWidth().height(56.dp)) {
        listOf(R.string.tab_watchlist, R.string.tab_market, R.string.tab_setup).forEachIndexed { i, label ->
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
