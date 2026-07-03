<div align="center">

# DeltaRay

![License](https://img.shields.io/badge/license-GPL--3.0-0D1117?style=flat-square&logo=open-source-initiative&logoColor=green&labelColor=0D1117)
![Kotlin](https://img.shields.io/badge/-Kotlin-0D1117?style=flat-square&logo=Kotlin&logoColor=b21fea)

[English Version](#english-version)

</div>

---

## 🔥 [برای دانلود آخرین نسخه کلیک کنید](https://github.com/Delta-Kronecker/DeltaRay/releases/latest/download/app-playstore-arm64-v8a-release.apk) 🔥

و یا به بخش [Releases](https://github.com/Delta-Kronecker/DeltaRay/releases) مراجعه کنید

---

## درباره دلتاری

دلتاری یک برنامه وی‌پی‌ان رایگان، متن‌باز و امن است که بر اساس وی تو ری ساخته شده.

### چرا دلتاری امن است؟

- **متن‌باز**: تمام کد منبع برنامه در GitHub موجود است و هر کسی می‌تواند آن را بررسی کند
- **بدون ردیابی**: هیچ اطلاعات شخصی یا لاگ اتصال ذخیره نمی‌شود
- **بدون تبلیغات**: هیچ تبلیغاتی در برنامه وجود ندارد
- **بدون خرید درون‌برنامه‌ای**: برنامه کاملاً رایگان است
- **امنیت بالا**: از پروتکل‌های رمزنگاری قوی وی تو ری استفاده می‌شود
- **کد قابل بررسی**: هر توسعه‌دهنده‌ای می‌تواند کد را مطالعه و تأیید کند

### هدف اصلی

ساده‌سازی تجربه وی‌پی‌ان برای کاربران عادی:
- **یک دکمه خاموش/روشن** - همین!

### مشکلی که دلتاری حل می‌کنه

با وی تو ری اصلی، کاربر باید:
- ساب‌لینک‌ها رو دستی اضافه کنه
- کانفیگ‌ها رو تست کنه
- بهترین کانفیگ رو پیدا کنه
- اگه سرور کند شد، دوباره تست کنه

### دلتاری چیکار می‌کنه؟

دلتاری تقریباً مثل یک وی‌پی‌ان معمولی کار می‌کنه:
- **یک دکمه خاموش/روشن** - همین!
- **آپدیت خودکار** - کانفیگ‌ها از ساب‌لینک‌ها دانلود و بروزرسانی میشن
- **تست خودکار** - همه کانفیگ‌ها تست میشن و بهترین انتخاب میشه
- **فیلاور خودکار** - اگه اتصال قطع بشه، خودکار به سرور بعدی وصل میشه
- **تغییر سرور** - اگه از سرعت راضی نبودی، یه دکمه برای عوض کردن سرور هست

---

## ساخت و کامپایل

### پیش‌نیازها
- [Android Studio](https://developer.android.com/studio) (نسخه Hedgehog یا جدیدتر)
- JDK 17
- Android SDK 36
- NDK 28.2

### مراحل ساخت

```bash
# کلون مخزن
git clone https://github.com/Delta-Kronecker/DeltaRay.git
cd DeltaRay

# آپدیت submodules
git submodule update --init --recursive

# ساخت کتابخانه hev-socks5-tunnel
bash compile-hevtun.sh

# ساخت libv2ray با gomobile
cd AndroidLibXrayLite
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init
go mod tidy
gomobile bind -v -androidapi 24 -trimpath -ldflags='-s -w -buildid=' -o libv2ray.aar ./
cp libv2ray.aar ../V2rayNG/app/libs/
cd ..

# ساخت APK
cd V2rayNG
chmod 755 gradlew
./gradlew assembleRelease
```

### خروجی
APK در مسیر زیر قرار می‌گیرد:
```
V2rayNG/app/build/outputs/apk/fdroid/release/
V2rayNG/app/build/outputs/apk/playstore/release/
```

---

<div align="center">

## English Version

</div>

---

## 🔥 [Download Latest Version](https://github.com/Delta-Kronecker/DeltaRay/releases/latest/download/app-playstore-arm64-v8a-release.apk) 🔥

Or visit the [Releases](https://github.com/Delta-Kronecker/DeltaRay/releases) page

---

## About DeltaRay

DeltaRay is a free, open-source, and secure VPN app forked from v2rayNG.

### Why is DeltaRay Secure?

- **Open Source**: All source code is available on GitHub for anyone to review
- **No Tracking**: No personal data or connection logs are stored
- **No Ads**: Zero advertisements in the app
- **No In-App Purchases**: Completely free
- **Strong Encryption**: Uses v2ray's robust encryption protocols
- **Auditable Code**: Any developer can read and verify the code

### Goal of This Fork

Make VPN usage effortless for everyday users:
- **One button** to connect/disconnect — that's it!

### The Problem with v2rayNG

With stock v2rayNG, users must:
- Manually add subscription links
- Test configurations one by one
- Find the best performing server
- Re-test when servers get slow

### What DeltaRay Does

DeltaRay works like a regular VPN app:
- **One button** to connect/disconnect — that's it
- **Auto-update** — configs are fetched and updated from subscription links
- **Auto-test** — all configs are tested, best one is selected automatically
- **Auto-failover** — if connection drops, switches to the next best server
- **Server switching** — a dedicated button to switch if current server is slow

---

## Build Instructions

### Prerequisites
- [Android Studio](https://developer.android.com/studio) (Hedgehog or newer)
- JDK 17
- Android SDK 36
- NDK 28.2

### Build Steps

```bash
# Clone the repository
git clone https://github.com/Delta-Kronecker/DeltaRay.git
cd DeltaRay

# Update submodules
git submodule update --init --recursive

# Build hev-socks5-tunnel library
bash compile-hevtun.sh

# Build libv2ray with gomobile
cd AndroidLibXrayLite
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init
go mod tidy
gomobile bind -v -androidapi 24 -trimpath -ldflags='-s -w -buildid=' -o libv2ray.aar ./
cp libv2ray.aar ../V2rayNG/app/libs/
cd ..

# Build APK
cd V2rayNG
chmod 755 gradlew
./gradlew assembleRelease
```

### Output
APK files are located at:
```
V2rayNG/app/build/outputs/apk/fdroid/release/
V2rayNG/app/build/outputs/apk/playstore/release/
```

---

<div align="center">

### Contact

Telegram: [@DeltaKroneckerGithub](https://t.me/DeltaKroneckerGithub)

Source: [Delta-Kronecker/DeltaRay](https://github.com/Delta-Kronecker/DeltaRay)

</div>
