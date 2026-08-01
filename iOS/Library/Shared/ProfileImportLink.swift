import Foundation
import Libbox

public enum ProfileImportLink {
    public static func generate(name: String, remoteURL: String) -> String {
        let upstreamLink = LibboxGenerateRemoteProfileImportLink(name, remoteURL)
        #if os(iOS)
            guard var components = URLComponents(string: upstreamLink) else {
                return upstreamLink
            }
            components.scheme = "clashnl"
            return components.string ?? upstreamLink
        #else
            return upstreamLink
        #endif
    }
}
