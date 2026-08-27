import Foundation
@testable import Vantage

/// Serves canned responses so provider tests exercise the real decode-and-map
/// path — `HTTPClient` status handling included — without touching the network.
///
/// Testing through the provider rather than against the private DTOs is
/// deliberate: the mapping is where the interesting mistakes live (Finnhub's
/// zero-for-missing convention, Tiingo's adjusted-versus-raw closes), and a DTO
/// test would not catch any of them.
final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    struct Response: Sendable {
        let statusCode: Int
        let body: Data
        let headers: [String: String]

        init(statusCode: Int = 200, body: Data, headers: [String: String] = [:]) {
            self.statusCode = statusCode
            self.body = body
            self.headers = headers
        }
    }

    /// Matched against the request URL by substring, first match wins.
    nonisolated(unsafe) private static var routes: [(match: String, response: Response)] = []
    nonisolated(unsafe) private static var recorded: [URLRequest] = []
    private static let lock = NSLock()

    static func reset() {
        lock.withLock { routes = []; recorded = [] }
    }

    static func stub(_ match: String, with response: Response) {
        lock.withLock { routes.append((match, response)) }
    }

    static func stub(_ match: String, fixture: String, statusCode: Int = 200) throws {
        stub(match, with: Response(statusCode: statusCode, body: try Fixture.data(fixture)))
    }

    /// Every request the stub saw, so tests can assert on what was actually sent.
    static var requests: [URLRequest] {
        lock.withLock { recorded }
    }

    /// A session wired to this stub, for injecting into `HTTPClient`.
    static func makeSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: config)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url?.absoluteString ?? ""
        Self.lock.withLock { Self.recorded.append(request) }

        let match = Self.lock.withLock {
            Self.routes.first { url.contains($0.match) }?.response
        }

        guard let match else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }

        let response = HTTPURLResponse(
            url: request.url!, statusCode: match.statusCode,
            httpVersion: "HTTP/1.1", headerFields: match.headers
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: match.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
