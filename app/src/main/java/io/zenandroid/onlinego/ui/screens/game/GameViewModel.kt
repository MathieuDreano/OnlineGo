package io.zenandroid.onlinego.ui.screens.game

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.molecule.AndroidUiDispatcher
import app.cash.molecule.launchMolecule
import io.zenandroid.onlinego.data.model.Cell
import io.zenandroid.onlinego.data.model.Position
import io.zenandroid.onlinego.data.model.StoneType
import io.zenandroid.onlinego.data.model.local.Game
import io.zenandroid.onlinego.data.model.local.Player
import io.zenandroid.onlinego.data.model.ogs.Phase
import io.zenandroid.onlinego.data.ogs.GameConnection
import io.zenandroid.onlinego.data.ogs.OGSWebSocketService
import io.zenandroid.onlinego.data.repositories.ActiveGamesRepository
import io.zenandroid.onlinego.data.repositories.ClockDriftRepository
import io.zenandroid.onlinego.data.repositories.UserSessionRepository
import io.zenandroid.onlinego.gamelogic.RulesManager
import io.zenandroid.onlinego.ui.screens.game.Button.*
import io.zenandroid.onlinego.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val MAX_ATTEMPTS = 3
private const val DELAY_BETWEEN_ATTEMTS = 5000L

class GameViewModel(
    private val activeGamesRepository: ActiveGamesRepository,
    userSessionRepository: UserSessionRepository,
    private val clockDriftRepository: ClockDriftRepository,
    private val socketService: OGSWebSocketService,
): ViewModel() {

    // Need to add a MonotonicFrameClock
    // See: https://github.com/cashapp/molecule/#frame-clock
    private val moleculeScope = CoroutineScope(viewModelScope.coroutineContext + AndroidUiDispatcher.Main)

    private val loading = mutableStateOf(true)
    private lateinit var position: MutableState<Position>
    private val userId = userSessionRepository.userId
    private val candidateMove = mutableStateOf<Cell?>(null)
    private lateinit var gameConnection: GameConnection
    private val gameState = mutableStateOf<Game?>(null)
    private val timer = mutableStateOf(TimerDetails("", "", "", "", 0, 0, false, false))
    private val pendingMove = mutableStateOf<PendingMove?>(null)
    private val retrySendMoveDialogShowing = mutableStateOf(false)
    private val analyzeMode = mutableStateOf(false)
    private val analysisShownMoveNumber = mutableStateOf(0)
    private val passDialogShowing = mutableStateOf(false)
    private val resignDialogShowing = mutableStateOf(false)
    private val gameFinished = mutableStateOf<Boolean?>(null)
    private val gameJustFinished = mutableStateOf(false)

    private var timerJob: Job? = null

    lateinit var state: StateFlow<GameState>

    fun initialize(gameId: Long, gameWidth: Int, gameHeight: Int) {
        val gameFlow = activeGamesRepository.monitorGameFlow(gameId).distinctUntilChanged()
        position = mutableStateOf(Position(gameWidth, gameHeight))
        gameConnection = socketService.connectToGame(gameId, true)

        viewModelScope.launch {
            gameFlow.collect { game ->
                withContext(Dispatchers.IO) {
                    position.value = RulesManager.replay(game)
                    gameState.value = game
                    loading.value = false
                    checkPendingMove(game)
                    timerJob?.cancel()
                    timerJob = viewModelScope.launch {
                        timerRefresher()
                    }
                    if (game.phase == Phase.FINISHED && gameFinished.value == false) { // Game just finished
                        gameJustFinished.value = true
                    }
                    gameFinished.value = game.phase == Phase.FINISHED
                }
            }
        }

        state = moleculeScope.launchMolecule {
            val loading by loading
            val game by gameState
            val position by position
            val timer by timer
            val candidateMove by candidateMove
            val pendingMove by pendingMove
            val retryMoveDialog by retrySendMoveDialogShowing
            val analyzeMode by analyzeMode
            val passDialogShowing by passDialogShowing
            val resignDialogShowing by resignDialogShowing
            val gameFinished by gameFinished
            val gameJustFinished by gameJustFinished
            val analysisShownMoveNumber by analysisShownMoveNumber

            val analysisPosition = remember(analysisShownMoveNumber, game != null) {
                game?.let {
                    RulesManager.replay(game!!, analysisShownMoveNumber, false)
                }
            }

            val isMyTurn =
                game?.phase == Phase.PLAY && (position.nextToMove == StoneType.WHITE && game?.whitePlayer?.id == userId) || (position.nextToMove == StoneType.BLACK && game?.blackPlayer?.id == userId)
            val visibleButtons =
                when {
                    gameFinished == true -> listOf(CHAT, ESTIMATE, PREVIOUS, NEXT)
                    analyzeMode -> listOf(EXIT_ANALYSIS, ESTIMATE, PREVIOUS, NEXT)
                    pendingMove != null -> emptyList()
                    isMyTurn && candidateMove == null -> listOf(ANALYZE, PASS, RESIGN, CHAT, NEXT_GAME)
                    isMyTurn && candidateMove != null -> listOf(CONFIRM_MOVE, DISCARD_MOVE)
                    !isMyTurn && game?.phase == Phase.PLAY -> listOf(ANALYZE, UNDO, RESIGN, CHAT, NEXT_GAME)
                    else -> emptyList()
                }

            val whiteToMove = game?.playerToMoveId == game?.whitePlayer?.id
            val bottomText = when {
                pendingMove != null && pendingMove?.attempt == 1 -> "Submitting move"
                pendingMove != null -> "Submitting move (attempt #${pendingMove?.attempt})"
                else -> null
            }
            val shownPosition =
                if (analyzeMode || gameFinished == true) analysisPosition else position
            val score = if (shownPosition != null && game != null) RulesManager.scorePosition(shownPosition, game!!) else (0f to 0f)
            GameState(
                position = shownPosition,
                loading = loading,
                gameWidth = gameWidth,
                gameHeight = gameHeight,
                candidateMove = candidateMove,
                boardInteractive = isMyTurn && pendingMove == null,
                buttons = visibleButtons,
                title = if (loading) "Loading..." else "Move ${game?.moves?.size} · ${game?.rules?.capitalize()} · ${if (whiteToMove) "White" else "Black"}",
                whitePlayer = game?.whitePlayer?.data(StoneType.WHITE, score.first),
                blackPlayer = game?.blackPlayer?.data(StoneType.BLACK, score.second),
                timerDetails = timer,
                bottomText = bottomText,
                retryMoveDialogShown = retryMoveDialog,
                showAnalysisPanel = analyzeMode || gameFinished == true,
                showPlayers = !(analyzeMode || gameFinished == true),
                passDialogShowing = passDialogShowing,
                resignDialogShowing = resignDialogShowing,
                gameOverDialogShowing = gameJustFinished,
            )
        }
    }

    fun onRetryDialogDismissed() {
        viewModelScope.launch {
            retrySendMoveDialogShowing.value = false
            pendingMove.value = null
        }
    }

    fun onRetryDialogRetry() {
        viewModelScope.launch {
            retrySendMoveDialogShowing.value = false
            pendingMove.value?.let {
                submitMove(it.cell, it.moveNo)
            }
        }
    }

    fun onPassDialogDismissed() {
        passDialogShowing.value = false
    }

    fun onPassDialogConfirm() {
        passDialogShowing.value = false
        submitMove(Cell(-1, -1), gameState.value?.moves?.size ?: 0)
    }

    fun onResignDialogDismissed() {
        resignDialogShowing.value = false
    }

    fun onResignDialogConfirm() {
        resignDialogShowing.value = false
        gameConnection.resign()
    }

    fun onGameOverDialogAnalyze() {
        gameJustFinished.value = false
    }

    fun onGameOverDialogDismissed() {

    }

    private suspend fun timerRefresher() {
        while (true) {
            var delayUntilNextUpdate = 1000L
            gameState.value?.let { game ->
                val maxTime = game.timeControl?.let { timeControl ->
                    when(timeControl.system) {
                        "fischer" -> timeControl.initial_time?.times(1000L)
                        else -> null
                    }
                } ?: 1
                game.clock?.let { clock ->
                    val whiteToMove = game.playerToMoveId == game.whitePlayer.id
                    val blackToMove = game.playerToMoveId == game.blackPlayer.id

                    val whiteTimer = computeTimeLeft(
                        clock,
                        clock.whiteTimeSimple,
                        clock.whiteTime,
                        whiteToMove,
                        game.pausedSince,
                        game.timeControl,
                    )
                    val blackTimer = computeTimeLeft(
                        clock,
                        clock.blackTimeSimple,
                        clock.blackTime,
                        blackToMove,
                        game.pausedSince,
                        game.timeControl,
                        )

                    var timeLeft = null as Long?

                    if (clock.startMode == true) {
                        clock.expiration?.let { expiration ->
                            timeLeft = expiration - clockDriftRepository.serverTime
                            timer.value =
                                if (whiteToMove)
                                    TimerDetails(
                                        whiteFirstLine = formatMillis(timeLeft!!),
                                        whiteSecondLine = "(start)",
                                        whitePercentage = (timeLeft!! / 300000.0 * 100).toInt(),
                                        whiteFaded = false,
                                        blackFirstLine = blackTimer.firstLine ?: "",
                                        blackSecondLine = blackTimer.secondLine ?: "",
                                        blackPercentage = 100,
                                        blackFaded = true,
                                    )
                                else
                                    TimerDetails(
                                        whiteFirstLine = whiteTimer.firstLine ?: "",
                                        whiteSecondLine = whiteTimer.secondLine ?: "",
                                        whitePercentage = 100,
                                        whiteFaded = true,
                                        blackFirstLine = formatMillis(timeLeft!!),
                                        blackSecondLine = "(start)",
                                        blackPercentage = (timeLeft!! / 300000.0 * 100).toInt(),
                                        blackFaded = false,
                                    )
                        }
                    } else {
                        if ((game.phase == Phase.PLAY || game.phase == Phase.STONE_REMOVAL) && !loading.value) {

                            timer.value =
                                TimerDetails(
                                    whiteFirstLine = whiteTimer.firstLine ?: "",
                                    whiteSecondLine = whiteTimer.secondLine ?: "",
                                    whitePercentage = (whiteTimer.timeLeft / maxTime.toDouble() * 100).toInt(),
                                    whiteFaded = blackToMove,
                                    blackFirstLine = blackTimer.firstLine ?: "",
                                    blackSecondLine = blackTimer.secondLine ?: "",
                                    blackPercentage = (blackTimer.timeLeft / maxTime.toDouble() * 100).toInt(),
                                    blackFaded = whiteToMove,
                                )

                            timeLeft = if (whiteToMove) whiteTimer.timeLeft else blackTimer.timeLeft

                        }
                    }
                    delayUntilNextUpdate = timeLeft?.let {
                        when (it) {
                            in 0 until 10_000 -> 100
                            in 10_000 until 3_600_000 -> 1_000
                            in 3_600_000 until 24 * 3_600_000 -> 60_000
                            else -> 12 * 60_000
                        }
                    } ?: 1000
                }
            }

            delay(delayUntilNextUpdate)
        }
    }

    private fun Player.data(color: StoneType, score: Float): PlayerData {
        return PlayerData(
            name = username,
            details = if(score != 0f) "+ $score points" else "",
            rank = formatRank(egfToRank(rating)),
            flagCode = convertCountryCodeToEmojiFlag(country),
            iconURL = icon,
            color = color,
        )
    }

    override fun onCleared() {
        gameConnection.close()
        super.onCleared()
    }

    fun onCellTracked(cell: Cell) {
        if(!position.value.blackStones.contains(cell) && !position.value.whiteStones.contains(cell)) {
            candidateMove.value = cell
        }
    }

    fun onCellTapUp(cell: Cell) {
        viewModelScope.launch {
            val newPosition = RulesManager.makeMove(position.value, position.value.nextToMove, cell)
            if(newPosition == null) {
                candidateMove.value = null
            }
        }
    }

    fun onButtonPressed(button: Button) {
        when(button) {
            CONFIRM_MOVE -> candidateMove.value?.let { submitMove(it,gameState.value?.moves?.size ?: 0) }
            DISCARD_MOVE -> candidateMove.value = null
            ANALYZE -> viewModelScope.launch {
                analysisShownMoveNumber.value = gameState.value?.moves?.size ?: 0
                analyzeMode.value = true
            }
            PASS -> passDialogShowing.value = true
            RESIGN -> resignDialogShowing.value = true
            CHAT -> TODO()
            NEXT_GAME -> TODO()
            UNDO -> TODO()
            EXIT_ANALYSIS -> analyzeMode.value = false
            ESTIMATE -> TODO()
            PREVIOUS -> analysisShownMoveNumber.value = (analysisShownMoveNumber.value - 1).coerceIn(0 .. (gameState.value?.moves?.size ?: 0))
            NEXT -> analysisShownMoveNumber.value = (analysisShownMoveNumber.value + 1).coerceIn(0 .. (gameState.value?.moves?.size ?: 0))
        }
    }

    private fun submitMove(move: Cell, moveNo: Int, attempt: Int = 1) {
        viewModelScope.launch {
            val newMove = PendingMove(
                cell = move,
                moveNo = moveNo,
                attempt = attempt
            )
            pendingMove.value = newMove
            gameConnection.submitMove(move)
            delay(DELAY_BETWEEN_ATTEMTS)
            if(pendingMove.value == newMove) {
                if(attempt >= MAX_ATTEMPTS) {
                    onSubmitMoveFailed()
                } else {
                    submitMove(move, moveNo, attempt + 1)
                }
            }
        }
    }

    private fun onSubmitMoveFailed() {
        retrySendMoveDialogShowing.value = true
    }

    private fun checkPendingMove(game: Game) {
        val expectedMove = pendingMove.value ?: return
        if(game?.moves?.getOrNull(expectedMove.moveNo) == expectedMove.cell) {
            pendingMove.value = null
            candidateMove.value = null
            retrySendMoveDialogShowing.value = false
        }
    }
}

data class GameState(
    val position: Position?,
    val loading: Boolean,
    val gameWidth: Int,
    val gameHeight: Int,
    val candidateMove: Cell?,
    val boardInteractive: Boolean,
    val buttons: List<Button>,
    val title: String,
    val whitePlayer: PlayerData?,
    val blackPlayer: PlayerData?,
    val timerDetails: TimerDetails?,
    val bottomText: String?,
    val retryMoveDialogShown: Boolean,
    val showPlayers: Boolean,
    val showAnalysisPanel: Boolean,
    val passDialogShowing: Boolean,
    val resignDialogShowing: Boolean,
    val gameOverDialogShowing: Boolean,
) {
    companion object {
        val DEFAULT = GameState(
            position = null,
            loading = true,
            gameWidth = 19,
            gameHeight = 19,
            candidateMove = null,
            boardInteractive = false,
            buttons = emptyList(),
            title = "Loading...",
            whitePlayer = null,
            blackPlayer = null,
            timerDetails = null,
            bottomText = null,
            retryMoveDialogShown = false,
            showAnalysisPanel = false,
            showPlayers = true,
            passDialogShowing = false,
            resignDialogShowing = false,
            gameOverDialogShowing = false,
        )
    }
}

data class PlayerData(
    val name: String,
    val details: String,
    val rank: String,
    val flagCode: String,
    val iconURL: String?,
    val color: StoneType,
)

enum class Button(
    val repeatable: Boolean = false
) {
    CONFIRM_MOVE,
    DISCARD_MOVE,
    ANALYZE,
    PASS,
    RESIGN,
    CHAT,
    NEXT_GAME,
    UNDO,
    EXIT_ANALYSIS,
    ESTIMATE,
    PREVIOUS (repeatable = true),
    NEXT (repeatable = true),
}

data class TimerDetails(
    val whiteFirstLine: String,
    val blackFirstLine: String,
    val whiteSecondLine: String,
    val blackSecondLine: String,
    val whitePercentage: Int,
    val blackPercentage: Int,
    val whiteFaded: Boolean,
    val blackFaded: Boolean,
)

data class PendingMove(
    val cell: Cell,
    val moveNo: Int,
    val attempt: Int,
)