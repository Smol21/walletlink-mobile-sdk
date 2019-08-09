package com.coinbase.walletlink

import android.content.Context
import com.coinbase.wallet.core.extensions.asUnit
import com.coinbase.wallet.core.extensions.reduceIntoMap
import com.coinbase.wallet.core.extensions.unwrap
import com.coinbase.wallet.core.extensions.zipOrEmpty
import com.coinbase.wallet.core.util.BoundedSet
import com.coinbase.wallet.core.util.Optional
import com.coinbase.walletlink.apis.WalletLinkConnection
import com.coinbase.walletlink.exceptions.WalletLinkException
import com.coinbase.walletlink.models.HostRequest
import com.coinbase.walletlink.models.HostRequestId
import com.coinbase.walletlink.models.Session
import com.coinbase.walletlink.models.ClientMetadataKey
import com.coinbase.walletlink.models.RequestMethod
import com.coinbase.walletlink.repositories.LinkRepository
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import java.math.BigInteger
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * WalletLink SDK interface
 *
 * @property userId User ID to deliver push notifications to
 * @property notificationUrl Webhook URL used to push notifications to mobile client
 */
class WalletLink(private val notificationUrl: URL, context: Context) : WalletLinkInterface {
    private val requestsSubject = PublishSubject.create<HostRequest>()
    private val requestsScheduler = Schedulers.single()
    private val processedRequestIds = BoundedSet<HostRequestId>(3000)
    private val linkRepository = LinkRepository(context)
    private val disposeBag = CompositeDisposable()
    private var connections = ConcurrentHashMap<URL, WalletLinkConnection>()

    override val requestsObservable: Observable<HostRequest>  by lazy {
        val observable1 = Observable.just(
            HostRequest.DappPermission(
                HostRequestId(
                    UUID.randomUUID().toString(),
                    "b",
                    "c",
                    URL("http://www.google.com"),
                    URL("http://www.google.com"),
                    URL("http://www.google.com"),
                    "Crypto Kitties",
                    RequestMethod.SignEthereumTransaction
                )
            )
        )
            .delay(3, TimeUnit.SECONDS)
            .map { it as HostRequest }

        val observable2 = Observable.just(
            HostRequest.SignMessage(
                HostRequestId(
                    UUID.randomUUID().toString(),
                    "b",
                    "c",
                    URL("http://www.google.com"),
                    URL("http://www.google.com"),
                    URL("http://www.google.com"),
                    "Crypto Kitties",
                    RequestMethod.SignEthereumTransaction
                ),
                "0xdF0635793e91D4F8e7426Dbd9Ed08471186F428D",
                "message",
                false
            )
        )
            .delay(6, TimeUnit.SECONDS)
            .map { it as HostRequest }

        val observable3 = Observable.just(
            HostRequest.SignAndSubmitTx(
                HostRequestId(
                    UUID.randomUUID().toString(),
                    "b",
                    "c",
                    URL("http://www.google.com"),
                    URL("http://www.google.com"),
                    URL("http://www.google.com"),
                    "Crypto Kitties",
                    RequestMethod.SignEthereumTransaction
                ),
                "0xdF0635793e91D4F8e7426Dbd9Ed08471186F428D",
                "toAddress",
                BigInteger.ONE,
                ByteArray(0),
                null,
                null,
                null,
                1,
                false))
            .delay(8, TimeUnit.SECONDS)
            .map { it as HostRequest }

        Observable.concat(observable1, observable2, observable3)
    }


    override fun sessions(): List<Session> = linkRepository.sessions

    override fun connect(userId: String, metadata: ConcurrentHashMap<ClientMetadataKey, String>) {
        val connections = ConcurrentHashMap<URL, WalletLinkConnection>()
        val sessionsByUrl = linkRepository.sessions.reduceIntoMap(HashMap<URL, List<Session>>()) { acc, session ->
            val sessions = acc[session.url]?.toMutableList()?.apply { add(session) }

            acc[session.url] = sessions?.toList() ?: mutableListOf(session)
        }

        for ((rpcUrl, sessions) in sessionsByUrl) {
            val conn = WalletLinkConnection(
                url = rpcUrl,
                userId = userId,
                notificationUrl = notificationUrl,
                linkRepository = linkRepository,
                metadata = metadata
            )

            observeConnection(conn)
            sessions.forEach { connections[it.url] = conn }
        }

        this.connections = connections
    }

    override fun disconnect() {
        disposeBag.clear()
        connections.values.forEach { it.disconnect() }
        connections.clear()
    }

    override fun link(
        sessionId: String,
        secret: String,
        url: URL,
        userId: String,
        metadata: ConcurrentHashMap<ClientMetadataKey, String>
    ): Single<Unit> {
        connections[url]?.let { connection ->
            return connection.link(sessionId = sessionId, secret = secret)
        }

        val connection = WalletLinkConnection(
            url = url,
            userId = userId,
            notificationUrl = notificationUrl,
            linkRepository = linkRepository,
            metadata = metadata
        )

        connections[url] = connection

        return connection.link(sessionId = sessionId, secret = secret)
            .map { observeConnection(connection) }
            .onErrorResumeNext { throwable ->
                connections.remove(url)
                throw throwable
            }
    }

    override fun unlink(session: Session) = linkRepository.delete(session.url, session.id)

    override fun setMetadata(key: ClientMetadataKey, value: String): Single<Unit> {
        val setMetadataSingles = connections.values
            .map { it.setMetadata(key = key, value = value).asUnit().onErrorReturn { Single.just(Unit) } }

        return Single.zip(setMetadataSingles) { it.filterIsInstance<Unit>() }.asUnit()
    }

    override fun approve(requestId: HostRequestId, signedData: ByteArray): Single<Unit> {
        val connection = connections[requestId.url] ?: return Single.error(
            WalletLinkException.NoConnectionFound(requestId.url)
        )

        return connection.approve(requestId, signedData)
    }

    override fun reject(requestId: HostRequestId): Single<Unit> {
        val connection = connections[requestId.url] ?: return Single.error(
            WalletLinkException.NoConnectionFound(requestId.url)
        )

        return connection.reject(requestId)
    }

    override fun markAsSeen(requestIds: List<HostRequestId>): Single<Unit> = requestIds
        .map { linkRepository.markAsSeen(it, it.url).onErrorReturn { Unit } }
        .zipOrEmpty()
        .asUnit()

    override fun getRequest(eventId: String, sessionId: String, url: URL): Single<HostRequest> {
        val session = linkRepository.getSession(sessionId, url)
            ?: return Single.error(WalletLinkException.SessionNotFound)

        return linkRepository.getPendingRequests(session, url)
            .map { requests -> requests.first { eventId == it.hostRequestId.eventId } }
    }

    // MARK: - Helpers

    private fun observeConnection(conn: WalletLinkConnection) {
        conn.requestsObservable
            .observeOn(requestsScheduler)
            .map { Optional(it) }
            .onErrorReturn { Optional(null) }
            .unwrap()
            .subscribe { request ->
                val hostRequestId = request.hostRequestId

                if (processedRequestIds.has(hostRequestId)) {
                    return@subscribe
                }

                processedRequestIds.add(hostRequestId)
                requestsSubject.onNext(request)
            }
            .addTo(disposeBag)
    }
}
