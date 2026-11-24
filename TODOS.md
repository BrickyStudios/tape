# TODOS

Deferred in the v0.2.0 scope review (23.11.2026), with the reason.

- **Dividend calendar.** Earnings dates come from Yahoo's calendar since 0.6.0 (crumb-free); dividends are still missing.
- **AI one-liner per alert.** Needs an API key or an on-device model, and users ask for real news rather than AI summaries. Revisit if people ask for it.
- **"Mute this stock today" action.** A notification action receiver. Next round, if the alert volume calls for it. (The morning overview shipped in 0.5.0.)
- **Exchange holidays.** Sessions are fixed hours on weekdays. Pre-market and after-hours prices show since 0.5.0, but do not ring.
- **US stocks on German exchanges in the morning.** Before 10:00 Frankfurt time a US stock only shows last night's after-hours price. Xetra and Tradegate trade them from early morning (Yahoo has `NVD.DE`, `APC.DE`), but the German symbol differs from the US one and needs a lookup per stock.
- **Non-English sources.** Event keywords and alert words are English.
