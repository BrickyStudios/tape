package io.github.brickystudios.tape

import org.junit.Assert.assertEquals
import org.junit.Test

/** Real headlines from Yahoo, CNBC and Bloomberg (25.11.2026), labelled by hand. The keyword list before 0.7.0 got 35 of the first 68 right. */
class ToneTest {
    private val good = listOf(
        "National Presto (NPK) Stock Is Up 8.5% Today Without Company News",
        "GE Aerospace Wins Malaysia Aviation Group Engine Deal for Up to 16 Boeing 787s",
        "Boeing Lands 50-Jet Chinese Order As Trump Threatens To Double Proposed Tariffs On Beijing",
        "Boeing Lands “a Firm China Order” Worth \$7.4 Billion. Trump Expects 250 More Jets",
        "Sanofi Makes \$41 Billion Bet On US Drugmaker Insmed. Biggest Pharma Deal In Three Years Sends Shares Soaring.",
        "Why Is AbbVie (ABBV) Stock Up Today? Health Care Rally as Tech Slides",
        "Why Gilead (GILD) Stock Is Up Today: Health Care Rally and New Rating",
        "Why Pfizer (PFE) Stock Is Up 4.2% Today: Health Care Sector Rally",
        "Why Is Nike (NKE) Stock Up Today? Dick's Strong Earnings Lift Retailers",
        "Foot Locker Turnaround Sparks 17% Surge in Dick’s Sporting Goods Stock",
        "Nike Soars 6% as Competitor Calls US Foot Locker “A Particular Bright Spot”",
        "Vertiv Just Raised Its Dividend 60%, but the AI Stock Still Yields Only 0.23%",
        "Wedbush starts Robinhood, Coinbase at Outperform in fintech coverage launch",
        "HOOD, COIN Get Wedbush Outperform Ratings: Here’s What The Firm Sees In Fintech’s Next Growth Phase",
        "Coinbase wins dismissal of four-year investor lawsuit",
        "DKS Q3 Earnings Beat Estimates on Strong Sales, View Raised",
        "Target raises FY26 guidance as Q3 sales growth offsets apparel decline",
        "Japan exports in October surge 20.5%, beating estimates on robust chip shipments",
        "4 PEG-Based Value Stocks to Buy as NVIDIA Leads Wall Street Rebound",
        "Li Auto’s Losses Narrow and a Weak Sales Outlook Isn’t Spoiling the Stock’s Rally",
        "FDIC-Insured Banks' Q3 Earnings Grow Y/Y, Asset Quality Holds Steady",
        "Bernstein’s \$250 Target Says DoorDash’s Selloff Went Too Far",
        "Boeing Is 35% Off Its High. Mizuho’s \$225 Target Says the Selloff Went Too Far",
    )
    private val bad = listOf(
        "USA Rare Earth Drops 6% as China Extends Mineral Export Pause and New U.S. Tariffs Loom; Critical Metals Declines 5%, MP Materials Falls 3%",
        "Microsoft Stocks Fall 2.53% as Hyperscalers Join AI Selloff",
        "Nasdaq slides 4% as AI selloff, tariff proposals rattle Wall St",
        "Stock market today: Dow, S&P 500, Nasdaq sink on hot inflation, new Trump tariffs as chip rout continues",
        "Nvidia Stock Keeps Falling. It’s Obvious Jensen Was Not the Only Problem.",
        "Why Dell Technologies Stock Plunged Today",
        "Tesla Sinks 5%, Sandisk Down 13% as AI Selloff Deepens and EU Stalls FSD Approval",
        "Tesla Slides 6% as EU Delays Vote on Its Self-Driving Software; Rivian and Lucid Drop 4%",
        "Tesla and SpaceX stocks sink as merger concerns mount",
        "Tesla’s FSD Push In Europe Hits Another Snag As EU Reportedly Delays Approval Talks Yet Again – TSLA Stock Falls Premarket",
        "Amazon Stocks Fall 4.26% as \$215 Billion Capex Run Rate Meets AI Doubts",
        "Amazon Sinks 6%, Sandisk Down 12% as AI Selloff Deepens: Bear Calls AI “Greatest Misallocation of Capital”",
        "Alphabet stock plunges as Google walks away from a big Australian news deal",
        "Meta Platforms Stocks Fall 2.91% as Big Tech Selloff Spreads",
        "Alphabet Stocks Drop 5.56% as Credit Default Swaps Hit Record",
        "Alphabet Falls 4% on AI Credit Fears as Strategist Warns Capex Is “As Good as It Gets”",
        "AI Chip Stocks Extend Brutal Selloff as Nvidia, AMD and Broadcom Tumble Again",
        "Impinj (PI) Stock Is Down 10.9% Today: Chip Selloff Amid AI Spending Fears",
        "AMD Stocks Plunge 9.74% as Chip Rout and Tariff Threat Collide",
        "Broadcom Stocks Slide 6.86% as AI Chip Selloff Deepens",
        "Ambiq Micro (AMBQ) Stock Is Down 11.7% Today: Chip Stocks Slide on AI Spending Fears",
        "Why Nvidia (NVDA) Stock Is Falling Today: Tech and Chip Selloff",
        "NVIDIA Tumbles 9% as Jensen Huang Rally Fades and Michael Burry Eyes a Fresh Short Near \$200; AMD Plunges 10%, Intel Falls 8%",
        "NFLX Stock Edges Lower Overnight: Streaming Giant Buys Historic ‘Seinfeld’ Studio Lot In Reported \$380M Deal",
        "Boeing Announces New Contracts From Pentagon, China; Stock Continues Skids",
        "Palantir Stocks Fall 4.62% as Nasdaq Rout Hits AI Software",
        "Super Micro Falls 7% Despite Dell’s Record AI Server Backlog; Dell Drops 7% as Barclays Cuts Target to \$344, Hewlett Packard Enterprise Slides 4%",
        "Super Micro forecasts 130% profit growth, yet shares sank to \$22",
        "Super Micro Just Fell 32% in a Month: Is SMCI an Unbeatable Bargain or a Toxic Stock?",
        "Outlook Cut Might Change The Case For Investing In DraftKings Stock (DKNG)",
        "'Exxon Got Walloped': \$340 Million Legal Blow Paves The Way For More",
        "Microsoft-Backed OpenAI Faces Another ChatGPT Setback",
        "Allstate suffers multi-day tech outage ahead of Thanksgiving",
        "Alphabet Is Most Beat Down AI Company",
        "The \$2 trillion chip rally is getting wiped out — here's what history says comes next",
        "Embraer Shares Sink as US Threatens Brazil Aircraft Tariff",
        "Nike's Sales Are Forecast To Fall 8%. Will It Cut AI Spending In 2027?",
        "The U.S. Just Blacklisted the Firm Suspected of Funneling Nvidia Chips to China",
    )
    private val unclear = listOf(
        "Broadcom vs. Qualcomm: Which Technology Stock Is a Better Buy for 2027?",
        "Netflix Acquires Radford Studio Center",
        "Walt Disney (DIS) Plans To Merge Hulu Into Disney+ By December 2026",
        "Starbucks Weighs \$3.1 Billion Sale of Japan Business",
        "Stirlingshire Investments Appoints Daniel Gicklhorn as Chief Technology Officer",
        "Burlington Stores Q3 Earnings Beat on Cost Savings, Sales Miss",
        "Netflix to buy Radford Studio Center in \$380-million deal",
        "Netflix Stock in Focus After \$380 Million Radford Studio Center Purchase",
        "Starbucks Is Said to Engage Banks for Sale of Japan Business",
        "What Could Netflix (NFLX) Gain From Owning The Radford Studio Center?",
        "What Could Lift Chipotle Stock Further?",
        "Citigroup Eyes Venezuela Re-Entry: Can It Grow Cross-Border Payments?",
        "Could Coinbase Actually Climb 70%? Or Is Goldman Sachs Totally Wrong?",
    )

    @Test fun `every labelled headline reads the way a person reads it`() {
        val wrong = good.filter { Alerts.tone(it) != 1 }.map { "+ $it" } + bad.filter { Alerts.tone(it) != -1 }.map { "- $it" } + unclear.filter { Alerts.tone(it) != 0 }.map { "0 $it" }
        assertEquals(emptyList<String>(), wrong)
    }

    private val gutDe = listOf(
        "Gewinne auf breiter Front: Nasdaq steigt 6,7 Prozent",
        "Nvidia-Aktie springt 28 Prozent - Jensen Huang zurück als CEO",
        "Dax erholt sich nach den Verlusten und testet wichtige Marke",
        "Workday glänzt im Nasdaq 100: Was macht der Softwarekonzern richtig?",
        "Mieten, kaufen, lohnen: Deutsche Immobilien-Aktie läutet Comeback ein",
    )
    private val schlechtDe = listOf(
        "Dax fällt auf Julitief – KI-Ausverkauf und US-Zölle belasten",
        "Hannover Rück: Der Kurs bleibt trotz neuer Gewinnziele unter Druck",
        "HP: Die Aktie fällt trotz besserer Quartalszahlen ins Minus",
        "Nutanix: Die Jahresprognose enttäuscht, die Aktie fällt",
        "Dell: Die Aktie fällt trotz verdoppeltem Umsatz unter 290 Dollar",
        "Anthropic: Unter 83 Dollar drohen weitere Kursverluste",
        "Nvidia: Die Aktie fällt nach Huangs Rückkehr erneut",
        "Nasdaq knickt um 3,6 Prozent ein - Anthropic immer schwächer",
        "Dax sinkt nach Tech-Rally - Aixtron sackt 16 Prozent ab",
        "Dax aktuell: Dax rutscht unter 25.000‑Punkte-Marke – und schließt auf tiefstem Stand seit Ende Juni",
        "Wall Street: Erneuter Ausverkauf: US-Börsen brechen weiter ein – Nvidia verliert sieben Prozent",
        "Börse am Tag danach: Nvidia-Aktie fällt um sechs Prozent",
        "Bitcoin auf neuem Tief: Drohen jetzt 50.000 Dollar?",
        "Kurse brechen ein: Wie verhalte ich mich jetzt richtig?",
    )
    private val offenDe = listOf(
        "Porsche: Exklusive Modelle sollen die Profitabilität erhöhen",
        "Nvidia: 250-Dollar-Ziel – Anthropic-Rückgang als Kaufchance?",
        "Nvidia, ASML & Micron: Startet jetzt die nächste KI-Rallye?",
        "Börse: Jetzt Nvidia kaufen?",
        "Geschäfts- und Quartalsberichte heute, 25.11.2026",
        "Immobilien: Nach Einstieg bei LEG: Aroundtown-Chef hört überraschend zum Jahresende auf",
    )
    private val kindsDe = listOf(
        "HP: Die Aktie fällt trotz besserer Quartalszahlen ins Minus" to Kind.EARNINGS,
        "Nutanix: Die Jahresprognose enttäuscht, die Aktie fällt" to Kind.EARNINGS,
        "Rückversicherer: Hannover Rück erwartet 2,9 Milliarden Euro Gewinn" to Kind.EARNINGS,
        "Immobilien: Nach Einstieg bei LEG: Aroundtown-Chef hört überraschend zum Jahresende auf" to Kind.MANAGEMENT,
        "Prozessauftakt: Anklage erhebt schwere Vorwürfe gegen Jones-Day-Anwälte" to Kind.LEGAL,
        "Dax sinkt nach Tech-Rally - Aixtron sackt 16 Prozent ab" to Kind.MOVE,
    )

    @Test fun `German headlines from onvista, Handelsblatt and FAZ read the same way`() {
        val wrong = gutDe.filter { Alerts.tone(it) != 1 }.map { "+ $it" } + schlechtDe.filter { Alerts.tone(it) != -1 }.map { "- $it" } + offenDe.filter { Alerts.tone(it) != 0 }.map { "0 $it" }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test fun `German headlines get their event`() {
        assertEquals(emptyList<String>(), kindsDe.filter { (t, k) -> Alerts.kind(t, "onvista") != k }.map { (t, k) -> "$k $t -> ${Alerts.kind(t, "onvista")}" })
    }

    @Test fun `ads in a feed are dropped`() {
        val xml = """<rss><channel><item><title>BLACK WEEK: Erhalte 50% Rabatt auf alle Abos</title><link>https://x.de/a</link><pubDate>Wed, 25 Nov 2026 17:46:42 +0100</pubDate></item>""" +
            """<item><title>Dax fällt auf Julitief</title><link>https://x.de/b</link><pubDate>Wed, 25 Nov 2026 17:46:42 +0100</pubDate></item></channel></rss>"""
        assertEquals(listOf("Dax fällt auf Julitief"), News.parseRss(xml, "onvista").map { it.title })
    }
}
