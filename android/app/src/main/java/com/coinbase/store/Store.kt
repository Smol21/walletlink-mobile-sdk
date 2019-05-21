package com.coinbase.store

import android.content.Context
import com.coinbase.store.exceptions.StoreException
import com.coinbase.store.interfaces.StoreInterface
import com.coinbase.store.models.Optional
import com.coinbase.store.models.StoreKey
import com.coinbase.store.models.StoreKind
import com.coinbase.store.storages.SharedPreferencesStorage
import com.coinbase.store.storages.MemoryStorage
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

// FIXME: hish - Figure out how to split encrypted vs non-encrypted

class Store(context: Context) : StoreInterface {
    private val appPrefStorage = SharedPreferencesStorage(context)
    private val memoryStorage = MemoryStorage()
    private val changeObservers = mutableMapOf<String, Any>()
    private val changeObserversLock = ReentrantReadWriteLock()

    override fun <T> set(key: StoreKey<T>, value: T?) {
        return when (key.kind) {
            StoreKind.SHARED_PREFERENCES -> appPrefStorage.set(key.name, value, key.clazz)
            StoreKind.MEMORY -> memoryStorage.set(key.name, value, key.clazz)
        }
    }

    override fun <T> get(key: StoreKey<T>): T? {
        return when (key.kind) {
            StoreKind.SHARED_PREFERENCES -> appPrefStorage.get(key.name, key.clazz)
            StoreKind.MEMORY -> memoryStorage.get(key.name, key.clazz)
        }
    }

    override fun <T> has(key: StoreKey<T>): Boolean {
        return get(key) != null
    }

    override fun <T> observe(key: StoreKey<T>): Observable<Optional<T>> {
        return observer(key).hide()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> observer(key: StoreKey<T>): BehaviorSubject<Optional<T>> {
        // Check if we have an observer registered in concurrent mode
        var currentObserver: BehaviorSubject<Optional<T>>? = null
        changeObserversLock.read {
            currentObserver = changeObservers[key.name] as? BehaviorSubject<Optional<T>>
        }

        val anObserver = currentObserver
        if (anObserver != null) {
            return anObserver
        }

        // If we can't find an observer, enter serial mode and check or create new observer
        var newObserver: BehaviorSubject<Optional<T>>? = null
        val value = get(key)

        changeObserversLock.write {
            changeObservers[key.name]?.let { return it as BehaviorSubject<Optional<T>> }

            val observer = BehaviorSubject.create<Optional<T>>()
            changeObservers[key.name] = observer
            newObserver = observer

            observer.onNext(Optional(value))
        }

        return newObserver ?: throw StoreException.UnableToCreateObserver()
    }
}