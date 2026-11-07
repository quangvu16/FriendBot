//
// Created by Nitish Yadav on 31/07/23.
//

import Foundation
import Starscream

class StarscreamWebSocket: WebSocketProvider {

    var delegate: WebSocketProviderDelegate?
    // Starscream-qualified on purpose: the iOS 26 SDK's Network.WebSocket now makes
    // the bare name ambiguous when files that import Network share a compile batch.
    private let socket: Starscream.WebSocket

    init(url: URL) {
        self.socket = Starscream.WebSocket(request: URLRequest(url: url))
        self.socket.delegate = self
    }

    func connect() {
        self.socket.connect()
    }

    func send(data: Data) {
        self.socket.write(data: data)
    }
}

extension StarscreamWebSocket: Starscream.WebSocketDelegate {

    func didReceive(event: Starscream.WebSocketEvent, client: Starscream.WebSocket) {
        switch event {
        case .connected:
            self.delegate?.webSocketDidConnect(self)
        case .disconnected:
            self.delegate?.webSocketDidDisconnect(self)
        case .text:
            debugPrint("Warning: Expected to receive data format but received a string. Check the websocket server config.")
        case .binary(let data):
            self.delegate?.webSocket(self, didReceiveData: data)
        default:
            break
        }
    }
}
