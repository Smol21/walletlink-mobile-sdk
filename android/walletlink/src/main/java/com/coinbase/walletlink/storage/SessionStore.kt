package com.coinbase.walletlink.storage

import com.coinbase.wallet.store.interfaces.StoreInterface
import com.coinbase.walletlink.models.Session
import com.coinbase.walletlink.models.StoreKeys
import io.reactivex.Observable
import java.net.URL
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SessionStore(private val store: StoreInterface) {
    private val accessLock = ReentrantLock()

    /**
     * Get stored sessions
     */
    val sessions: List<Session> get() = getStoredSessions()

    /**
     * Get stored sessions filtered by url
     *
     * @param url URL to filter sessions
     *
     * @return Sessions for given URL
     */
    fun getSessions(url: URL): List<Session> = getStoredSessions().filter { it.rpcUrl == url }

    /**
     * Get stored session for given sessionID and rpc URL
     *
     * @param id Session ID
     * @param url URL to filter sessions
     *
     * @returns Sessions for given URL
     */
    fun getSession(id: String, url: URL): Session? = getStoredSessions().firstOrNull { it.rpcUrl == url && it.id == id }

    /**
     * Store session/secret to shared preferences using Android KeyStore
     *
     * @param rpcUrl WalletLink server websocket URL
     * @param sessionId Session ID generated by the host
     * @param name Host name
     * @param secret Secret generated by the host
     */
    fun save(rpcUrl: URL, sessionId: String, name: String, secret: String) = accessLock.withLock {
        val sessions = (store.get(StoreKeys.sessions) ?: arrayOf())
            .filter { it.id != sessionId && it.rpcUrl == rpcUrl }.toMutableList()

        sessions.add(Session(id = sessionId, name = name, secret = secret, rpcUrl = rpcUrl))

        store.set(StoreKeys.sessions, sessions.toTypedArray())
    }

    /**
     * Deletes sessionID from keychain
     *
     * @param sessionId Session ID generated by the host
     */
    fun delete(rpcUrl: URL, sessionId: String) = accessLock.withLock {
        val sessionIds = (store.get(StoreKeys.sessions) ?: arrayOf())
            .filter { it.id != sessionId && it.rpcUrl == rpcUrl }.toMutableList()

        store.set(StoreKeys.sessions, sessionIds.toTypedArray())
    }


    /**
     * Observe for distinct stored sessionIds update
     *
     * @param url URL to filter sessions
     *
     * @return Session observable for given URL
     */
    fun observeSessions(url: URL): Observable<List<Session>> = store.observe(StoreKeys.sessions)
        .map { list -> list.element?.filter { it.rpcUrl == url }?.sortedBy { it.id } ?: listOf() }
        .distinctUntilChanged()

    // Private helpers

    private fun getStoredSessions(): List<Session> = accessLock
        .withLock { store.get(StoreKeys.sessions)?.toList() ?: listOf() }
        .sortedBy { it.id }

}
