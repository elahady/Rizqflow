# Rizqflow

> Rezeki mengalir, setiap hak terpenuhi. *(Rizq flows; every right is fulfilled.)*

Rizqflow is a personal finance app for Android (with a companion web dashboard) built around one question: not *"where did my money go?"* but *"has every responsibility in my life been funded?"* Income is routed by rules into named **spaces** (ruang), such as Giving, Self, and Family, each with its own budget and its own definition of "fulfilled." Most finance apps are budget trackers that tell you what you spent. Rizqflow tells you whether the money you received has reached every space it was meant for, before the month runs out. It is aimed at people, especially in Indonesia, who pay with cash, QRIS, and several e-wallets and who want a calm way to keep their commitments visible. An optional Islamic module adds zakat and haul tracking, with a Hijri calendar and nisab calculated from the gold price, but the core is universal and useful without it. Rizqflow is a portfolio project and an experiment in product and business design; it is not released yet.

## Features

- **Quick transaction entry**: income, expenses, and transfers across cash, bank, and e-wallet accounts, with quick-entry favorites, a home-screen widget, and a notification reply for a nightly reminder.
- **Automatic allocation to spaces**: income goes through a rule set (percentages, or a waterfall with priorities and monthly caps). A preview shows how the money will flow before you save it. Rounding uses the largest-remainder method, so the parts always add up to the exact amount.
- **Zakat and haul (optional module)**: nisab and haul tracking using the Hijri calendar, with two configurable haul policies, multiple wealth profiles (Pro), and a yearly zakat record. The default assumptions are shown on screen with a disclaimer; they are a calculation aid, not a fatwa.
- **Encrypted multi-device sync**: an append-only, end-to-end encrypted log synced across devices. The server stores only ciphertext. Encryption keys are created and kept on the device and are protected by a 24-word recovery code.
- **Shared family space**: two members share a space through a QR-code invitation. The shared space has its own key, and removing a member rotates that key for future data.
- **Offline-first**: every core feature works without a network connection. Local encrypted backup and CSV export are always free.
- **Demo mode**: a sample data set lets anyone try the app without entering real financial data.

## Tech stack

- **Android**: Kotlin, Jetpack Compose (Material 3), Room (local database, one per account), Navigation Compose, coroutines and Flow, Google Tink (AES-GCM, keysets in the Android Keystore), Google Play Billing, CameraX with ML Kit for QR scanning, and `java.time` with a Hijri chronology for the calendar.
- **Modules**: `:domain` is pure Kotlin/JVM with no Android dependency and holds the money, allocation, zakat, and sync logic. `:data` holds Room and repository implementations. `:app` holds Compose screens and ViewModels, wired together with manual dependency injection.
- **Server**: Laravel 12, Livewire 4, Blade, Laravel Sanctum for API tokens, and Google's ID-token verification and Play Developer API for account and purchase checks. The web dashboard uses Livewire and a shared design system built from the same tokens as the Android theme.

## Design highlights

- **Money is an integer.** Amounts are stored as whole numbers in the smallest unit (rupiah has no decimals). Percentages are stored as basis points, so 2.5% stays exact. Floating-point arithmetic is never used for money.
- **The server cannot read financial data.** Sync records are encrypted on the device before upload. The server stores ciphertext plus routing labels and applies last-write-wins by timestamp without decrypting anything.
- **One entitlement layer.** Every paid feature asks one place whether it is allowed, so pricing can change without touching feature code.

## Project status

Rizqflow is **in active development and has not been released publicly**. It is not on the Google Play Store yet, and no public build is available. The core Android experience (recording, allocation, zakat, offline use) is implemented and covered by automated tests. Sync, the family space, the server, and in-app purchases are implemented and covered by automated tests with fake gateways, but they have not yet been verified on physical devices, against a live Play Store purchase, or against a production server. The web dashboard is a scaffold that currently runs on sample data and is not yet connected to encrypted sync.

## Running the project locally

**Android** (JDK 17 or newer, Android Studio Quail 4 / 2026.1.4 or newer):

```bash
./gradlew :domain:test          # domain unit tests
./gradlew :app:assembleDebug    # debug APK
```

To point a debug build at a local API server, pass `-PrizqflowApiBaseUrl=http://<host>:8000/api/` (see the Gradle configuration for other options).

**Server** (PHP 8.2+, Composer):

```bash
cd server
composer install
cp .env.example .env
php artisan key:generate
php artisan migrate --seed
php artisan serve
```

Configuration values such as Google OAuth client IDs, mail, and Play credentials are read from `.env` and are not stored in this repository.

## Documentation

The product concept, data model, sync design, and roadmap are in the `docs/` folder (written in Indonesian):

- `docs/konsep.md`: product concept and decisions
- `docs/server-sync.md`: server, encryption, and sync architecture
- `docs/monetisasi.md`: Free, Pro, and Sync model
- `docs/roadmap.md`: development stages and their status
