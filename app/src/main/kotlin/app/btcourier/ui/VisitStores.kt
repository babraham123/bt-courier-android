package app.btcourier.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * One [ViewModelStore] per screen visit, so a screen's view models survive configuration changes
 * but are cleared, cancelling their work, once navigation leaves that visit.
 */
class VisitStores {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun owner(visit: String): ViewModelStoreOwner {
        val store = stores.getOrPut(visit) { ViewModelStore() }
        return object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
    }

    /** Clears every store but [visit]'s; null clears them all. */
    fun keepOnly(visit: String?) {
        val gone = stores.keys - setOfNotNull(visit)
        gone.forEach { stores.remove(it)?.clear() }
    }
}

/** Holds the [VisitStores] in the activity's own store, which outlives configuration changes. */
class VisitStoresHolder : ViewModel() {
    val stores = VisitStores()

    override fun onCleared() = stores.keepOnly(null)
}
