# Trading Bot (XAUUSD advisor)

Android app jo XAUUSD (gold) ke aapke levels pehchan kar **Hindi awaaz mein** batata hai:
**WAIT / NO TRADE / BUY / SELL**, kaunsa timeframe dekhna hai (1 ghanta, 15 min, 5 min), aur Entry, SL, TP.
Ye aapko "Sadhan" bol kar salah deta hai. App kholo, **ANALYZE + BOLO** dabao.

![logo](logo.png)

## Aapke levels (rang wahi jo aap chart par lagate ho)
| Rang | Level |
|---|---|
| Peeli | Pichhle din (daily candle) ka High aur Low |
| Lal | Pichhle hafte (weekly candle) ka High aur Low |
| Neeli | Pichhli 4 ghante ki candle ka High aur Low |
| Hari | Aaj ki candle ka Open |

Din New York ke 17:00 se shuru hota hai aur hafta Sunday 17:00 se (zyadatar brokers aise hi candle banate hain).
Agar aapke broker ki candle alag waqt par shuru hoti hai to levels thode alag aa sakte hain.

## Bot kaise sochta hai
1. Price ke paas kaunsa level hai (weekly > daily > 4H = open, taqat ke hisaab se).
2. Pichhle 1 ghante ki 5 min candles dekh kar level par kya hua:
   - **Sweep + reversal**: wick level ke paar gaya, candle wapas andar band hui -> ulta trade (resistance par SELL, support par BUY).
   - **Time-pass**: zyadatar candles level ke paas hi ghoom rahi hain -> **WAIT** (breakout ya sweep ka intezar).
   - **Level toot gaya** (bina respect kiye) -> peeche se chase nahi, **retest hold** ka WAIT. Retest tik gaya to BUY/SELL.
   - **Sirf touch** -> **WAIT**. Resistance par red rejection candle, support par green rejection candle ka intezar (aapka rule).
3. Score: level ki taqat + lamba wick + trend saath (aaj ke open aur 1 ghante ke EMA20 se) + kai lines ek saath.
   Kamzor setup par bot khud **WAIT** bolta hai. Medium par "chhota lot" bolta hai.
4. SL sweep ke wick ke paar, TP agle level par (ya 1:1.5 / 1:2.5).

## GitHub par APK banana
1. github.com par login karo, **New repository** (naam: `tradingbot`, **Public**).
2. Is zip ko extract karo. `TradingBot` folder ke **andar ki** files upload karo (**Add file > Upload files**).
   Sabse upar `app`, `gradle`, `build.gradle.kts`, `settings.gradle.kts` dikhna chahiye (extra folder nahi).
3. **`.github` folder upload nahi hota** (hidden hai). Isliye: **Add file > Create new file**, naam mein likho
   `.github/workflows/build.yml` aur andar `build-workflow.txt` ka poora text paste karo. **Commit changes**.
4. **Actions** tab > **Build APK**. 3-5 minute baad hara tick aaye to run kholo, neeche **Artifacts** se `TradingBot-apk` download karo.
5. Zip extract karo, `app-debug.apk` phone mein bhej kar install karo.

## Phone par pehli baar
1. Internet chalu rakho.
2. Hindi awaaz ke liye: Phone **Settings > Text-to-speech output** mein Google Hindi voice data install hona chahiye.
   Na ho to English awaaz mein Hinglish text bolega.
3. **Broker offset**: apne broker ke chart par XAUUSD ka price dekho aur app ke price se milao. Farq (broker minus app)
   "Broker price offset" mein likh kar Save karo. Isse levels aapke broker ke chart ke paas aa jate hain.
4. **Auto-refresh** ON karo to bot har 30 second mein dekhta rahega aur salah **badalne par hi** bolega.

## Dhyan do
- Data free Yahoo Finance feed se aata hai (pehle XAUUSD spot, na chale to GC=F gold futures, jiska price broker se kaafi alag ho sakta hai).
  Feed kabhi band ya late ho sakta hai. Market band ho to bot "trade mat lo" bolta hai.
- Ye bot sirf rules ke hisaab se salah deta hai. Profit ki guarantee nahi. News ke time (NFP, CPI, FOMC) trade avoid karo, SL hamesha lagao, aur final faisla aapka hai.
- Code mein Android ka UI part is zip ko banate waqt compile nahi kiya ja saka (yahan Android SDK nahi hai). Analysis engine alag se compile karke 8 scenario par test kiya gaya hai.
  GitHub build mein koi error aaye to error ke aakhri 20-30 line bhej do.
