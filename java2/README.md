# J2

## Собрать и запустить тесты

```bat
if not exist build mkdir build
javac -d build SortList.java SortListTest.java
java -cp build SortListTest
java -cp build SortList --verify
```

Если проверки прошли, обе команды напечатают `PASS`.

## Посмотреть работу программы

Сначала сравнить скорость обработки шагов без задержки:

```bat
java -cp build SortList --benchmark 4 0 3000 1000
```

Здесь видно влияние курсора в собственном списке. `estimate=NaN`, потому что оценка через время задержки при нулевой задержке не определена. Для сравнения с задержкой и расчёта оценки:

```bat
java -cp build SortList --benchmark 4 1 3000 1000
```

Затем запустить программу с собственным списком:

```bat
java -cp build SortList linked 4 1000
```

Ввести несколько строк. Пустая строка покажет список в его текущем состоянии.

Тот же пример со стандартной коллекцией:

```bat
java -cp build SortList standard 4 1000
```
