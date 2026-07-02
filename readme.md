<div align="center">

# DeltaRay

![License](https://img.shields.io/badge/license-GPL--3.0-0D1117?style=flat-square&logo=open-source-initiative&logoColor=green&labelColor=0D1117)
![Kotlin](https://img.shields.io/badge/-Kotlin-0D1117?style=flat-square&logo=Kotlin&logoColor=b21fea)

[English Version](#english-version)

</div>

---

## درباره DeltaRay

DeltaRay یک برنامه VPN رایگان و متن‌باز است که بر اساس [v2rayNG](https://github.com/2dust/v2rayNG) ساخته شده.

**هدف اصلی این فورک:** ساده‌سازی تجربه VPN برای کاربران عادی.

### مشکلی که DeltaRay حل می‌کنه

با v2rayNG اصلی، کاربر باید:
- ساب‌لینک‌ها رو دستی اضافه کنه
- کانفیگ‌ها رو تست کنه
- بهترین کانفیگ رو پیدا کنه
- اگه سرور کند شد، دوباره تست کنه

### DeltaRay چیکار می‌کنه؟

DeltaRay تقریباً مثل یک VPN معمولی کار می‌کنه:
- **یک دکمه خاموش/روشن** - همین!
- **آپدیت خودکار** - کانفیگ‌ها از ساب‌لینک‌ها دانلود و بروزرسانی میشن
- **تست خودکار** - همه کانفیگ‌ها تست میشن و بهترین انتخاب میشه
- **Failover خودکار** - اگه اتصال قطع بشه، خودکار به سرور بعدی وصل میشه
- **تغییر سرور** - اگه از سرعت راضی نبودی، یه دکمه برای عوض کردن سرور هست

### تنظیمات پیش‌فرض بهینه
- Fragment: فعال با تنظیمات `100-200` و `1-2`
- Mux: غیرفعال (برای سازگاری بیشتر)
- DNS: Cloudflare

---

<div align="center">

## English Version

</div>

---

## About DeltaRay

DeltaRay is a free, open-source VPN app forked from [v2rayNG](https://github.com/2dust/v2rayNG).

**Goal of this fork:** Make VPN usage effortless for everyday users.

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

### Optimized Defaults
- Fragment: enabled, length `100-200`, packets `1-2`
- Mux: disabled (for compatibility)
- DNS: Cloudflare

---

<div align="center">

### Contact

Telegram: [@DeltaKroneckerGithub](https://t.me/DeltaKroneckerGithub)

Source: [Delta-Kronecker/DeltaRay](https://github.com/Delta-Kronecker/DeltaRay)

</div>
