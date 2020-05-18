package io.zenandroid.onlinego.data.repositories

import android.util.Log
import com.crashlytics.android.Crashlytics
import io.reactivex.Flowable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.zenandroid.onlinego.OnlineGoApplication
import io.zenandroid.onlinego.utils.addToDisposable
import io.zenandroid.onlinego.data.model.local.Game
import io.zenandroid.onlinego.data.model.local.HistoricGamesMetadata
import io.zenandroid.onlinego.data.model.ogs.OGSGame
import io.zenandroid.onlinego.data.ogs.OGSServiceImpl
import java.io.IOException
import java.util.concurrent.TimeUnit

object FinishedGamesRepository {
    data class HistoricGamesRepositoryResult(
            val games: List<Game>,
            val loading: Boolean
    )

    private val subscriptions = CompositeDisposable()

    private val gameDao by lazy { OnlineGoApplication.instance.db.gameDao() }

    private var hasFetchedAllHistoricGames = false
    private var oldestGameFetchedEndedAt: Long? = null
    private var newestGameFetchedEndedAt: Long? = null

    internal fun subscribe() {
        gameDao.monitorHistoricGameMetadata()
                .distinctUntilChanged()
                .subscribeOn(Schedulers.io())
                .subscribe(this::onMetadata, { onError(it, "monitorHistoricGameMetadata") })
                .addToDisposable(subscriptions)
    }

    fun unsubscribe() {
        subscriptions.clear()
    }

    fun getRecentlyFinishedGames(): Flowable<List<Game>> {
        fetchRecentlyFinishedGames()
        return gameDao
                .monitorRecentGames(OGSServiceImpl.uiConfig?.user?.id)
                .doOnNext { it.forEach(ActiveGamesRepository::connectToGame) } // <- NOTE: We're connecting to the recent games just because of the chat...
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
        Log.e("FinishedGameRepository", message, t)
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
                        { onError(it, "fetchRecentlyFinishedGames") }
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
                        { onError(it, "fetchHistoricGames") }
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

    private fun retryIOException(it: Flowable<Throwable>) =
            it.flatMap {
                when (it) {
                    is IOException -> Flowable.timer(15, TimeUnit.SECONDS)
                    else -> Flowable.error<Long>(it)
                }
            }

}