// Copyright (c) 2017-2019 Coinbase Inc. See LICENSE

import CBCrypto
import CBHTTP
import os.log
import RxSwift

public class WalletLink: WalletLinkProtocol {
    private let linkStore = LinkStore()
    private let connection: WalletLinkConnection
    private let operationQueue = OperationQueue()
    private let isConnectedSubject = ReplaySubject<Bool>.create(bufferSize: 1)
    private let signatureRequestsSubject = PublishSubject<SignatureRequest>()
    private var metadata: [ClientMetadataKey: String]

    /// Incoming signature requests
    public let signatureRequestObservable: Observable<SignatureRequest>

    /// Constructor
    ///
    /// - Parameters:
    ///     -  url: WalletLink server URL
    ///     - metadata: client metadata forwarded to host once link is established
    public required init(url: URL, metadata: [ClientMetadataKey: String]) {
        self.metadata = metadata

        connection = WalletLinkConnection(url: url)
        operationQueue.maxConcurrentOperationCount = 1
        signatureRequestObservable = signatureRequestsSubject.asObservable()
    }

    /// Connect to WalletLink server using parameters extracted from QR code scan
    ///
    /// - Parameters:
    ///     - sessionId: WalletLink host generated session ID
    ///     - secret: WalletLink host/guest shared secret
    ///
    /// - Returns: A single wrapping `Void` if connection was successful. Otherwise, an exception is thrown
    public func connect(sessionId: String, secret: String) -> Single<Void> {
        let session = Session(sessionId: sessionId, secret: secret)
        let scheduler = ConcurrentDispatchQueueScheduler(qos: .userInitiated)

        // Connect to WalletLink server (if disconnected)
        _ = startConnection().subscribe()

        // wait for connection to be established, then attempt to join and persist the new session.
        return isConnectedSubject
            .filter { $0 == true }
            .takeSingle()
            .flatMap { _ in self.joinSession(session) }
            .map { _ in self.linkStore.save(sessionId: session.sessionId, secret: session.secret) }
            .timeout(15, scheduler: scheduler)
            .logError()
    }

    /// Set metadata in all active sessions. This metadata will be forwarded to all the hosts
    ///
    /// - Parameters:
    ///   - key: Metadata key
    ///   - value: Metadata value
    ///
    /// - Returns: True if the operation succeeds
    public func setMetadata(key: String, value: String) -> Single<Void> {
        let setMetadataSingles: [Single<Bool>] = linkStore.sessions.compactMap { session in
            guard
                let iv = Data.randomBytes(12),
                let encryptedValue = try? value.encryptUsingAES256GCM(secret: session.secret, iv: iv)
            else {
                assertionFailure("Unable to encrypt \(key):\(value)")
                return nil
            }

            return self.connection.setMetadata(key: key, value: encryptedValue, for: session.sessionId)
                .logError()
                .catchErrorJustReturn(false)
        }

        return Single.zip(setMetadataSingles).asVoid()
    }

    /// Send signature request approval to the requesting host
    ///
    /// - Parameters:
    ///     - requestId: WalletLink request ID
    ///     - signedData: User signed data
    ///
    /// - Returns: A single wrapping a `Void` if successful, or an exception is thrown
    public func approve(requestId _: String, signedData _: Data) -> Single<Void> {
        return .just(())
    }

    /// Send signature request rejection to the requesting host
    ///
    /// - Parameters:
    ///     - requestId: WalletLink request ID
    ///
    /// - Returns: A single wrapping a `Void` if successful, or an exception is thrown
    public func reject(requestId _: String) -> Single<Void> {
        return .just(())
    }

    // MARK: - Connection management

    private func startConnection() -> Single<Void> {
        operationQueue.cancelAllOperations()

        let connectSingle = Internet.statusChanges
            .filter { $0.isOnline }
            .takeSingle()
            .flatMap { _ in self.connection.connect() }
            .map { self.isConnectedSubject.onNext(true) }

        return operationQueue.addSingle(connectSingle)
    }

    private func stopConnection() -> Single<Void> {
        operationQueue.cancelAllOperations()

        let disconnectSingle = connection.disconnect()
            // .logError()
            .catchErrorJustReturn(())
            .map { self.isConnectedSubject.onNext(false) }

        return operationQueue.addSingle(disconnectSingle)
    }

    // MARK: - Session management

    private func joinSessions() -> Single<Void> {
        let joinSessionSingles = linkStore.sessions.map { self.joinSession($0).asVoid().catchErrorJustReturn(()) }

        return Single.zip(joinSessionSingles).asVoid()
    }

    private func joinSession(_ session: Session) -> Single<Bool> {
        let sessionKey = "\(session.sessionId) \(session.secret) WalletLink" // FIXME: hish -.sha256()

        return connection.joinSession(using: sessionKey, for: session.sessionId)
            .flatMap { success -> Single<Bool> in
                guard success else { return .just(false) }

                return self.setSessionConfig(session: session)
            }
            .map { success in
                if success {
                    os_log("[walletlink] successfully joined session %@", type: .debug, session.sessionId)
                } else {
                    os_log("[walletlink] Invalid session %@. Removing...", type: .error, session.sessionId)
                    self.linkStore.delete(sessionId: session.sessionId)
                }

                return success
            }
            .logError()
    }

    private func setSessionConfig(session: Session) -> Single<Bool> {
        guard let iv = Data.randomBytes(12) else { return .error(WalletLinkError.unableToEncryptData) }

        var encryptedMetadata = [String: String]()
        for (key, value) in metadata {
            guard let encryptedValue = try? value.encryptUsingAES256GCM(secret: session.secret, iv: iv) else {
                return .error(WalletLinkError.unableToEncryptData)
            }

            encryptedMetadata[key.rawValue] = encryptedValue
        }

        return connection.setSessionConfig(
            webhookId: "", // FIXME: hish - fill
            webhookUrl: "", // FIXME: hish - fill
            metadata: encryptedMetadata,
            for: session.sessionId
        )
    }
}
