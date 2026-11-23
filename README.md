# Pocket Lawyer UK

Android app (Kotlin + Jetpack Compose) for auditing interactions with UK police and authorities, scanning legal documents, and answering UK legal questions with Gemini.

## Build requirements

- Android Studio with Android SDK 37
- A Gemini API key

## Setup: `GEMINI_API_KEY`

The build reads the Gemini key from `local.properties` in the project root. Create the file if it does not exist and add:

```properties
sdk.dir=/path/to/your/Android/Sdk
GEMINI_API_KEY=your_api_key_here
```

- `local.properties` is **gitignored** — never commit it or share your key.
- The key is read at build time and compiled into `BuildConfig.GEMINI_API_KEY`, so **rebuild after changing it**.
- Users can also paste a key in the app via the key icon on the LAW AI screen; a saved in-app key overrides the built-in one.

Get a key from [Google AI Studio](https://aistudio.google.com/apikey) or Google Cloud Console → APIs & Services → Credentials, and restrict it to the **Generative Language API**. Both `AIza...` and `AQ....` key formats are supported.

## Run

Open the project in Android Studio and press **Run**, or build from the command line:

```bash
./gradlew assembleDebug
```

> Do not publish APKs built with a personal API key — the key can be extracted from the APK.
