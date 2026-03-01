# LITEC (Android)

Приложение с кнопкой `Spawn Hello World` и автообновлением через GitHub Releases.

## Как работает обновление

1. Приложение делает запрос в `releases/latest`:
   `https://api.github.com/repos/drapik/LITEC/releases/latest`
2. Сравнивает `tag_name` с текущим `versionCode` приложения.
3. Если версия новее, скачивает APK asset (по умолчанию `LITEC.apk`).
4. Запускает системный экран установки APK поверх текущего приложения.

## Важные правила релиза

- `tag_name` должен быть в формате `v<number>`, например `v2`, `v3`.
- В релизе должен быть APK asset `LITEC.apk` (или любой `.apk`, но лучше именно это имя).
- APK должен быть подписан тем же ключом, что и установленная версия, иначе обновление не установится.

## Сборка

```bash
./gradlew assembleDebug
```

APK:
`app/build/outputs/apk/debug/app-debug.apk`
