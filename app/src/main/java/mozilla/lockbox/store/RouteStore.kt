/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package mozilla.lockbox.store

import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.Relay
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import mozilla.lockbox.action.DialogAction
import mozilla.lockbox.action.OnboardingStatusAction
import mozilla.lockbox.action.RouteAction
import mozilla.lockbox.extensions.filterByType
import mozilla.lockbox.extensions.filterNotNull
import mozilla.lockbox.flux.Dispatcher
import mozilla.lockbox.flux.StackReplaySubject
import mozilla.lockbox.support.Optional
import mozilla.lockbox.support.asOptional

@ExperimentalCoroutinesApi
open class RouteStore(
    dispatcher: Dispatcher = Dispatcher.shared,
    dataStore: DataStore = DataStore.shared
) {
    companion object {
        val shared by lazy { RouteStore() }
    }

    internal val compositeDisposable = CompositeDisposable()

    private val onboarding: Observable<Boolean> = BehaviorRelay.createDefault(false)
    private val _routes = StackReplaySubject.create<RouteAction>()
    open val routes: Observable<RouteAction> = _routes

    init {
        dispatcher.register
            .filterByType(RouteAction::class.java)
            .subscribe { routeAction ->
                when (routeAction) {
                    is RouteAction.InternalBack -> _routes.safePop()
                    else -> _routes.onNext(routeAction)
                }

                // If we're about to leave the app and then come back again
                // then trim the history of any dialogs that have just been dismissed
                // or the very route action that caused us to leave the app.
                _routes.trim {
                    when (it) {
                        is DialogAction,
                        is RouteAction.AutoLockSetting,
                        is RouteAction.DialogFragment,
                        is RouteAction.EditItemDetail,
                        is RouteAction.SystemIntent -> true
                        else -> false
                    }
                }
            }
            .addTo(compositeDisposable)

        dispatcher.register
            .filterByType(OnboardingStatusAction::class.java)
            .map { it.onboardingInProgress }
            .subscribe(onboarding as Relay)
            .addTo(compositeDisposable)

        Observables.combineLatest(dataStore.state, onboarding)
            .filter { !it.second }
            .map { dataStoreToRouteActions(it.first) }
            .filterNotNull()
            .subscribe(_routes)
    }

    private fun dataStoreToRouteActions(storageState: DataStore.State): Optional<RouteAction> {
        return when (storageState) {
            is DataStore.State.Unlocked -> RouteAction.ItemList
            is DataStore.State.Locked -> RouteAction.LockScreen
            is DataStore.State.Unprepared -> RouteAction.Welcome
            else -> null
        }.asOptional()
    }

    fun clearBackStack() {
        _routes.trimTail()
    }
}