# LITEC (Android TSD)

Приложение для ТСД с базовым потоком:

1. Вход в МойСклад (логин/пароль).
2. Сохранение сессии в SQLite, чтобы не авторизовываться каждый запуск.
3. Кнопка `Проверить обновление` доступна на экране входа и в меню.
4. Меню с кнопкой `Заказы клиентов` и кнопкой `Выход`.
5. Загрузка и показ списка заказов клиентов из МойСклад.

## Технически

- API: `https://api.moysklad.ru/api/remap/1.2/entity/customerorder`
- Авторизация: HTTP Basic (`login:password`)
- Локальное хранение сессии: SQLite (`SessionDbHelper`)
- Автообновление: GitHub Releases (`releases/latest`) + `DownloadManager` + `FileProvider`

## Сборка

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`
