# SP WEB OS

**Single-file HTML → APK, seedha phone par.** No PC, no server, no internet.

SP WEB OS ek Android app hai jisme ek ready-made **WebView template APK** inbuilt
hota hai. Tum apni single-file HTML file choose karte ho (ya code paste karte
ho), app template ke andar ki `index.html` ko tumhari file se badal deta hai,
aur APK ko phone par hi **sign** karke install ke liye taiyaar kar deta hai.

## Kaise kaam karta hai

1. `template-app/` — chhota sa Android app jo hamesha `assets/index.html` ko
   full-screen WebView mein kholta hai. Isi ka APK template banta hai.
2. `builder-app/` — **SP WEB OS** khud. Iske assets mein template APK aur ek
   signing key (base64) hoti hai.
3. Build button dabane par builder:
   - template ZIP kholta hai, purani signature (META-INF) hatata hai,
   - `assets/index.html` ko tumhare HTML se replace karta hai
     (compression methods aur `resources.arsc` ki alignment sambhal kar),
   - Google ki **apksig** library se APK ko phone par hi sign karta hai (v1 + v2),
   - final APK ko `Android/data/com.sp.webos/files/WebApp.apk` mein save karke
     PackageInstaller se install bhi kar deta hai.

## APK download (GitHub Actions)

Har push par GitHub Actions dono APK banata hai (pehle template, phir use
builder mein embed karke builder). Taiyaar APK repo ke
**Actions → latest run → Artifacts → `SP-WEB-OS-APK`** se download hota hai.

## V2 — naam, package aur browser wali permissions (2026-11-02)

- **Har app ka apna naam + package:** Builder mein app ka naam do to binary
  manifest (AXML string pool) aur `resources.arsc` patch karke us app ko
  apni pehchan milti hai — package `com.sp.webos.app.<naam>`. Alag naam =
  alag app, **kai web apps ek saath** phone par reh sakte hain. Naam khaali
  chhodne par purana default "Web App" (`com.sp.webos.webapp`) banta hai.
  Hindi naam bhi chalega (package ke liye number wala id ban jata hai).
- **Browser-like + advanced permissions:** Generated Web App ab web page ko
  browser jaisi takat de sakta hai — **camera, microphone (WebRTC /
  getUserMedia), location (geolocation), file upload (`<input type=file>`),
  downloads (DownloadManager), HTML5 fullscreen video, notifications
  permission, vibration**. Jaise browser mein hota hai, permission runtime
  par maangi jati hai — page puchega, Android dialog dikhayega, faisla
  user ka. Web notifications ka permission app ke paas hai, lekin WebView
  mein push notification ke liye aage JS bridge chahiye hoga.

## V3 — storage, background aur notifications (2026-11-02)

- **APK Downloads me:** Har bana hua APK `Downloads/SP WEB OS/<App Naam>.apk`
  me bhi copy hota hai.
- **HTML Library:** Har build ka HTML SP WEB OS ke apne storage me save
  hota hai — "📚 Saved HTML (Library)" se kabhi bhi wapas load karke
  dobara APK bana sakte ho.
- **Apna icon:** Generated apps ko ab SP WEB OS ka apna deep-pink "W" icon
  milta hai (pehle wala generic Android icon gaya). Har app ka *alag*
  custom icon abhi nahi hai — icon ek compiled resource hota hai, uske
  liye workflow me ek extra step lagega (aage ka plan).
- **Background + Notifications — `SpWebOS` JS bridge:** Generated app ki
  web page ko ek native bridge milta hai:
  - `SpWebOS.notify(title, body)` — turant notification
  - `SpWebOS.scheduleNotify(seconds, title, body)` — AlarmManager se
    scheduled notification; **app band ho tab bhi aata hai**
  - `SpWebOS.keepAlive(true/false)` — foreground service jo app ko
    background me zinda rakhta hai (persistent notification ke saath)
  - `SpWebOS.toast(text)` — chhota toast
  Sach ye bhi hai: keep-alive process ko zinda rakhta hai, lekin page ke
  JS timers background me WebView/OEM battery manager se throttle ho
  sakte hain — isiliye time-based kaam ke liye `scheduleNotify` hi
  bharosemand rasta hai.

## Seemayein (honest list)

- Generated app ka **icon abhi default** hai (custom icon = V3 idea).
- HTML **single file** hona chahiye — CSS/JS usi file ke andar inline.
- Signing key repo mein base64 form mein hai (password `spwebos123`) — ye ek
  throwaway **dev key** hai, sirf personal use ke liye. Kabhi public release
  karna ho to apni private key use karna.
- On-device signing apksig se hota hai; kisi phone par install error aaye to
  error message ke saath issue kholo — wahi sabse achha test data hai.

## Roadmap (V3 ideas)

- Generated app ka custom icon
- ZIP se multi-file projects (assets folder ke saath)

---
Personal project by **sp-dev-22** • Android 12+ • Made for fun & learning
