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

## V1 ki seemayein (honest list)

- Generated app ka **naam "Web App", icon default, package
  `com.sp.webos.webapp`** fixed hai. Isliye ek samay par phone mein ek hi
  generated app rahega — naya APK install karne par purana replace ho jayega.
  (Naam/icon/package badalna = binary manifest editing, V2 ke liye rakha hai.)
- HTML **single file** hona chahiye — CSS/JS usi file ke andar inline.
- Signing key repo mein base64 form mein hai (password `spwebos123`) — ye ek
  throwaway **dev key** hai, sirf personal use ke liye. Kabhi public release
  karna ho to apni private key use karna.
- On-device signing apksig se hota hai; kisi phone par install error aaye to
  error message ke saath issue kholo — wahi sabse achha test data hai.

## Roadmap (V2 ideas)

- Generated app ka custom naam + icon
- Multiple templates (alag package names) taaki kai web apps ek saath reh saken
- ZIP se multi-file projects (assets folder ke saath)

---
Personal project by **sp-dev-22** • Android 12+ • Made for fun & learning
