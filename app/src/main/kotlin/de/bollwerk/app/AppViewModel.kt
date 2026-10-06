package de.bollwerk.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import de.bollwerk.app.di.AppGraph
import de.bollwerk.app.nav.Navigator
import java.util.concurrent.ConcurrentHashMap

/** ViewModel-Speicher je Navigationseintrag; wird beim Entfernen des Eintrags geleert. */
class EntryViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

/** Wurzel-ViewModel der Activity: hält [AppGraph], den [Navigator] und die Speicher der Bildschirm-ViewModels. */
class AppViewModel(val graph: AppGraph) : ViewModel() {
    private val owners = ConcurrentHashMap<Long, EntryViewModelStoreOwner>()

    /** Speicher entstehen nur im Navigator (beim Anlegen des Eintrags) und werden nur dort wieder entfernt. */
    val navigator = Navigator(
        onEntryAdded = { entry -> owners[entry.id] = EntryViewModelStoreOwner() },
        onEntryRemoved = { entry -> owners.remove(entry.id)?.viewModelStore?.clear() },
    )

    /**
     * Speicher eines Eintrags; `null`, wenn der Eintrag schon entfernt wurde (die Oberfläche liest dann noch
     * einen veralteten Stapel und zeichnet beim nächsten Stand neu). Legt nie selbst einen Speicher an.
     */
    fun ownerFor(entryId: Long): EntryViewModelStoreOwner? = owners[entryId]

    override fun onCleared() {
        owners.values.forEach { it.viewModelStore.clear() }
        owners.clear()
    }
}
