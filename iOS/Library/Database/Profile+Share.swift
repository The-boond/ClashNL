import Foundation
import Libbox

public extension Profile {
    var shareLink: URL {
        URL(string: ProfileImportLink.generate(name: name, remoteURL: remoteURL!))!
    }
}
