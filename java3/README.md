# J3

## Собрать и запустить тесты

```bat
if not exist build mkdir build
javac -d build Spider.java SpiderTest.java
java -cp build SpiderTest
```

Тест напечатает три строки `PASS`.

## Проверить на сервере из задания

В первом окне:

```bat
java -jar lib\Task_J3-server.jar MikhailDeshin
```

В втором окне:

```bat
java -cp build Spider http://localhost:8080/
```

Паук напечатает сообщения в отсортированном порядке. Их текст при новом запуске может отличаться. После проверки закрыть окно сервера.
