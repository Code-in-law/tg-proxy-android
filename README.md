<div align="center">

# 📱 TG Proxy<br>!!Based on flowseal [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)!!
### Telegram WebSocket Bridge Proxy for Android

[![MIT License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blue.svg)](https://kotlinlang.org)
[![Release](https://img.shields.io/github/v/release/Code-in-law/tg-proxy-android?color=purple)](https://github.com/Code-in-law/tg-proxy-android/releases)

**Обход блокировок Telegram через WebSocket-мост прямо на вашем Android-устройстве.**
**Никаких серверов. Никаких VPN. Просто установите APK и нажмите одну кнопку.**

[📥 Скачать APK](https://github.com/Code-in-law/tg-proxy-android/releases/latest) •
[🐛 Сообщить о баге](https://github.com/Code-in-law/tg-proxy-android/issues) •
[💬 Telegram](https://t.me/IvanovKeen)

</div>

---

## 🚀 Что это?

TG Proxy — Android-приложение, которое запускает **локальный SOCKS5 прокси** на вашем телефоне. Оно перенаправляет трафик Telegram через **WebSocket-соединения** к серверам Telegram, обходя блокировки на уровне провайдера.

### ✨ Возможности

| Функция | Описание |
|---------|----------|
|  **Локальный прокси** | Работает только на вашем устройстве (127.0.0.1) |
|  **WebSocket мост** | Трафик идёт через WS, который не блокируется |
|  **Статистика** | Количество соединений, WS/TCP в реальном времени |
|  **Фоновая работа** | Foreground Service — не убивается Android |
|  **Без VPN** | Не перехватывает остальной трафик |
|  **Без root** | Работает на любом Android 8.0+ |

## 📥 Установка

### Способ 1: Скачать готовый APK (рекомендуется)

1. Перейдите в [**Releases**](https://github.com/Code-in-law/tg-proxy-android/releases/latest)
2. Скачайте файл `tg-proxy-v*.apk`
3. Откройте на телефоне ? «Установить»
4. Если появится предупреждение — нажмите «Всё равно установить»

### Способ 2: Собрать из исходников

```bash
git clone https://github.com/Code-in-law/tg-proxy-android.git
cd TGProxy
./gradlew assembleDebug
```

APK будет в `app/build/outputs/apk/debug/app-debug.apk`

## ⚙️ Настройка

### 1. Запустите прокси
Откройте TG Proxy ? нажмите **«? Запустить прокси»**

### 2. Настройте Telegram
1. Telegram → **Настройки** → **Данные и память** → **Прокси**
2. **Добавить прокси** → **SOCKS5**
3. Заполните:

| Параметр | Значение |
|----------|----------|
| Сервер | `127.0.0.1` |
| Порт | `1080` |
| Имя пользователя | *(пусто)* |
| Пароль | *(пусто)* |

4. Нажмите **Подключить** ?


### Файлы проекта

| Файл | Описание |
|------|----------|
| `MainActivity.kt` | UI — кнопка старт/стоп, статистика |
| `ProxyService.kt` | Foreground Service, SOCKS5 сервер |
| `Socks5Engine.kt` | SOCKS5 хэндшейк, MTProto, AES-CTR |
| `WsBridge.kt` | WebSocket клиент через OkHttp |
| `TgConstants.kt` | IP-адреса DC Telegram |

## ❓ FAQ

<details>
<summary><b>🔴 Telegram пишет «Прокси недоступен»</b></summary>

- Убедитесь, что TG Proxy запущен (зелёный статус)
- Проверьте: сервер = `127.0.0.1`, порт = `1080`
- Перезапустите TG Proxy и Telegram
</details>

<details>
<summary><b>🔋 Прокси выключается в фоне</b></summary>

На **Xiaomi/MIUI**: Настройки ? Приложения ? TG Proxy ? Автозапуск ?
На **Samsung**: Настройки ? Батарея ? TG Proxy ? Неограниченно
На **Huawei**: Настройки ? Батарея ? Запуск приложений ? TG Proxy ? Вручную ? всё ?
</details>

<details>
<summary><b>📱 Какие версии Android поддерживаются?</b></summary>

Android 8.0 (API 26) и выше. Это 95%+ всех устройств.
</details>

## 💖 Поддержать проект

Если проект оказался полезен — можете поддержать разработку:

| Способ | Адрес |
|--------|-------|
| 💎 **TON** | `UQXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX` |
| 💵 **USDT (TRC-20)** | `TXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX` |
| 🅿️ **ЮMoney** | `4100XXXXXXXXXXXXXXX` |
| ☕ **Buy Me a Coffee** | [buymeacoffee.com/codeinlaw](https://buymeacoffee.com/codeinlaw) |

## 👥 Авторы

| | Автор | Роль |
|---|------|------|
| ⚡ | [**Code-in-law**](https://github.com/Code-in-law) | Android-приложение |
| 🌊 | [**Flowseal**](https://github.com/Flowseal) | Оригинальный WS-прокси |

## 📄 Лицензия

Этот проект лицензирован под [MIT License](LICENSE).

---

<div align="center">

**⭐ Если проект помог — поставьте звезду!**

Made with ❤️ for free Telegram

</div>
