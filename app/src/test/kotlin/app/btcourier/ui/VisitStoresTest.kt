package app.btcourier.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** A visit's view models survive re-lookups (configuration changes) until navigation leaves it. */
class VisitStoresTest {
    private val cleared = mutableListOf<String>()

    private inner class Tracked(
        val name: String,
    ) : ViewModel() {
        override fun onCleared() {
            cleared += name
        }
    }

    private fun VisitStores.tracked(visit: String) = viewModel(visit) { Tracked(visit) }

    @Test
    fun `the same visit gets the same view model until it is left`() {
        val stores = VisitStores()
        val first = stores.tracked("a")

        stores.keepOnly("a")

        assertSame(first, stores.tracked("a"))
        assertEquals(emptyList<String>(), cleared)
    }

    @Test
    fun `moving to another visit clears the one left, and leaving all clears the rest`() {
        val stores = VisitStores()
        val a = stores.tracked("a")
        stores.tracked("b")

        stores.keepOnly("b")
        assertEquals(listOf("a"), cleared)
        assertNotSame(a, stores.tracked("a"), "a visit id seen again starts afresh")

        stores.keepOnly(null)
        assertEquals(setOf("a", "b"), cleared.drop(1).toSet())
    }

    @Test
    fun `clearing the holder with the activity clears every visit`() {
        val activityStore = ActivityStore()
        val holder =
            ViewModelProvider(
                activityStore,
                viewModelFactory {
                    initializer { VisitStoresHolder() }
                },
            )[VisitStoresHolder::class.java]
        holder.stores.tracked("a")
        holder.stores.tracked("b")

        activityStore.viewModelStore.clear()

        assertEquals(setOf("a", "b"), cleared.toSet())
    }

    private class ActivityStore : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
}
