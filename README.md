# 🛒 Shop Nudge

Think of something you need, add it, and get a nudge on your phone (and watch) when you're actually **in** a shop that sells it.

**Install (Android):** https://github.com/jimmywilson1979-arch/shop-nudge/releases/latest/download/shop-nudge.apk

## How it works
- Add an item by typing, the 🎤 button, sharing text from another app, or long-pressing the app icon › **Add item**.
- Pick where you can get it (Supermarket, Corner shop, Chemist, DIY, B&M/pound shop, Pet shop, Petrol), or name one shop (e.g. *Tesco*).
- The app finds matching shops within 10 km using OpenStreetMap (free, no key) and hands them to Android's built-in geofencing.
- **Walking past** a matching shop (within ~150 m): instant nudge so you can pop in. **Driving past**: nothing — the phone knows you're in a car.
- **In the shop** about 2 minutes: backup nudge (covers parking up and walking in). Each shop nudges you at most once every 3 hours.
- When you move more than 4 km from where it last searched, it looks for shops around your new spot.
- Tap **Got them all ✓** on the notification to tick items off, or **🔕 Quiet here** to never be nudged around that spot.
- **Quiet places** (home, work…): no nudges within 250 m.

Everything stays on the phone. The only thing sent anywhere is the shop search (an area plus shop types).

## Build
Every push to `main` builds a signed APK with GitHub Actions and publishes it to Releases.
The signing key is in `keystore/` so every build can update the installed app. It's fine for a personal sideloaded app, but move it to a GitHub secret before going public on the Play Store.
