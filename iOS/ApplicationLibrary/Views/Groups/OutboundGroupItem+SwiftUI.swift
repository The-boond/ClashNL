import Library
import SwiftUI

public extension OutboundGroupItem {
    var delayColor: Color {
        switch urlTestDelay {
        case 0:
            return .gray
        case ...250:
            return .green
        case ...350:
            return .blue
        case ...600:
            return .orange
        default:
            return .red
        }
    }
}
