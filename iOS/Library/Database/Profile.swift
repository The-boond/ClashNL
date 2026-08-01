import Foundation
import GRDB
import Network

public class Profile: Record, Identifiable, ObservableObject {
    public var id: Int64?
    public var mustID: Int64 {
        id!
    }

    @Published public var name: String
    public var order: UInt32
    public var type: ProfileType
    public var path: String
    @Published public var remoteURL: String?
    @Published public var autoUpdate: Bool
    @Published public var autoUpdateInterval: Int32
    public var lastUpdated: Date?
    public var subscriptionUpload: Int64?
    public var subscriptionDownload: Int64?
    public var subscriptionTotal: Int64?
    public var subscriptionExpireAt: Date?
    public var subscriptionUpdateInterval: Int32
    public var subscriptionWebPageURL: String?

    public init(id: Int64? = nil, name: String, order: UInt32 = 0, type: ProfileType, path: String, remoteURL: String? = nil, autoUpdate: Bool = false, autoUpdateInterval: Int32 = 0, lastUpdated: Date? = nil, subscriptionUpload: Int64? = nil, subscriptionDownload: Int64? = nil, subscriptionTotal: Int64? = nil, subscriptionExpireAt: Date? = nil, subscriptionUpdateInterval: Int32 = 0, subscriptionWebPageURL: String? = nil) {
        self.id = id
        self.name = name
        self.order = order
        self.type = type
        self.path = path
        self.remoteURL = remoteURL
        self.autoUpdate = autoUpdate
        self.autoUpdateInterval = autoUpdateInterval
        self.lastUpdated = lastUpdated
        self.subscriptionUpload = subscriptionUpload
        self.subscriptionDownload = subscriptionDownload
        self.subscriptionTotal = subscriptionTotal
        self.subscriptionExpireAt = subscriptionExpireAt
        self.subscriptionUpdateInterval = subscriptionUpdateInterval
        self.subscriptionWebPageURL = subscriptionWebPageURL
        super.init()
    }

    override public class var databaseTableName: String {
        "profiles"
    }

    enum Columns: String, ColumnExpression {
        case id, name, order, type, path, remoteURL, autoUpdate, autoUpdateInterval, lastUpdated, subscriptionUpload, subscriptionDownload, subscriptionTotal, subscriptionExpireAt, subscriptionUpdateInterval, subscriptionWebPageURL, userAgent
    }

    required init(row: Row) throws {
        id = row[Columns.id]
        name = row[Columns.name] ?? ""
        order = row[Columns.order] ?? 0
        type = ProfileType(rawValue: row[Columns.type] ?? ProfileType.local.rawValue)!
        path = row[Columns.path] ?? ""
        remoteURL = row[Columns.remoteURL]
        autoUpdate = row[Columns.autoUpdate] ?? false
        autoUpdateInterval = row[Columns.autoUpdateInterval] ?? 0
        lastUpdated = row[Columns.lastUpdated]
        subscriptionUpload = row[Columns.subscriptionUpload]
        subscriptionDownload = row[Columns.subscriptionDownload]
        subscriptionTotal = row[Columns.subscriptionTotal]
        subscriptionExpireAt = row[Columns.subscriptionExpireAt]
        subscriptionUpdateInterval = row[Columns.subscriptionUpdateInterval] ?? 0
        subscriptionWebPageURL = row[Columns.subscriptionWebPageURL]
        try super.init(row: row)
    }

    override public func encode(to container: inout PersistenceContainer) throws {
        container[Columns.id] = id
        container[Columns.name] = name
        container[Columns.order] = order
        container[Columns.type] = type.rawValue
        container[Columns.path] = path
        container[Columns.remoteURL] = remoteURL
        container[Columns.autoUpdate] = autoUpdate
        container[Columns.autoUpdateInterval] = autoUpdateInterval
        container[Columns.lastUpdated] = lastUpdated
        container[Columns.subscriptionUpload] = subscriptionUpload
        container[Columns.subscriptionDownload] = subscriptionDownload
        container[Columns.subscriptionTotal] = subscriptionTotal
        container[Columns.subscriptionExpireAt] = subscriptionExpireAt
        container[Columns.subscriptionUpdateInterval] = subscriptionUpdateInterval
        container[Columns.subscriptionWebPageURL] = subscriptionWebPageURL
    }

    override public func didInsert(_ inserted: InsertionSuccess) {
        super.didInsert(inserted)
        id = inserted.rowID
    }
}

public struct ProfilePreview: Identifiable, Hashable {
    public let id: Int64
    public let name: String
    public var order: UInt32
    public let type: ProfileType
    public let path: String
    public let remoteURL: String?
    public let autoUpdate: Bool
    public let autoUpdateInterval: Int32
    public let lastUpdated: Date?
    public let subscriptionUpload: Int64?
    public let subscriptionDownload: Int64?
    public let subscriptionTotal: Int64?
    public let subscriptionExpireAt: Date?
    public let subscriptionUpdateInterval: Int32
    public let subscriptionWebPageURL: String?
    public let origin: Profile

    public init(_ profile: Profile) {
        id = profile.mustID
        name = profile.name
        order = profile.order
        type = profile.type
        path = profile.path
        remoteURL = profile.remoteURL
        autoUpdate = profile.autoUpdate
        autoUpdateInterval = profile.autoUpdateInterval
        lastUpdated = profile.lastUpdated
        subscriptionUpload = profile.subscriptionUpload
        subscriptionDownload = profile.subscriptionDownload
        subscriptionTotal = profile.subscriptionTotal
        subscriptionExpireAt = profile.subscriptionExpireAt
        subscriptionUpdateInterval = profile.subscriptionUpdateInterval
        subscriptionWebPageURL = profile.subscriptionWebPageURL
        origin = profile
    }
}

public enum ProfileType: Int {
    case local = 0, icloud, remote
}
