# Sounder Project Guidelines

## Multilingual Localization Policy

Whenever any new user-facing text or string is introduced into the project:
1. **Never use hardcoded text** in UI layouts, fragments, dialogs, or Kotlin code.
2. **Declare in default English**: Add the string key in `app/src/main/res/values/strings.xml`.
3. **Propagate to ALL 61 language packages**: Translate and add the string to each and every `app/src/main/res/values-*/strings.xml` bundle.
4. **Preserve Audio Metadata**: Song titles, artist names, album titles, and original genre metadata must never be translated.
5. **Escape Special XML Characters**:
   - Single quotes: always `\'`.
   - Ampersands: always `&amp;`.
   - Placeholders (`%1$s`, `%1$d`, etc.): exact match across all translations.

## Google Drive APK Upload Policy

Whenever the user requests uploading the APK to Google Drive:
1. **Destination Folder**: Target `Antigravity/Sounder/` in the user's Google Drive.
2. **Build Variant Subfolder**:
   - For **Debug** builds: upload to the `Debug` folder (ID: `1i44zW3gbX3Z2jJiSAIeBhGkHp481Ctsh`).
   - For **Release** builds: upload to the `Release` folder (ID: `1A18uS3nC3X_WeS73Dli7O3KYfaOb_qNq`).
3. **Naming Convention**: Name the file following `Sounder-<Variant>_YYYYMMDD_HHMMSS.apk` (e.g., `Sounder-Debug_20261106_080200.apk`).
4. **Provide Drive Link**: Always provide the resulting Google Drive view link to the user.
