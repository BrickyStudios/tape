# TODOS

Deferred in the v0.2.0 scope review (23.11.2026), with the reason.

- **Dividend calendar.** Earnings dates come from Yahoo's calendar since 0.6.0 (crumb-free); dividends are still missing.
- **AI one-liner per alert.** Needs an API key or an on-device model, and users ask for real news rather than AI summaries. Revisit if people ask for it.
- **"Mute this stock today" action.** A notification action receiver. Next round, if the alert volume calls for it. (The morning overview shipped in 0.5.0.)
- **Exchange holidays.** Sessions are fixed hours on weekdays. Pre-market and after-hours prices show since 0.5.0, but do not ring.
- **US stocks on German exchanges in the morning.** Before 10:00 Frankfurt time a US stock only shows last night's after-hours price. Xetra and Tradegate trade them from early morning (Yahoo has `NVD.DE`, `APC.DE`), but the German symbol differs from the US one and needs a lookup per stock.
- **Non-English sources.** Event keywords and alert words are English.
- **Push for a new signal.** The rules of thumb show on the list and the stock's page; a push when a buying chance or a sell signal starts to hold for a stock you own or watch is the next step if the list is not enough.
- **Swipe smoothness on a real phone.** 0.8.0 stops the whole app from recomputing during a swipe; the emulator renders in software, so frame times there say nothing. Check with `adb shell dumpsys gfxinfo` on a phone.
- **More Nitter servers.** 0.8.0 reads X through five servers that answered on 25.11.2026 (FMHY's list and status.d420.de). When they go, update `Voices.NITTER`.
- **A small language model for the tone.** Gemini Nano is missing on GrapheneOS and other phones without Google's AICore, and a model in the APK adds 20 to 100 MB. The keyword list reads 74 of 74 labelled headlines right; revisit if it starts missing.
- **German news per stock.** finanzen.net refuses apps (403). German headlines reach a stock through the German ticker feeds when they name the company.
- **README screenshots for 0.7.0.** The pictures still show the tab bar before the glass.

