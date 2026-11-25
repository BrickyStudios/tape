package io.github.brickystudios.tape

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
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
private const val AGENDA = 2
private const val SETUP = 3

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
    val ext by Wire.ext.collectAsStateWithLifecycle()
    val releases by Wire.releases.collectAsStateWithLifecycle()
    val earnings by Wire.earnings.collectAsStateWithLifecycle()
    val mentions by Wire.mentions.collectAsStateWithLifecycle()
    val posts by Wire.posts.collectAsStateWithLifecycle()
    val cot by Wire.cot.collectAsStateWithLifecycle()
    val levels by Wire.levels.collectAsStateWithLifecycle()
    val store = remember { Store(ctx) }
    var favorites by remember { mutableStateOf(store.favorites) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pulled by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val search = remember { SearchState() }
    val still = reducedMotion()
    val pager = rememberPagerState(initialPage = tab.intValue) { 4 }
    val seen = remember { mutableStateListOf(store.seen(0), store.seen(1)) }
    fun markSeen(t: Int) { if (t < 2) { val n = System.currentTimeMillis(); seen[t] = n; store.seen(t, n) } }

    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        favorites = store.favorites
        Wire.foreground = true
        val job = scope.launch { Squawk.say(ctx, Wire.check(ctx)) }
        onPauseOrDispose { Wire.foreground = false; job.cancel(); markSeen(tab.intValue) }
    }
    // a tap on a tab or a push moves the pages; a swipe that settles moves the tab
    LaunchedEffect(tab.intValue) { if (pager.currentPage != tab.intValue) { if (still) pager.scrollToPage(tab.intValue) else pager.animateScrollToPage(tab.intValue) } }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { tab.intValue = it } }
    // leaving a tab marks what was on it as seen, so its count starts again from zero
    var shownTab by remember { mutableIntStateOf(tab.intValue) }
    LaunchedEffect(tab.intValue) { markSeen(shownTab); shownTab = tab.intValue }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } } // clocks turn from amber to grey
    fun refresh() = scope.launch { pulled = true; val say = Wire.check(ctx, stocksNow = true); pulled = false; Squawk.say(ctx, say) }
    fun follow(f: Favorite, own: Boolean?) {
        val adding = own != null && favorites.none { it.symbol == f.symbol }
        favorites = store.follow(f, own)
        if (adding) scope.launch { Wire.check(ctx, stocksNow = true) } // news, prices and levels for it right away
    }

    val all = items.orEmpty()
    val watchlist = all.map { News.tag(it, favorites) }.filter { it.tickers.isNotEmpty() }
    val wire = all.filter { it.wire }.map { News.tag(it, favorites) }
    val counts = listOf(Alerts.newCount(watchlist, seen[0]), Alerts.newCount(wire, seen[1]), 0, 0)
    val zone = remember { java.time.ZoneId.systemDefault() }
    val redditPosts = remember(checked) { store.redditPosts }
    val byQuote = (quotes + market).associateBy { it.symbol }
    val pricesError = remember(checked) { store.status[Wire.PRICES]?.error }
    val status = statusLine(now, checked, checking)
    val words = remember(checked) { store.words }
    val wordTag = stringResource(R.string.alert_word_tag)

    // the page you are on: the tabs, the search over them, or one stock over both; deeper pages slide in from the right
    val screen = opened.value?.let { "d:$it" } ?: if (searching) "search" else "tabs"
    fun depth(s: String) = when { s.startsWith("d:") -> 2; s == "search" -> 1; else -> 0 }
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val ease = CubicBezierEasing(0.32f, 0.72f, 0f, 1f) // iOS-like ease-out: quick start, long soft landing
            when {
                still -> EnterTransition.None togetherWith ExitTransition.None
                depth(targetState) > depth(initialState) ->
                    (slideInHorizontally(tween(320, easing = ease)) { it / 4 } + fadeIn(tween(220))) togetherWith fadeOut(tween(160))
                else -> fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(280, easing = ease)) { it / 4 } + fadeOut(tween(200)))
            }
        },
        label = "screen",
    ) { shown ->
        when {
            shown.startsWith("d:") -> {
                val detail = shown.removePrefix("d:")
                val fav = favorites.firstOrNull { it.symbol == detail }
                Detail(
                    symbol = detail,
                    name = fav?.name ?: MARKET_NAMES[detail]?.let { stringResource(it.first) } ?: search.found.firstOrNull { it.symbol == detail }?.name
                        ?: mentions.firstOrNull { it.symbol == detail }?.name ?: earnings.firstOrNull { it.symbol == detail }?.name.orEmpty(),
                    quote = byQuote[detail],
                    ext = ext[detail],
                    earning = Agenda.next(earnings, detail, now, zone),
                    mention = mentions.firstOrNull { it.symbol == detail },
                    follow = fav,
                    plain = detail in MARKET,
                    news = if (fav != null) watchlist.filter { detail in it.tickers } else emptyList(),
                    levels = levels[detail],
                    now = now,
                    threshold = store.threshold,
                    onFollow = ::follow,
                ) { opened.value = null }
            }
            shown == "search" -> Search(search, favorites, onOpen = { opened.value = it.symbol }) { searching = false }
            else -> Glassed(Modifier.fillMaxSize(), chrome = {
                UpdateBanner(Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 8.dp))
                BottomBar(
                    { pager.currentPage + pager.currentPageOffsetFraction }, pager.currentPage, counts,
                    Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    onTab = { tab.intValue = it },
                ) { searching = true }
            }) {
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                        when (page) {
                            WATCHLIST -> Page(stringResource(R.string.tab_watchlist), status, pulled, { refresh() }) {
                                if (pricesError != null) item { ErrorLine(pricesError) { tab.intValue = SETUP } }
                                if (favorites.isEmpty()) item { Text(stringResource(R.string.empty_watchlist), style = MetaStyle, modifier = Modifier.padding(vertical = 12.dp)) }
                                // what you own first, then what you only watch; each row says when a rule of thumb sees a buying chance
                                listOf(true to R.string.section_holding, false to R.string.section_watching).forEach { (own, label) ->
                                    val group = favorites.filter { it.own == own }
                                    if (group.isNotEmpty()) item(key = "g:$own") { Section(label, first = own || favorites.none { it.own }) }
                                    items(group, key = { "q:" + it.symbol }) { f ->
                                        val q = byQuote[f.symbol]
                                        QuoteRow(
                                            f.symbol, f.name, q, points[f.symbol], plain = false, mono = true, stale = stale(f.symbol, q, now), ext = ext[f.symbol],
                                            hint = earningHint(Agenda.next(earnings, f.symbol, now, zone), now), chance = chanceHint(levels[f.symbol], q),
                                        ) { opened.value = f.symbol }
                                    }
                                }
                                item {
                                    Text(
                                        "+  " + stringResource(R.string.add_stock),
                                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { searching = true }.padding(vertical = 16.dp),
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
                                // what trades now on top: Europe in the morning, the US in the afternoon, Asia at night
                                Market.order(now).forEachIndexed { i, region ->
                                    item(key = "rg:" + region) { RegionHeader(region, now, first = i == 0) }
                                    items(MARKET_GROUPS.getValue(region), key = { "mk:" + it }) { s -> MarketRow(s, byQuote[s], points[s], now) { opened.value = s } }
                                }
                                item { Section(R.string.region_other) }
                                items(MARKET_OTHER, key = { "mk:" + it }) { s -> MarketRow(s, byQuote[s], points[s], now) { opened.value = s } }
                                // what MSW built into its own app: a fear gauge and the most named tickers with their move
                                item { Section(R.string.section_mood) }
                                item { VixRow(byQuote[VIX]) { opened.value = VIX } }
                                if (cot.isNotEmpty()) item { PositioningBlock(cot) }
                                if (mentions.isNotEmpty()) {
                                    item { Section(R.string.section_reddit) }
                                    item { Text(stringResource(R.string.reddit_note), style = MetaStyle.copy(color = Text3), modifier = Modifier.padding(bottom = 8.dp)) }
                                    items(mentions.take(5), key = { "rd:" + it.symbol }) { m -> MentionRow(m, byQuote[m.symbol]) { opened.value = m.symbol } }
                                }
                                if (redditPosts && posts.isNotEmpty()) {
                                    item { Section(R.string.section_posts) }
                                    val top = Reddit.top(posts, "wallstreetbets") + Reddit.top(posts, "mauerstrassenwetten")
                                    items(top, key = { "rp:" + it.id }) { PostRow(it, now) }
                                }
                                item { Section(R.string.ticker) }
                                if (wire.isEmpty()) item { Text(stringResource(R.string.empty_news), style = MetaStyle, modifier = Modifier.padding(vertical = 12.dp)) }
                                items(wire.take(60), key = { "w:" + it.id }) { i -> HeadlineRow(i, now, Alerts.alertWord(i.title, words)?.let { "$wordTag $it" }) }
                            }
                            AGENDA -> Page(stringResource(R.string.tab_agenda), status, pulled, { refresh() }) {
                                agendaItems(releases, earnings, favorites, now) { opened.value = it }
                            }
                            else -> Setup(now, favorites, onFavorites = { favorites = it }) { searching = true }
                        }
                    }
                }
                // content fades out before it reaches the floating tabs, so they stay readable
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.92f)))))
            }
        }
    }
}

/** Room under each page's last line, so it can scroll up past the floating tab bar. */
val BAR_ROOM = 104.dp

/** Page title, the market/check line, then the content; pull down to check everything now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, status: String, refreshing: Boolean, onRefresh: () -> Unit, content: LazyListScope.() -> Unit) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BAR_ROOM)) {
            item { Title(title, status) }
            content()
        }
    }
}

@Composable
private fun Section(label: Int, first: Boolean = false) = Text(stringResource(label), style = SectionStyle, modifier = Modifier.padding(top = if (first) 8.dp else 32.dp, bottom = 8.dp))

/** Prices could not be fetched: say why in one line; tapping it opens Setup, where Sources shows each source. */
@Composable
private fun ErrorLine(error: String, onClick: () -> Unit) {
    Text(
        stringResource(R.string.prices_stale, error),
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 12.dp),
        style = MetaStyle.copy(color = Down), maxLines = 2,
    )
}

/** "Price from 14:02" when the stock's market is open but the price is over 30 minutes old (Yahoo's European prices lag 15). */
@Composable
private fun stale(symbol: String, q: Quote?, now: Long): String? {
    val region = q?.let { Market.region(symbol, it.zone) } ?: return null
    if (Market.status(region, now) != MarketStatus.OPEN || now - q.time < 30 * MINUTE) return null
    return stringResource(R.string.price_from, clock(LocalContext.current, q.time, now))
}

/** "Earnings Thu 22:00" under a stock that reports within a week. */
@Composable
private fun earningHint(e: Earning?, now: Long): String? {
    if (e == null || e.time - now > 7 * 24 * HOUR) return null
    val ctx = LocalContext.current
    val day = whenText(ctx, e.time, now).let { if (slotText(ctx, e) == null) it.substringBefore(' ') else it }
    return stringResource(R.string.earnings_hint, day)
}

@Composable
private fun MarketRow(symbol: String, quote: Quote?, points: List<Double>?, now: Long, onClick: () -> Unit) {
    val (name, sub) = MARKET_NAMES.getValue(symbol)
    QuoteRow(stringResource(name), stringResource(sub), quote, points, plain = true, mono = false, stale = stale(symbol, quote, now), onClick = onClick)
}

/**
 * A floating glass capsule with the four tabs, as in Freehub; the pill under the labels follows the pages 1:1 while you swipe.
 * A tab that is not open shows how many headlines came since you last looked, in amber like a fresh clock.
 * Search sits beside it in its own glass circle, reachable from every tab.
 */
@Composable
private fun BottomBar(position: () -> Float, current: Int, counts: List<Int>, modifier: Modifier, onTab: (Int) -> Unit, onSearch: () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(Modifier.weight(1f).height(56.dp).glass()) {
            val quarter = maxWidth / 4
            Box(Modifier.offset { IntOffset((quarter * position()).roundToPx(), 0) }.width(quarter).fillMaxHeight().padding(4.dp).background(Text1.copy(alpha = 0.10f), Pill))
            Row(Modifier.fillMaxSize()) {
                listOf(R.string.tab_watchlist, R.string.tab_market, R.string.tab_agenda, R.string.tab_setup).forEachIndexed { i, label ->
                    val on = current == i
                    val count = if (on) 0 else counts.getOrElse(i) { 0 }
                    Box(
                        Modifier.weight(1f).fillMaxHeight().semantics { selected = on }
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onTab(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            buildAnnotatedString {
                                append(stringResource(label))
                                if (count > 0) withStyle(SpanStyle(color = Amber, fontFamily = FontFamily.Monospace, fontSize = 11.sp)) { append(" " + if (count > 99) "99+" else count.toString()) }
                            },
                            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (on) Text1 else Text2, maxLines = 1, softWrap = false,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        val source = remember { MutableInteractionSource() }
        Box(
            Modifier.size(56.dp).pressable(source).glass().clickable(source, indication = null, role = Role.Button, onClick = onSearch),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Search, stringResource(R.string.search), tint = Text1) }
    }
}

/** A new version: a glass card over the top of the page until installed or put off. */
@Composable
private fun UpdateBanner(modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val u by Updater.state.collectAsStateWithLifecycle()
    val update = u ?: return
    Row(modifier.fillMaxWidth().glass(Card).padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                update.failed -> stringResource(R.string.update_download_failed)
                update.progress != null -> stringResource(R.string.update_loading, (update.progress * 100).toInt())
                else -> stringResource(R.string.update_ready, update.release.version)
            },
            Modifier.weight(1f).padding(vertical = 12.dp), style = MetaStyle.copy(color = Text1),
        )
        if (update.progress == null) {
            TextButton(onClick = Updater::later) { Text(stringResource(R.string.update_later), color = Text2) }
            TextButton(onClick = { scope.launch { Updater.install(ctx) } }) { Text(stringResource(R.string.update_install), color = Amber) }
        }
    }
}
