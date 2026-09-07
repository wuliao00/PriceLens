# PriceLens — Минималистичный помощник для сравнения цен

[简体中文](README.md) | [English](README.en.md) | [Русский](README.ru.md)

> **Две платформы** · **Навсегда бесплатно** · **Локальный приоритет** · **Лицензия MIT**
> Android (на основе специальных возможностей) + Windows (Electron) · Автор: **Mo**
> Версия: Android v2.5.1 / Desktop v2.1.0

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Release](https://img.shields.io/github/v/release/wuliao00/PriceLens?label=Latest%20Release)](https://github.com/wuliao00/PriceLens/releases)

PriceLens возвращает сравнение цен к основам: откройте страницу товара и мгновенно
увидите историю цен по разным магазинам, купоны и настоящие отзывы сообщества —
без рекламы, без отслеживания, без утечки данных с вашего устройства.

## Возможности

- **Определение цен**: страницы товаров JD / Taobao / PDD через службу специальных
  возможностей Android; веб-скрапинг на настольной версии.
- **История цен**: маркеры минимума/максимума, пульсация текущей цены, подсветка
  промоакций, обнаружение «падения после роста» (>= среднее за 7 дней x 1,10).
- **Проверка сообществом**: обзорные видео Bilibili с метками «реклама / хайп»,
  индекс «стоит ли своих денег» с SMZDM.
- **Купоны**: автоматическое обнаружение купонов с копированием в один клик.
- **Отслеживание цен**: фоновый опрос каждые 30 минут, системное уведомление
  при достижении целевой цены (Android WorkManager / резидент в трее на настольной
  версии).
- **Пользовательские скрипты**: встроенные скрипты только для чтения + пользовательские
  скрипты (Android через ADB shell Shizuku, настольная версия через PowerShell,
  тайм-аут 120 с, ограничение размера 64 КБ).
- **Приватность прежде всего**: ноль аналитики, ноль загрузок наружу; локальная
  база Room + кэш TLRU (Android) и локальный JSON-кэш (настольная версия).

## Скриншоты

| Отзывы Bilibili | Сообщество (Shihuo) | График цен | Купоны |
|------------------|--------------------|-------------|---------|
| ![Bilibili](assets/screenshots/android-bilibili.png) | ![Community](assets/screenshots/desktop-community.png) | ![Price](assets/screenshots/desktop-price.png) | ![Coupons](assets/screenshots/desktop-coupons.png) |

## Установка

Скачайте подписанные сборки со страницы
[GitHub Releases](https://github.com/wuliao00/PriceLens/releases):

- **Android**: `PriceLens_v2.5.1.apk` (Android 8.0+)
- **Windows**: NSIS-установщик или портативный ZIP (Windows 10/11 x64, Node не требуется)

## Сборка из исходников

### Android

```bash
git clone https://github.com/wuliao00/PriceLens.git
cd PriceLens
echo "sdk.dir=<путь к вашему Android SDK>" > local.properties
./gradlew :app:assembleDebug          # отладочная сборка, подпись не нужна
./gradlew test ktlintCheck            # модульные тесты + проверка стиля
```

Подпись релиза: добавьте `PRICLENS_STORE_FILE` / `PRICLENS_STORE_PASSWORD` /
`PRICLENS_KEY_ALIAS` / `PRICLENS_KEY_PASSWORD` в `local.properties`, затем выполните
`./gradlew :app:assembleRelease`.

### Настольная версия (Electron 33 + нативный JS + Vite)

```bash
cd desktop
npm install        # также генерирует build/icon.ico через postinstall
npm run dev        # режим разработки (Electron + горячая перезагрузка Vite)
npm run lint       # проверка синтаксиса (node --check)
npm run build      # NSIS-установщик + портативный ZIP в dist/
```

Требования: Node 18+. Опционально: `npx playwright install chromium` для
страниц с JS-рендерингом (без него — корректная деградация).

## Технологический стек

- **Android**: Kotlin 2.0, Jetpack Compose, Hilt, Room, WorkManager, Coil,
  OkHttp, Shizuku (опционально, автоматизация уровня ADB).
- **Настольная версия**: Electron 33, ванильный JS, Vite, undici, electron-builder.

## Дисциплина краулера (общая для обеих платформ)

```text
<= 1 запрос / 3 с на домен
<= 3 одновременных домена
тайм-аут 10 с, 1 повтор
Ротация UA (5)
403 -> размыкатель цепи на 5 минут
```

## Документация

- [Журнал изменений](CHANGELOG.md) · [API](docs/API.md) · [Разработка](docs/DEVELOPMENT.md)
- [Приватность](PRIVACY.md) · [Безопасность](SECURITY.md) · [Лицензия](LICENSE)

## Лицензия

Лицензия MIT. Навсегда бесплатно — без платных тарифов, без рекламы, без отслеживания.
Сохраняйте это уведомление и файл LICENSE при распространении.
