# J1

Библиотеки уже лежат в `lib`.

## Собрать

```bat
if not exist build mkdir build
javac -cp "lib/*" -d build IssuerKeyTool.java KeyServer.java KeyClient.java IntegrationCheck.java
if not exist issuer.pk8 java -cp "build;lib/*" IssuerKeyTool issuer.pk8
```

## Проверить

В первом окне запустить сервер:

```bat
java -cp "build;lib/*" KeyServer 9000 4 issuer.pk8 CourseIssuer
```

Дождаться `Listening on 0.0.0.0:9000`. Сервер не пишет о каждом запросе.

Во втором окне запустить тест:

```bat
java -cp "build;lib/*" IntegrationCheck 127.0.0.1 9000
```

Если всё прошло, появится `PASS`. Для отдельной проверки клиента:

```bat
java -cp "build;lib/*" KeyClient Alice 127.0.0.1 9000 alice
keytool -printcert -file "%OUT%.crt"
```

Клиент сохранит `.key` и `.crt` в текущую папку. Генерация ключа может занять некоторое время.
