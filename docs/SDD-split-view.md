# SDD: split view (дерево разбиений)

Эпик `t_d7e7f0d6`. PRD: [PRD-split-view.md](PRD-split-view.md). Стек — KMP + Compose Multiplatform,
цели: `androidApp`, `desktopApp`, общий код в `shared/ui`.

## Решение

Дерево бинарных разбиений (`Row`/`Column` с долей), как в VS Code / tmux / IntelliJ. Не сетка: сетка не выражает
«слева 60 % в две строки, справа один чат». На телефоне первое деление — `Column` (сверху текущий чат, снизу
выбор), на десктопе — `Row`.

## Модель данных

`shared/ui/.../ui/split/SplitLayout.kt`:

```kotlin
enum class SplitAxis { Row, Column }

sealed interface SplitNode {
    data class Pane(val id: String, val chat: PaneChat) : SplitNode
    data class Branch(val id: String, val axis: SplitAxis, val fraction: Float,
                      val first: SplitNode, val second: SplitNode) : SplitNode
}

data class PaneChat(val storedSessionId: String?, val title: String?, val profile: String?)

class SplitLayout private constructor(
    /** `Pane` — одна панель без разбиений; `Branch` — дерево. */
    val root: SplitNode,
    val focusedPaneId: String,
)
```

Инварианты (проверяются тестами):

- `MAX_PANES = 4`, минимум 1 панель; `close` последней — no-op.
- Нет веток с единственным ребёнком: `close` схлопывает ветку в оставшееся поддерево.
- `fraction` всегда в `0.15f..0.85f`; `resize` дополнительно зажимает по минимальному размеру панели в dp
  (телефон 240×200, десктоп 320×240), если передан размер контейнера.
- `split(paneId, axis, newPaneId)` отказывает (`splitOrNull() == null`) при `count == MAX_PANES` или когда после
  деления часть становится меньше минимума; при успехе новая панель пустая (`PaneChat(null, null, profile)`),
  фокус на ней.
- Все операции чистые: возвращают новое дерево, входное не меняется.
- `Saver<SplitLayout, String>`: компактная строка `R0.5(P:dev/abc,C0.7(P:-,P:onru/def))`, `P:` — панель,
  `-` — пустая, `R`/`C` — ось. Доли округляются до 3 знаков, чтобы `restore(save(x)) == x`; мусор → `null`.

## Композиция и производительность

- `SplitContainer.kt` рисует дерево: `Branch` → `Row`/`Column` с `weight(fraction)` / `weight(1 - fraction)` и
  границей между детьми. Граница: видимая линия 1dp, зона захвата 24dp, `detectDragGestures` пересчитывает долю
  от размера контейнера в px (`derivedStateOf` + `LocalDensity`) и отдаёт её в `onResize` по одному вызову на
  событие; двойной тап → `onReset` (50/50). Курсор: `Row` — горизонтальный resize
  (`designsystem/components/Cursors.kt`, `ResizeHorizontalPointer` уже есть; для `Column` добавить вертикальный
  expect/actual).
- Содержимое панели обёрнуто в `key(pane.id)`: изменение доли не пересоздаёт композицию чата, панели —
  независимые поддеревья, поэтому стрим одной не рекомпозирует другую. `animateContentSize` на контейнере
  запрещён.
- `SplitScreen.kt` держит `rememberSaveable(saver = SplitLayout.Saver)` и состояние `SplitLayout`, раздаёт
  колбэки; шапка экрана — кнопка Split (`Lucide.PanelsTopLeft`), неактивна при `count == MAX_PANES` или отказе
  `splitOrNull`.

## Жизненный цикл панели

`PaneChatHost.kt` — по образцу `desktopApp/.../ChatWindow.kt`:

- свой `ChatHost` из `koin.get()`, свой `ViewModelStoreOwner`,
  `ChatViewModel(qualifier = named(CHAT_WINDOW)) { parametersOf(host) }` — то есть **одна панель = одна сессия**;
- `DisposableEffect(paneId)`: `host.close()` + `owner.viewModelStore.clear()` на выходе панели;
- `DisposableEffect(storedSessionId) { mainHost.holdSideChat(id); onDispose { releaseSideChat(id) } }` —
  ровно как `ChatWindow.kt:165` и `ChatHost.holdSideChat` (`shared/core/.../ChatHost.kt:49`). Без этого watcher
  считает чат «другим» и сыпет баннерами (пункт 8 приёмки).

Фокус: `focused` в `SplitLayout`; только его панель получает `keysEnabled = true` и `ProvideReplySpeaker`,
остальные рендерятся и стримят, но ввод не забирают. Тап по панели → `focus(paneId)`.

## Точки правки

| Файл | Что |
|---|---|
| `shared/ui/.../ui/split/SplitLayout.kt` (новый) | модель, операции, `Saver`, `MAX_PANES`, минимумы |
| `shared/ui/.../ui/split/SplitContainer.kt` (новый) | дерево, границы, drag, сброс, курсоры |
| `shared/ui/.../ui/split/PaneChatHost.kt` (новый) | `ChatHost`/`ChatViewModel`/`ViewModelStoreOwner` на панель, `holdSideChat` |
| `shared/ui/.../ui/split/SplitScreen.kt` (новый) | состояние + шапка Split + пустая панель «Choose a chat» + крестики + фокус |
| `shared/ui/.../ui/split/SplitDragAndDrop.kt` (новый) | зоны сброса (центр / 4 края по 25 %), источник драга |
| `shared/ui/.../ui/App.kt` | замена `else -> ProvideReplySpeaker { ChatScreen(...) }` (стр. ~767) на `SplitScreen`; `openSession` → панель под фокусом |
| `shared/ui/.../ui/Shortcuts.kt` | `Shortcut.SplitScreen` (`Ctrl+\`), не отбирая занятые комбинации |
| `shared/ui/.../ui/sessions/SessionsSidebar.kt` | долгое нажатие с движением = drag, без движения = прежнее меню; пункт «Open beside» |
| `shared/ui/src/commonTest/.../split/SplitLayoutTest.kt` | модель + `Saver` |
| `shared/ui/src/jvmTest/.../split/*` | `SplitContainerTest`, `SplitScreenTest`, `PaneChatHostTest`, `SplitDropZoneTest` |

## План выполнения (волны)

1. Модель `SplitLayout` + юнит-тесты (`t_368cfeb2`).
2. Параллельно: `SplitContainer` (`t_3cce01f8`) и `PaneChatHost` (`t_d915d9c2`) — обе ветки от модели.
3. Экран и интеграция в `App.kt` (`t_b07ca085`) — зависит от 2.
4. Параллельно от экрана: drag-and-drop (`t_89c920f8`) и UI-тесты (`t_3db5f51b`).
5. Ревью собранного дерева (`t_61c4cbc8`).
6. E2E на телефоне + релиз (`t_ce9a0f49`).

Файлы не пересекаются между параллельными ветками, кроме `SplitScreen.kt` (его правит только `t_b07ca085`;
`t_89c920f8` подключает колбэк `onDropChat` уже после неё).

## Откат

Функция целиком за новыми файлами: откат — вернуть в `App.kt` прежнюю ветку `ProvideReplySpeaker(saver) {
ChatScreen(...) }`, удалить `ui/split/` и `Shortcut.SplitScreen`. Миграций и изменений в данных нет: `Saver`
живёт в `rememberSaveable`, в настройки ничего не пишется, формат сессий гейтвея не меняется.

## Как проверим

- `:shared:ui:jvmTest`, `:shared:core:jvmTest` — зелёные; покрытие нового `ui/split/` ≥75 % (цифра в отчёте).
- `:shared:ui:compileKotlinJvm`, `:desktopApp:compileKotlinJvm`, `:androidApp:compileDebugKotlin` — зелёные.
- Живой прогон: `./gradlew.bat :androidApp:assembleRelease -PappVersion=<next>`, `adb install -r`, сценарий из
  карточки `t_ce9a0f49` (сплит → выбор чата → стрим в нижней панели → драг границы → 3–4 панели → крестик →
  поворот → сворачивание).
- Регрессия одиночного чата и отсутствие ложных «другой чат ответил» — по логу и баннерам на устройстве.

## Известные ограничения

- Раскладка не сохраняется между запусками приложения (только поворот/смерть процесса).
- Максимум 4 панели; на телефоне при 4 панелях минимум 240×200 dp может не поместиться — `splitOrNull`
  откажет, кнопка гаснет.
- Один и тот же чат в двух панелях — две независимые сессии (`ChatHost` на панель): история одна, но стрим и
  композер у каждой свои. Если это окажется неудобным — отдельной карточкой.
