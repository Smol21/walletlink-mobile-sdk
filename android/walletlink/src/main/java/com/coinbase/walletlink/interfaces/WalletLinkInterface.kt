package com.coinbase.walletlink.interfaces

import com.coinbase.walletlink.models.HostRequest
import com.coinbase.walletlink.models.HostRequestId
import com.coinbase.walletlink.models.ClientMetadataKey
import io.reactivex.Observable
import io.reactivex.Single
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * WalletLink SDK interface
 */
interface WalletLinkInterface {
    /**
     * Incoming host requests
     */
    val requestsObservable: Observable<HostRequest>

    /**
    * Starts WalletLink connection with the server if a stored session exists. Otherwise, this is a noop. This method
    * should be called immediately on app launch.
    *
    * @param metadata client metadata forwarded to host once link is established
    */
    fun connect(metadata: ConcurrentHashMap<ClientMetadataKey, String>)

    /**
     * Disconnect from WalletLink server and stop observing session ID updates to prevent reconnection.
     */
    fun disconnect()

    /**
     * Connect to WalletLink server using parameters extracted from QR code scan
     *
     * @param sessionId WalletLink host generated session ID
     * @param name Host name
     * @param secret WalletLink host/guest shared secret
     * @param rpcUrl WalletLink server websocket URL
     * @param metadata client metadata forwarded to host once link is established
     *
     * @return A single wrapping `Unit` if connection was successful. Otherwise, an exception is thrown
     */
    fun link(
        sessionId: String,
        name: String,
        secret: String,
        rpcUrl: URL,
        metadata: ConcurrentHashMap<ClientMetadataKey, String>
    ): Single<Unit>

    /**
     * Set metadata in all active sessions. This metadata will be forwarded to all the hosts
     *
     * @param key Metadata key
     * @param value Metadata value
     *
     * @return A single wrapping `Unit` if operation was successful. Otherwise, an exception is thrown
     */
    fun setMetadata(key: ClientMetadataKey, value: String): Single<Unit>

    /**
     * Send signature request approval to the requesting host
     *
     * @param requestId WalletLink host generated request ID
     * @param signedData: User signed data
     *
     * @return A single wrapping `Unit` if operation was successful. Otherwise, an exception is thrown
     */
    fun approve(requestId: HostRequestId, signedData: ByteArray): Single<Unit>

    /**
     * Send signature request rejection to the requesting host
     *
     * @param requestId WalletLink host generated request ID
     *
     * @return A single wrapping `Unit` if operation was successful. Otherwise, an exception is thrown
     */
    fun reject(requestId: HostRequestId): Single<Unit>

    /** Get an event
     *
     * @param eventId The event ID
     * @param sessionId The session ID
     * @param rpcUrl The RPC URL
     *
     * @return A Single wrapping the HostRequest
     */
    fun getRequest(eventId: String, sessionId: String, rpcUrl: URL): Single<HostRequest>
}
