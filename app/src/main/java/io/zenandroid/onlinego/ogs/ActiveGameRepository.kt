package io.zenandroid.onlinego.ogs

import android.util.Log
import com.crashlytics.android.Crashlytics
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.zenandroid.onlinego.OnlineGoApplication
import io.zenandroid.onlinego.extensions.addToDisposable
import io.zenandroid.onlinego.gamelogic.Util
import io.zenandroid.onlinego.gamelogic.Util.isMyTurn
import io.zenandroid.onlinego.model.local.Clock
import io.zenandroid.onlinego.model.local.Game
import io.zenandroid.onlinego.model.local.HistoricGamesMetadata
import io.zenandroid.onlinego.model.ogs.GameData
import io.zenandroid.onlinego.model.ogs.OGSGame
import io.zenandroid.onlinego.model.ogs.Phase
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Created by alex on 08/11/2017.
 */
object ActiveGameRepository {

    private val activeDbGames = mutableMapOf<Long, Game>()
    private val gameConnections = mutableSetOf<Long>()
    private val connectedGameCache = hashMapOf<Long, Game>()

    private val myMoveCountSubject = BehaviorSubject.create<Int>()

    private val subscriptions = CompositeDisposable()

    private val gameDao by lazy { OnlineGoApplication.instance.db.gameDao() }

    private var hasFetchedAllHistoricGames = false
    private var oldestGameFetchedEndedAt: Long? = null
    private var newestGameFetchedEndedAt: Long? = null

    private fun onNotification(game: OGSGame) {
        if(gameDao.getGameMaybe(game.id).blockingGet() == null) {
            Crashlytics.log(Log.VERBOSE, "ActiveGameRepository", "New game found from active_game notification ${game.id}")
            OGSServiceImpl.fetchGame(game.id)
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.single())
                    .map(Game.Companion::fromOGSGame)
                    .retryWhen(this::retryIOException)
                    .subscribe({
                        gameDao.insertAllGames(listOf(it))
                    }, { this.onError(it, "onNotification") })
                    .addToDisposable(subscriptions)
        }
    }

    val myMoveCountObservable: Observable<Int>
        @Synchronized get() = myMoveCountSubject.distinctUntilChanged()

    val myTurnGamesList: List<Game>
        @Synchronized get() = activeDbGames.values.filter(Util::isMyTurn).toList()

    internal fun subscribe() {
        refreshActiveGames()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe({}, { this.onError(it, "monitorActiveGames") })
                .addToDisposable(subscriptions)
        OGSServiceImpl.connectToActiveGames()
                .subscribeOn(Schedulers.io())
                .subscribe(this::onNotification) { this.onError(it, "connectToActiveGames") }
                .addToDisposable(subscriptions)
        gameDao
                .monitorActiveGamesWithNewMessagesCount(OGSServiceImpl.uiConfig?.user?.id)
                .subscribe(this::setActiveGames) { this.onError(it, "gameDao") }
                .addToDisposable(subscriptions)

        gameDao.monitorHistoricGameMetadata()
                .distinctUntilChanged()
                .subscribeOn(Schedulers.io())
                .subscribe(this::onMetadata, { this.onError(it, "monitorHistoricGameMetadata") })
                .addToDisposable(subscriptions)
    }

    fun unsubscribe() {
        subscriptions.clear()
        gameConnections.clear()
    }

    @Synchronized
    private fun isGameActive(id: Long) =
            activeDbGames.containsKey(id)

    private fun connectToGame(baseGame: Game) {
        val game = baseGame.copy()
        synchronized(connectedGameCache) {
            connectedGameCache[game.id] = game
        }
        if(gameConnections.contains(game.id)) {
            return
        }
        gameConnections.add(game.id)

        val gameConnection = OGSServiceImpl.connectToGame(game.id)
        gameConnection.addToDisposable(subscriptions)
        gameConnection.gameData
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe (
                        { onGameData(game.id, it) },
                        { this.onError(it, "gameData") }
                )
                .addToDisposable(subscriptions)
        gameConnection.moves
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe (
                        { onGameMove(game.id, it) },
                        { this.onError(it, "moves") }
                )
                .addToDisposable(subscriptions)
        gameConnection.clock
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe (
                        { onGameClock(game.id, it) },
                        { this.onError(it, "clock") }
                )
                .addToDisposable(subscriptions)
        gameConnection.phase
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe (
                        { onGamePhase(game.id, it) },
                        { this.onError(it, "phase") }
                )
                .addToDisposable(subscriptions)
        gameConnection.removedStones
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe (
                        { onGameRemovedStones(game.id, it) },
                        { this.onError(it, "removedStones") }
                )
                .addToDisposable(subscriptions)
        gameConnection.undoRequested
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe (
                        { onUndoRequested(game.id, it) },
                        { this.onError(it, "undoRequested") }
                )
                .addToDisposable(subscriptions)
        gameConnection.removedStonesAccepted
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe (
                        { onRemovedStonesAccepted(game.id, it) },
                        { this.onError(it, "removedStonesAccepted") }
                )
                .addToDisposable(subscriptions)
    }

    private fun onGameRemovedStones(gameId: Long, stones: RemovedStones) {
        gameDao.updateRemovedStones(gameId, stones.all_removed ?: "")
    }

    private fun onRemovedStonesAccepted(gameId: Long, accepted: RemovedStonesAccepted) {
        gameDao.updateRemovedStonesAccepted(gameId, accepted.players?.white?.accepted_stones, accepted.players?.black?.accepted_stones)
    }

    private fun onUndoRequested(gameId: Long, moveNo: Int) {
        gameDao.updateUndoRequested(gameId, moveNo)
    }

    private fun onGamePhase(gameId: Long, newPhase: Phase) {
        gameDao.updatePhase(gameId, newPhase)
    }

    private fun onGameClock(gameId: Long, clock: OGSClock) {
        gameDao.updateClock(
                id = gameId,
                playerToMoveId = clock.current_player,
                clock = Clock.fromOGSClock(clock)
        )
    }

    private fun onGameData(gameId: Long, gameData: GameData) {
        gameDao.updateGameData(
                id = gameId,
                outcome = gameData.outcome,
                phase = gameData.phase,
                playerToMoveId = gameData.clock?.current_player,
                initialState = gameData.initial_state,
                whiteGoesFirst = gameData.initial_player == "white",
                moves = gameData.moves.map { mutableListOf(it[0].toInt(), it[1].toInt()) }.toMutableList(),
                removedStones = gameData.removed,
                whiteScore = gameData.score?.white,
                blackScore = gameData.score?.black,
                clock = Clock.fromOGSClock(gameData.clock),
                undoRequested = gameData.undo_requested,
                whiteLost = gameData.winner?.let { it == gameData.black_player_id },
                blackLost = gameData.winner?.let { it == gameData.white_player_id },
                ended = gameData.end_time?.let { it * 1000 }
        )
    }

    private fun onGameMove(gameId: Long, move: Move ) {
        synchronized(connectedGameCache) {
            connectedGameCache[gameId]?.let { game ->
                game.moves?.let {
                    val newMoves = it.toMutableList().apply {
                        add(mutableListOf(move.move[0].toInt(), move.move[1].toInt()))
                    }
                    gameDao.updateMoves(game.id, newMoves)
                }
            }
        }
    }

    @Synchronized
    private fun setActiveGames(games : List<Game>) {
        activeDbGames.clear()
        games.forEach {
            activeDbGames[it.id] = it
            connectToGame(it)
        }
        myMoveCountSubject.onNext(activeDbGames.values.count { isMyTurn(it) })
    }

    fun monitorGame(id: Long): Flowable<Game> {
        // TODO: Maybe check if the data is fresh enough to warrant skipping this call?
        OGSServiceImpl
                .fetchGame(id)
                .map(Game.Companion::fromOGSGame)
                .map(::listOf)
                .retryWhen (this::retryIOException)
                .subscribe(
                        gameDao::insertAllGames,
                        { this.onError(it, "monitorGame") }
                ).addToDisposable(subscriptions)

        return gameDao.monitorGame(id)
                .doOnNext(this::connectToGame)
    }

    private fun retryIOException(it: Flowable<Throwable>) =
            it.flatMap {
                when (it) {
                    is IOException -> Flowable.timer(15, TimeUnit.SECONDS)
                    else -> Flowable.error<Long>(it)
                }
            }

    fun getGameSingle(id: Long): Single<Game> {
        return gameDao.monitorGame(id).take(1).firstOrError()
    }

    fun refreshActiveGames(): Completable =
            OGSServiceImpl.fetchActiveGames()
                    .map { it.map (Game.Companion::fromOGSGame)}
                    .doOnSuccess(gameDao::insertAllGames)
                    .doOnSuccess { Crashlytics.log("overview returned ${it.size} games") }
                    .map { it.map(Game::id) }
                    .map { gameDao.getGamesThatShouldBeFinished(OGSServiceImpl.uiConfig?.user?.id, it) }
                    .doOnSuccess { Crashlytics.log("Found ${it.size} games that are neither active nor marked as finished") }
                    .flattenAsObservable { it }
                    .flatMapSingle { OGSServiceImpl.fetchGame(it) }
                    .map (Game.Companion::fromOGSGame)
                    .doOnNext { if(it.phase != Phase.FINISHED) Crashlytics.logException(Exception("Game ${it.id} ${it.phase} was not returned by overview but is not yet finished")) }
                    .toList()
                    .doOnSuccess(gameDao::updateGames)
                    .retryWhen (this::retryIOException)
                    .ignoreElement()

    fun monitorActiveGames(): Flowable<List<Game>> {
        return gameDao.monitorActiveGamesWithNewMessagesCount(OGSServiceImpl.uiConfig?.user?.id)
    }

    fun getRecentlyFinishedGames(): Flowable<List<Game>> {
        fetchRecentlyFinishedGames()
        return gameDao
                .monitorRecentGames(OGSServiceImpl.uiConfig?.user?.id)
                .doOnNext { it.forEach(this::connectToGame) } // <- NOTE: We're connecting to the recent games just because of the chat...
    }

    private fun onError(t: Throwable, request: String) {
        var message = request
        if(t is retrofit2.HttpException) {
            message = "$request: ${t.response()?.errorBody()?.string()}"
            if(t.code() == 429) {
                Crashlytics.setBool("HIT_RATE_LIMITER", true)
            }
        }
        Crashlytics.logException(Exception(message, t))
        Log.e("ActiveGameRespository", message, t)
    }

    fun getHistoricGames(endedBefore: Long?): Flowable<HistoricGamesRepositoryResult> {
        val dbObservable = if(endedBefore == null) {
            gameDao.monitorFinishedNotRecentGames(OGSServiceImpl.uiConfig?.user?.id)
        } else {
            gameDao.monitorFinishedGamesEndedBefore(OGSServiceImpl.uiConfig?.user?.id, endedBefore)
        }

        return dbObservable.distinctUntilChanged()
                .map {
                    if(it.size < 10 && hasFetchedAllHistoricGames) {
                        return@map HistoricGamesRepositoryResult(it, false)
                    } else if(it.size < 10) {
                        fetchMoreHistoricGames()
                        return@map HistoricGamesRepositoryResult(it, true)
                    } else {
                        return@map HistoricGamesRepositoryResult(it, true)
                    }
                }
    }

    private fun fetchRecentlyFinishedGames() {
        OGSServiceImpl.fetchHistoricGamesAfter(newestGameFetchedEndedAt)
                .map { it.map(OGSGame::id) }
                .map { it - gameDao.getHistoricGamesThatDontNeedUpdating(it) }
                .flattenAsObservable { it }
                .flatMapSingle { OGSServiceImpl.fetchGame(it) }
                .map (Game.Companion::fromOGSGame)
                .toList()
                .retryWhen (this::retryIOException)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe(
                        { onHistoricGames(it) },
                        { this.onError(it, "fetchRecentlyFinishedGames") }
                ).addToDisposable(subscriptions)
    }

    private fun fetchMoreHistoricGames() {
        OGSServiceImpl.fetchHistoricGamesBefore(oldestGameFetchedEndedAt)
                .doOnSuccess {
                    if(it.isEmpty()) {
                        val newMetadata = HistoricGamesMetadata(
                                oldestGameEnded = oldestGameFetchedEndedAt,
                                newestGameEnded = newestGameFetchedEndedAt,
                                loadedOldestGame = true
                        )
                        gameDao.updateHistoricGameMetadata(newMetadata)
                        onMetadata(newMetadata)
                    }
                }
                .map { it.map(OGSGame::id) }
                .map { it - gameDao.getHistoricGamesThatDontNeedUpdating(it) }
                .flattenAsObservable { it }
                .flatMapSingle { OGSServiceImpl.fetchGame(it) }
                .map (Game.Companion::fromOGSGame)
                .toList()
                .retryWhen (this::retryIOException)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.single())
                .subscribe(
                        { onHistoricGames(it) },
                        { this.onError(it, "fetchHistoricGames") }
                ).addToDisposable(subscriptions)
    }

    private fun onHistoricGames(games: List<Game>) {
        val oldestGame = games.minBy { it.ended ?: Long.MAX_VALUE }
        val newOldestDate = when {
            oldestGameFetchedEndedAt == null -> {
                oldestGame?.ended
            }
            oldestGame?.ended == null -> {
                oldestGameFetchedEndedAt
            }
            else -> {
                Math.min(oldestGameFetchedEndedAt!!, oldestGame.ended!!)
            }
        }

        val newestGame = games.maxBy { it.ended ?: Long.MIN_VALUE }
        val newNewestDate = when {
            newestGameFetchedEndedAt == null -> {
                newestGame?.ended
            }
            newestGame?.ended == null -> {
                newestGameFetchedEndedAt
            }
            else -> {
                Math.max(newestGameFetchedEndedAt!!, newestGame.ended!!)
            }
        }
        val metadata = HistoricGamesMetadata(
                oldestGameEnded = newOldestDate,
                newestGameEnded = newNewestDate,
                loadedOldestGame = hasFetchedAllHistoricGames
        )
        gameDao.insertHistoricGames(games, metadata)
        onMetadata(metadata)
    }

    private fun onMetadata(metadata: HistoricGamesMetadata) {
        hasFetchedAllHistoricGames = metadata.loadedOldestGame ?: false
        oldestGameFetchedEndedAt = metadata.oldestGameEnded
        newestGameFetchedEndedAt = metadata.newestGameEnded
    }
}

data class HistoricGamesRepositoryResult(
        val games: List<Game>,
        val loading: Boolean
)