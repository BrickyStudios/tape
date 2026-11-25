# TODOS

Deferred in the v0.2.0 scope review (23.11.2026), with the reason.

- **Dividend calendar.** Earnings dates come from Yahoo's calendar since 0.6.0 (crumb-free); dividends are still missing.
- **AI one-liner per alert.** Needs an API key or an on-device model, and users ask for real news rather than AI summaries. Revisit if people ask for it.
- **"Mute this stock today" action.** A notification action receiver. Next round, if the alert volume calls for it. (The morning overview shipped in 0.5.0.)
- **Exchange holidays.** Sessions are fixed hours on weekdays. Pre-market and after-hours prices show since 0.5.0, but do not ring.
- **US stocks on German exchanges in the morning.** Before 10:00 Frankfurt time a US stock only shows last night's after-hours price. Xetra and Tradegate trade them from early morning (Yahoo has `NVD.DE`, `APC.DE`), but the German symbol differs from the US one and needs a lookup per stock.
- **Non-English sources.** Event keywords and alert words are English.
- **Push for a new buying chance.** 0.7.0 shows the rules of thumb on the list and the stock's page; a push when one starts to hold for a stock you own or watch is the next step if the list is not enough.
- **Posts from people on X.** Checked 25.11.2026: X gives no read access without its paid API. nitter.net answers 410, xcancel.com is suspended, X's own embed endpoint answers 429 to logged-out requests. Revisit if that changes; YouTube channel feeds or Bluesky would work without a key.
- **A small language model for the tone.** Gemini Nano is missing on GrapheneOS and other phones without Google's AICore, and a model in the APK adds 20 to 100 MB. The keyword list reads 74 of 74 labelled headlines right; revisit if it starts missing.
- **German news per stock.** finanzen.net refuses apps (403). German headlines reach a stock through the German ticker feeds when they name the company.
- **README screenshots for 0.7.0.** The pictures still show the tab bar before the glass.

