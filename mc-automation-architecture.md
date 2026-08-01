# Архитектура универсальной/модульной системы автоматизации Minecraft

> Проектирование на основе разбора Baritone — с учётом его архитектурных ограничений,
> которые стоит **не повторять**. Baritone намеренно не кроссверсионный (ветка на каждую
> версию MC), а цель здесь — единое ядро. Это переносит всю сложность в границу между
> чистым ядром и игрой.

---

## 0. Главный принцип: инверсия зависимостей

Одно правило, из которого выводится всё остальное:

> **Ядро не знает про Minecraft. Совсем. Ноль импортов `net.minecraft`.**

Ядро работает с абстрактным миром через узкий интерфейс (SPI). Реальный Minecraft
подключается снаружи, как драйвер. Это ровно то, чего Baritone **не** сделал (его
`CalculationContext`, движения и миксины напрямую импортируют классы MC — потому он и
форкается по версиям).

```
   ┌─────────────────────────────────────────────┐
   │              PURE CORE (без MC)              │  ← один артефакт на все версии
   │   pathfinder · movement SPI · task engine    │
   └───────────────────▲─────────────────────────┘
                       │ зависит ОТ интерфейсов
   ┌───────────────────┴─────────────────────────┐
   │           PLATFORM SPI (интерфейсы)          │  ← контракт «что ядру нужно от игры»
   └───────────────────▲─────────────────────────┘
                       │ реализует
   ┌───────────────────┴─────────────────────────┐
   │   ADAPTERS: forge-1.20 · fabric-1.21 · ...   │  ← тонкие, по одному на версию×лоадер
   └──────────────────────────────────────────────┘
```

---

## 1. Слоистая архитектура (модули Gradle)

```
mc-automation/
├── core/                 ← ЧИСТАЯ Java. Ноль зависимостей от MC.
│   ├── core-math         ← векторы, BlockPos, хеши, кучи (fastutil)
│   ├── core-world        ← абстрактная модель мира (IBlockView, снимки, кэш чанков)
│   ├── core-pathfinder   ← A*, движения-SPI, стоимости, цели
│   ├── core-engine       ← task/process manager, шина событий, планировщик тиков
│   └── core-api          ← публичные интерфейсы + capability-система
│
├── platform/             ← SPI: контракт ядро↔игра (интерфейсы, DTO, без реализации)
│
├── movements/            ← плагины-движения, тоже по возможности чистые
│   ├── mv-walk           ← traverse/ascend/descend/fall/parkour (базовый набор)
│   ├── mv-elytra         ← полёт (свой солвер)
│   └── mv-<mod>          ← jetpack, blink, и т.п.
│
├── scripts/              ← задачи поверх pathfinder
│   ├── sc-mine · sc-follow · sc-build(schematic) · sc-farm ...
│
├── bridge/               ← мост к внешнему миру
│   ├── bridge-rpc        ← сериализация, протокол (WebSocket/gRPC), state-streaming
│   └── bridge-config     ← схема настроек, валидация, hot-reload
│
├── adapters/             ← ЕДИНСТВЕННОЕ место с net.minecraft
│   ├── adapter-common    ← общий код адаптеров (пред-процессится по версиям)
│   ├── adapter-fabric-1.20.1/
│   ├── adapter-forge-1.20.1/
│   ├── adapter-fabric-1.21.x/
│   └── adapter-legacy-1.12.2/  (старые версии — отдельная реализация SPI)
│
└── web-ui/               ← ОТДЕЛЬНЫЙ репозиторий/процесс (Node.js + React)
```

Правило зависимостей строгое и однонаправленное:
`adapters → platform ← core → platform`, `movements/scripts → core-api`,
`bridge → core-api`. Никогда наоборот.

---

## 2. Platform SPI — сердце версионной независимости

Самый важный дизайн-артефакт. Набор **узких** интерфейсов, описывающих ровно то, что
ядру нужно от игры, в терминах ядра (не MC). Держать его **минимальным** — чем меньше
поверхность, тем меньше версионной боли.

```java
// platform/ — ни одного импорта net.minecraft

/** Чтение мира. Реализация читает живой мир MC ИЛИ снимок. */
public interface IBlockView {
    IBlockData getBlock(int x, int y, int z);   // абстрактный блок, не BlockState MC
    boolean isChunkLoaded(int cx, int cz);
    int minY(); int maxY();
}

/** Абстрактный блок — свойства, а не тип MC. */
public interface IBlockData {
    BlockShape shape();          // FULL, SLAB_BOTTOM, STAIR, FENCE, AIR, LIQUID...
    boolean isClimbable();       // лестница/лоза
    boolean isLiquid();
    double hardness(IToolContext tool);   // тиков ломать
    BlockTag tags();             // AVOID, FALLING, WATER, LAVA, THROWAWAY...
    Object raw();                // escape hatch к нативному BlockState (для адаптера)
}

/** Состояние игрока — снимок или живое. */
public interface IPlayerState {
    Vec3d position(); Vec3d velocity();
    Rotation rotation();
    boolean onGround(); boolean inLiquid();
    IInventoryView inventory();
    EnumSet<Capability> capabilities();   // что персонаж умеет ПРЯМО СЕЙЧАС
}

/** Единственный канал ВЛИЯНИЯ на игру. */
public interface IActuator {
    void setInput(Input input, boolean pressed);   // WASD/jump/sneak/clicks
    void setRotationTarget(Rotation r, boolean force);
    void selectHotbar(int slot);
    void useItem(Hand hand);
    void startBreaking(int x,int y,int z, Face f);
    // расширяемо: кастомные keybind'ы модов регистрируются как Input.custom("jetpack")
}

/** Событийная шина ИЗ игры В ядро. */
public interface IGameEvents {
    void onClientTick(TickPhase phase);
    void onPositionCorrection(Vec3d server);   // ← откат сервером
    void onBlockChange(int x,int y,int z, IBlockData now);
    void onChunkLoad(int cx,int cz); void onChunkUnload(int cx,int cz);
    void onWorldChange();
    void onDeath();
}

/** Метаданные платформы — для capability-переговоров. */
public interface IPlatformInfo {
    GameVersion version();          // 1.12.2 / 1.20.1 / ...
    Loader loader();                // FORGE / FABRIC / NEOFORGE
    EnumSet<Capability> worldCapabilities();  // есть ли элитры, водные ведра-MLG и т.п.
}
```

Ядро видит только это. `adapter-fabric-1.21` реализует эти интерфейсы, транслируя
`net.minecraft.*` ↔ абстракции. Версия меняется → меняется **только адаптер**, ядро не
трогается.

---

## 3. Core-pathfinder — переосмысленный Baritone A*

Берём хорошее из Baritone, чиним плохое.

### Что берём как есть
- Неявный граф, узел `(x,y,z)`, сегментация с incremental-cost-backoff, снимок контекста
  для потокобезопасности, стоимость в тиках. Отличные решения.

### Что делаем лучше

**1. Движение — это плагин, а не enum.** У Baritone `Moves` — жёсткий `enum`, добавить
движение = править ядро. Здесь — реестр:

```java
public interface IMovementType {
    String id();                          // "walk.traverse", "mod.jetpack.fly"
    EnumSet<Capability> requires();       // что нужно от персонажа/мира
    void expand(NodeExpansionContext ctx, MoveSink out);  // сгенерить соседей+стоимости
    IMovementExecutor executor();         // как исполнять (стейт-машина инпутов)
}

public interface IMovementRegistry {
    void register(IMovementType t);
    List<IMovementType> activeFor(CapabilitySet caps);  // фильтр по возможностям
}
```

Регистрация через `ServiceLoader` (авто-обнаружение jar'ов движений) **или** явно. A*
итерирует не `Moves.values()`, а `registry.activeFor(context.capabilities)`. Добавить
jetpack/blink = кинуть jar с новым `IMovementType`, ядро не пересобирается.

**2. Обобщённое состояние узла** (решает проблему заряда/кулдауна). Узел
параметризуется: `Node<S>` где `S` — дополнительное дискретизированное состояние (заряд
джетпака, кулдаун). По умолчанию `S = Void` (как у Baritone, только позиция). Движение,
которому нужен ресурс, объявляет его измерение, и хеш узла расширяется. Платишь за это
только когда реально используешь.

```java
public interface StateDimension {         // опционально подключаемое измерение
    int bucketize(double raw);            // дискретизация в корзины
    boolean feasible(int bucket);
}
```

**3. Capability-переговоры.** Каждое движение объявляет `requires()`. При старте расчёта
ядро берёт `IPlayerState.capabilities() ∩ IPlatformInfo.worldCapabilities()` и включает
только подходящие движения. Пример: `mv-elytra` требует `CAN_ELYTRA`; на 1.8.9 платформа
этого не отдаёт → элитры молча выключены, остальное работает. **Это и есть механизм
адаптации под версии/моды.**

```
Версия/лоадер → IPlatformInfo.capabilities ─┐
Персонаж сейчас → IPlayerState.capabilities ─┼─→ активный набор движений
Настройки (allowParkour…) ───────────────────┘
```

---

## 4. Core-engine — процессы и задачи

Иерархия из Baritone работает отлично, оставляем:

```
Scripts (задачи)  →  выдают Goal + приоритет
       │
ProcessManager    →  выбирает активный процесс по приоритету  (= PathingControlManager)
       │
Pathfinder        →  строит путь к Goal
       │
Executor          →  гонит движения, сверяет позицию каждый тик  (= PathExecutor)
       │
IActuator         →  инпуты в игру
```

Сюда же — **event-driven ресинхронизация**: `Executor` каждый тик сверяет
`IPlayerState.position()` с `getValidPositions()`. Улучшение: `onPositionCorrection` из
SPI даёт **явный** сигнал отката (Baritone его почти не использует для наземного
движения) — можно реагировать мгновенно, а не ждать сверки.

---

## 5. Как реально победить версионность (⅔ всей сложности)

Три проблемы: обфускация, изменения API MC между версиями, разные лоадеры. Решения
послойно:

### 5.1 Ядро — вообще вне проблемы
Раз в `core/` нет `net.minecraft`, оно компилируется один раз обычной Java и кладётся в
jar. Никакой обфускации, никаких маппингов. **Главный выигрыш.**

### 5.2 Адаптеры — единственное «грязное» место
Здесь есть `net.minecraft`, и тут версии расходятся. Инструменты:

- **Multiloader-шаблон** (общий исходник + тонкие точки входа fabric/forge). База —
  например, шаблон Architectury или ручной.
- **Препроцессор версий** для мест, где API MC разошёлся: **Stonecutter** (специально
  для мультиверсионных модов) или Manifold. Держит **один файл адаптера** с версионными
  вставками:
  ```java
  //? if >=1.20 {
  world.getBlockState(pos)
  //?} else {
  /*world.getBlockState(x, y, z)*/
  //?}
  ```
  Лучше, чем ветка-на-версию у Baritone: логика адаптера одна, различается только
  синтаксис вызовов MC.
- **Маппинги**: разработка на Mojmap (официальные имена Mojang) или Yarn, публикация
  через unimined/loom, remap под нужный лоадер. Legacy-версии (1.8/1.12) — на MCP/Yarn
  отдельно.

### 5.3 Legacy как отдельная реализация SPI
1.12.2 и раньше настолько отличаются (нет `BlockState` в современном виде, другой
рендер, другой netcode), что не стоит впихивать их в тот же адаптер. **Просто ещё одна
реализация того же `platform/` SPI** — `adapter-legacy-1.12`. Ядро не заметит разницы.
В этом сила узкого SPI: пока legacy-адаптер умеет отдать `IBlockData` и принять
`IActuator`, ему всё равно, что внутри древний API.

### 5.4 Абстракция реестров (блоки/предметы по версиям)
Имена и id блоков меняются. Data-driven слой: JSON/таблицы «свойства блока →
BlockShape/BlockTag», грузятся под версию. Адаптер маппит нативный блок в абстрактный
`IBlockData` через этот слой + рефлексию/теги. Так `mv-walk` не знает, что «soul_sand»
на 1.12 назывался иначе.

---

## 6. Слой скриптов (Schematica-подобное и прочее)

Скрипт — **композитор целей и реакций поверх pathfinder**, ровно как
`MineProcess`/`BuilderProcess`. Даём декларативный каркас.

```java
public interface IScript {
    String id();
    void onStart(ScriptContext ctx);
    ScriptTick onTick(ScriptContext ctx);   // вернуть Goal / под-цель / done / yield
}
```

Для сложных сценариев — **behavior tree / стейт-машина** как библиотека в `core-engine`,
чтобы скрипты не писали ручные `if`-каскады:

```
SchematicBuildScript:
  Sequence:
    LoadSchematic(file)
    Repeat until complete:
      Selector:
        [need materials?] → GotoInventory → RestockScript
        [next block]      → SetGoal(GoalPlaceAt) → WaitPathDone → PlaceBlock
```

Адаптация Schematica/Litematica: скрипт читает формат схематики (чистая работа с файлом,
версионно-независимая — в `sc-build`), превращает в очередь «поставить блок X в позиции
P», и на каждом шаге ставит `Goal` пасфайндеру + выдаёт `IActuator.useItem`. **Сам
pathfinder ничего не знает про строительство** — скрипт дирижирует им. Это и есть
модульность.

Скрипты тоже плагины через `ServiceLoader` → добавляются jar'ом.

---

## 7. Web-UI и внешний API

Ядро headless, поэтому внешний Node.js UI хорошо ложится.

```
┌──────────────┐   WebSocket / gRPC    ┌───────────────────┐
│  Node.js UI  │◄─────(JSON/proto)────►│  bridge-rpc в ядре │
│ React + графы│   state stream +      │  (внутри мода)     │
│ конфиг · дебаг│   команды             └─────────┬─────────┘
└──────────────┘                                 │ core-api
                                          ┌───────▼────────┐
                                          │   Pure Core    │
                                          └────────────────┘
```

Дизайн моста:

- **`bridge-rpc`** внутри мода поднимает локальный сервер (WebSocket на `localhost:PORT`).
  Протокол — **два канала**:
  1. **State stream** (server→UI, push): текущий путь, рассматриваемые узлы A* (дебаг,
     как синие линии Baritone, но в браузере), позиция, состояние процессов,
     netcode-события. Дельты, а не снимки.
  2. **Command/RPC** (UI→server): `setGoal`, `startScript`, `setSetting`, `pause`. Плюс
     запрос-ответ для конфигов.
- **Сериализация**: рекомендуется **protobuf/flatbuffers** (схема = контракт,
  кроссязычность Java↔TS из коробки, быстро для стриминга узлов A*). JSON проще
  стартовать, но для дебаг-стрима тысяч узлов задохнётся.
- **Схема конфигов** (`bridge-config`): настройки объявляются декларативно (`Setting<T>`
  с типом, диапазоном, описанием), ядро отдаёт **JSON Schema** → UI автогенерит формы.
  Hot-reload через тот же канал. Красивее строкового `#set` Baritone.
- **Дебаг нетворкинга**: адаптер хукает входящие/исходящие пакеты (как
  `MixinNetworkManager`), прогоняет метаданными через мост → UI рисует таймлайн пакетов,
  откаты позиции, тайминги. Отдельная вкладка.
- **Внешний сервер**: тот же WebSocket-протокол может слушать не только `localhost` —
  Node.js-сервис подключается как обычный клиент моста. Один протокол для встроенного UI
  и внешнего оркестратора (например, управлять пачкой ботов).

⚠️ **Безопасность**: мост биндить на `127.0.0.1` по умолчанию, для внешнего доступа —
токен-аутентификация. Открытый порт управления ботом — это дыра.

Опционально — тонкий **in-game overlay** (рендер пути в самой игре) как отдельный
модуль-адаптер, чтобы не зависеть от браузера для базового дебага.

---

## 8. Модель потоков и детерминизм (уроки Baritone)

- **Игровой поток**: только `Executor` + `IActuator` + сбор снимков. Ничего тяжёлого.
- **Пул расчёта**: A* и солверы движений. Работают на **иммутабельном снимке мира**
  (`IBlockView.snapshot()`), как `CalculationContext(forThreadedUse=true)`. Никаких гонок.
- **Поток моста**: сеть UI, отдельно, общается с ядром через lock-free очереди
  команд/событий.
- **Детерминизм для тестов**: раз ядро чистое, можно скормить ему **фейковый
  `IBlockView`** (мир из массива) и юнит-тестить пасфайндинг без Minecraft вообще.
  Baritone так и делает (`src/test`), но здесь это первоклассно — весь core тестируется
  headless. Огромный выигрыш для надёжности.

---

## 9. План по фазам

1. **`platform/` SPI + `core-math/world`** — узкий контракт и абстрактный мир. Замерить:
   реально ли выразить мир без `net.minecraft`. Make-or-break.
2. **`core-pathfinder`** с реестром движений + `mv-walk`. Тест на фейковом мире, headless.
   MC ещё нет.
3. **Первый адаптер** (одна версия×лоадер, например fabric-1.20.1). Оживить бота в игре.
   Здесь всплывут дыры в SPI — доточить контракт.
4. **`bridge-rpc` + web-UI MVP**: goto-команда, визуализация пути, базовый конфиг.
5. **Второй адаптер** (forge-1.20.1) — проверка переносимости SPI (минимум дублирования →
   SPI хороший).
6. **Скрипты** (`sc-mine`, `sc-build`) + behavior-tree.
7. **Мультиверсионность**: Stonecutter, второй major-версии адаптер (1.21). Отдельно —
   legacy-адаптер, если реально нужен.
8. **Продвинутые движения** (элитры/jetpack) и расширенное состояние узла.

**Критическая проверка после фазы 3**: сколько кода в адаптере? Если много логики (не
просто трансляция) — SPI спроектирован неверно, логика утекла из ядра. Вернуться и
вычистить.

---

## 10. Итоговая схема

```
        ┌──────────── web-ui (Node/React) ────────────┐
        │  конфиг-формы · визуализация A* · netdebug   │
        └───────────────────┬──────────────────────────┘
                    WebSocket│proto  (auth, localhost)
        ┌───────────────────▼──────────────────────────┐
        │ PURE CORE (один jar на все версии)            │
        │  ┌────────────┐  ┌──────────────┐             │
        │  │ pathfinder │◄─┤ movement reg │◄ ServiceLoader (mv-*, скрипты как плагины)
        │  │  A* + seg  │  │ capability-  │             │
        │  └─────┬──────┘  │ negotiation  │             │
        │        │         └──────────────┘             │
        │  ┌─────▼──────┐  ┌──────────────┐  ┌────────┐ │
        │  │ process/   │  │ script engine│  │ bridge │ │
        │  │ executor   │  │ (behavior tr)│  │  rpc   │ │
        │  └─────┬──────┘  └──────────────┘  └────────┘ │
        └────────┼──── platform SPI (интерфейсы) ───────┘
                 │ IBlockView·IActuator·IGameEvents·IPlatformInfo
        ┌────────▼──────────────────────────────────────┐
        │ ADAPTERS (единственный net.minecraft)         │
        │  Stonecutter-препроцессор для разницы версий   │
        │  fabric-1.20 · forge-1.20 · fabric-1.21 · ...  │
        │  adapter-legacy-1.12 (отдельная реализация SPI)│
        └────────────────────────────────────────────────┘
```

### Три мысли, которые стоит запомнить

1. **Узкий SPI — единственный рычаг против версионного ада.** Всё, что в него не пустишь,
   останется чистым и вечным. Каждый метод в SPI — будущая версионная головная боль,
   поэтому дерись за минимализм.
2. **Capability-переговоры** превращают «версия/мод» из ветвления кода в данные: движение
   объявляет требования, платформа объявляет возможности, ядро их пересекает. Новый
   мод/версия = новые capability, а не новый `if`.
3. **Плагинность (`ServiceLoader`) движений и скриптов** делает ядро закрытым для
   изменений, но открытым для расширений — то, чего Baritone с его `enum Moves` лишён.

---

## Что можно раскрыть глубже (следующие шаги проектирования)

- Полный набор интерфейсов `platform/` SPI.
- Протокол моста ядро↔UI со схемой сообщений (proto).
- Реестр движений с расширенным состоянием узла.
- Конкретный Gradle-скелет мультимодуля с Stonecutter/unimined.
