# شکن DNS - Android App

اپلیکیشن اندروید برای اتصال به DNS شکن از طریق VPN

## امکانات

- 🔗 **اتصال VPN با DNS شکن** - هدایت ترافیک DNS از طریق سرورهای شکن
- 🔄 **به‌روزرسانی DDNS** - آپدیت خودکار IP در ddns.shecan.ir
- 🚀 **اجرای خودکار** - امکان اجرا هنگام روشن شدن گوشی
- 📊 **نمایش وضعیت** - نمایش لحظه‌ای وضعیت اتصال

## DNS سرورها

| اولویت | آدرس IP |
|--------|---------|
| اول | `178.22.122.101` |
| دوم | `185.51.200.1` |

## نیازمندی‌ها

- Android 8.0 (API 26) یا بالاتر
- Android Studio Hedgehog یا جدیدتر
- JDK 11 یا بالاتر

## نصب و Build

### روش اول: Android Studio

1. پروژه رو Clone کنید:
```bash
git clone https://github.com/YOUR_USERNAME/shecan-dns-android.git
cd shecan-dns-android
```

2. Android Studio رو باز کنید
3. `File > Open` → پوشه پروژه
4. صبر کنید Gradle sync بشه
5. `Build > Build Bundle(s) / APK(s) > Build APK(s)`
6. APK در `app/build/outputs/apk/debug/` پیدا میشه

### روش دوم: Command Line

```bash
./gradlew assembleDebug
```

### روش سوم: GitHub Actions (خودکار)

با هر push به `main`، APK به صورت خودکار build میشه و در Artifacts قابل دانلود است.

## استفاده

1. اپ رو باز کنید
2. **اتصال VPN**: فقط VPN رو با DNS شکن وصل می‌کنه
3. **به‌روزرسانی IP + اتصال**: ابتدا IP رو در DDNS آپدیت می‌کنه، سپس وصل میشه
4. **فقط به‌روزرسانی DDNS**: فقط IP رو آپدیت می‌کنه

## ساختار پروژه

```
app/src/main/
├── java/ir/shecan/dnsapp/
│   ├── MainActivity.kt      # رابط کاربری اصلی
│   ├── DnsVpnService.kt    # سرویس VPN با DNS شکن
│   └── BootReceiver.kt     # اجرای خودکار هنگام روشن شدن
├── res/
│   ├── layout/activity_main.xml
│   ├── values/themes.xml
│   └── drawable/
└── AndroidManifest.xml
```

## مجوزها

- `INTERNET` - برای اتصال اینترنت
- `BIND_VPN_SERVICE` - برای سرویس VPN
- `FOREGROUND_SERVICE` - برای اجرای پس‌زمینه
- `RECEIVE_BOOT_COMPLETED` - برای اجرای خودکار
