// Copyright (c) 2017-2019 Coinbase Inc. See LICENSE

import RxSwift

extension Observable {
    /// Take one entry from an observable and return it as a single
    func takeSingle() -> Single<Element> {
        return take(1).asSingle()
    }
}
