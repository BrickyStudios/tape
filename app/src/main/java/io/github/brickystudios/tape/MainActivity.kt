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
    var favorites by remember { mutableStateOf(Store(ctx).favorites) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pulled by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        val job = scope.launch { Wire.check(ctx) }
        onPauseOrDispose { job.cancel() }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } } // clocks turn from amber to grey
    fun refresh() = scope.launch { pulled = true; Wire.check(ctx, stocksNow = true); pulled = false }

    val all = items.orEmpty()
    Column(Modifier.fillMaxSize()) {
        UpdateBanner()
        Box(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            val sub = if (checking) stringResource(R.string.checking) else if (checked > 0) stringResource(R.string.checked, clock(ctx, checked, now)) else ""
            when (tab.intValue) {
                0 -> {
                    var only by remember { mutableStateOf<String?>(null) }
                    val mine = all.map { News.tag(it, favorites) }.filter { it.tickers.isNotEmpty() && (only == null || only in it.tickers) }
                    Timeline(stringResource(R.string.tab_watchlist), sub, mine, now, pulled, { refresh() },
                        empty = stringResource(if (favorites.isEmpty()) R.string.empty_watchlist else R.string.empty_news)) {
                        if (favorites.size > 1) SymbolFilter(favorites.map { it.symbol }, only) { only = it }
                    }
                }
                1 -> Timeline(stringResource(R.string.tab_wire), sub, all.filter { it.wire }.map { News.tag(it, favorites) }, now, pulled, { refresh() },
                    empty = stringResource(R.string.empty_news))
                else -> Setup(now, onFavorites = { favorites = it })
            }
        }
        HorizontalDivider(color = Hairline)
        TabBar(tab)
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

/** All, AMZN, MSFT, ...: one stock at a time. */
@Composable
private fun SymbolFilter(symbols: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (listOf<String?>(null) + symbols).forEach { s ->
            val on = s == selected
            Text(
                s ?: stringResource(R.string.all),
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Raised else Ink).clickable { onSelect(s) }.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 13.sp,
                fontFamily = if (s == null) FontFamily.Default else FontFamily.Monospace,
                color = if (on) Text1 else Text2,
            )
        }
    }
}

@Composable
private fun TabBar(tab: MutableIntState) {
    Row(Modifier.fillMaxWidth().height(56.dp)) {
        listOf(R.string.tab_watchlist, R.string.tab_wire, R.string.tab_setup).forEachIndexed { i, label ->
            val on = tab.intValue == i
            Box(Modifier.weight(1f).fillMaxSize().clickable { tab.intValue = i }, contentAlignment = Alignment.Center) {
                Text(stringResource(label), fontSize = 14.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) Text1 else Text3)
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
