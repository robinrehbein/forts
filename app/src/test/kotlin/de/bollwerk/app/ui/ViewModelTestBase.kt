package de.bollwerk.app.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/** Setzt `Dispatchers.Main` auf einen Test-Dispatcher, damit `viewModelScope` ohne Android läuft. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ViewModelTestBase {
    protected val dispatcher: TestDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUpMain() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDownMain() = Dispatchers.resetMain()

    protected fun idle() = dispatcher.scheduler.advanceUntilIdle()

    protected fun advanceMillis(ms: Long) {
        dispatcher.scheduler.advanceTimeBy(ms)
        dispatcher.scheduler.runCurrent()
    }
}
